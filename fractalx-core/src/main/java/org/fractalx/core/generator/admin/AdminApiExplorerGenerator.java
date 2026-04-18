package org.fractalx.core.generator.admin;

import org.fractalx.core.model.FractalModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates the API Explorer subsystem for the admin service.
 *
 * <p>Produces 1 class in {@code org.fractalx.admin.explorer}:
 * <ul>
 *   <li>{@code ApiExplorerController} — REST API: /api/explorer/**</li>
 * </ul>
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET  /api/explorer/services           — list all services with base URLs</li>
 *   <li>GET  /api/explorer/{service}/mappings — proxy to service's /actuator/mappings</li>
 *   <li>POST /api/explorer/request            — relay an HTTP request to a service</li>
 * </ul>
 */
class AdminApiExplorerGenerator {

    private static final Logger log = LoggerFactory.getLogger(AdminApiExplorerGenerator.class);

    void generate(Path srcMainJava, String basePackage, List<FractalModule> modules) throws IOException {
        Path pkg = AdminPackageUtil.createPackagePath(srcMainJava, basePackage + ".explorer");
        generateApiExplorerController(pkg);
        log.debug("Generated admin API explorer subsystem ({} modules)", modules.size());
    }

    // -------------------------------------------------------------------------

    private void generateApiExplorerController(Path pkg) throws IOException {
        Files.writeString(pkg.resolve("ApiExplorerController.java"), """
                package org.fractalx.admin.explorer;

                import org.fractalx.admin.services.ServiceMetaRegistry;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.http.*;
                import org.springframework.http.client.SimpleClientHttpRequestFactory;
                import org.springframework.web.bind.annotation.*;
                import org.springframework.web.client.HttpStatusCodeException;
                import org.springframework.web.client.RestTemplate;

                import java.util.*;

                /**
                 * In-browser REST client + endpoint explorer.
                 *
                 * <ul>
                 *   <li>GET  /api/explorer/services           — list all known services</li>
                 *   <li>GET  /api/explorer/{service}/mappings — extract endpoints from actuator/mappings</li>
                 *   <li>POST /api/explorer/request            — proxy a REST call to a service</li>
                 * </ul>
                 */
                @RestController
                @RequestMapping("/api/explorer")
                public class ApiExplorerController {

                    private static final Logger log = LoggerFactory.getLogger(ApiExplorerController.class);

                    private final ServiceMetaRegistry registry;
                    private final RestTemplate        rest;

                    public ApiExplorerController(ServiceMetaRegistry registry) {
                        this.registry = registry;
                        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                        factory.setConnectTimeout(3_000);
                        factory.setReadTimeout(10_000);
                        this.rest = new RestTemplate(factory);
                    }

                    @GetMapping("/services")
                    public List<Map<String, Object>> services() {
                        return registry.getAll().stream()
                                .filter(s -> s.port() > 0)
                                .map(s -> {
                                    Map<String, Object> m = new LinkedHashMap<>();
                                    m.put("name",    s.name());
                                    m.put("port",    s.port());
                                    m.put("baseUrl", "http://localhost:" + s.port());
                                    m.put("type",    s.type());
                                    return m;
                                })
                                .toList();
                    }

                    @GetMapping("/{service}/mappings")
                    public Map<String, Object> mappings(@PathVariable String service) {
                        ServiceMetaRegistry.ServiceMeta meta = registry.findByName(service).orElse(null);
                        if (meta == null) return Map.of("error", "Unknown service: " + service, "endpoints", List.of());
                        String baseUrl = "http://localhost:" + meta.port();
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> raw = rest.getForObject(baseUrl + "/actuator/mappings", Map.class);
                            return extractEndpoints(raw, baseUrl);
                        } catch (Exception e) {
                            log.debug("Could not load mappings for {}: {}", service, e.getMessage());
                            return Map.of("error", "Mappings unavailable: " + e.getMessage(), "endpoints", List.of(), "baseUrl", baseUrl);
                        }
                    }

                    @PostMapping("/request")
                    public Map<String, Object> executeRequest(@RequestBody Map<String, Object> req) {
                        String method = String.valueOf(req.getOrDefault("method", "GET")).toUpperCase();
                        String url    = String.valueOf(req.getOrDefault("url", ""));
                        String body   = req.containsKey("body") ? String.valueOf(req.get("body")) : null;

                        @SuppressWarnings("unchecked")
                        Map<String, String> headerMap = req.containsKey("headers")
                                ? (Map<String, String>) req.get("headers") : Map.of();

                        HttpHeaders headers = new HttpHeaders();
                        headerMap.forEach(headers::set);
                        if (headers.getFirst(HttpHeaders.CONTENT_TYPE) == null && body != null && !body.isBlank())
                            headers.setContentType(MediaType.APPLICATION_JSON);

                        HttpEntity<String> entity = new HttpEntity<>(body, headers);
                        long start = System.currentTimeMillis();
                        try {
                            ResponseEntity<String> resp = rest.exchange(url, HttpMethod.valueOf(method), entity, String.class);
                            return buildResponse(resp.getStatusCode().value(), resp.getStatusCode().toString(),
                                    resp.getHeaders().toSingleValueMap(), resp.getBody(), System.currentTimeMillis() - start);
                        } catch (HttpStatusCodeException e) {
                            return buildResponse(e.getStatusCode().value(), e.getStatusCode().toString(),
                                    e.getResponseHeaders() != null ? e.getResponseHeaders().toSingleValueMap() : Map.of(),
                                    e.getResponseBodyAsString(), System.currentTimeMillis() - start);
                        } catch (Exception e) {
                            Map<String, Object> out = new LinkedHashMap<>();
                            out.put("status",     0);
                            out.put("statusText", "Connection error");
                            out.put("headers",    Map.of());
                            out.put("body",       e.getMessage());
                            out.put("durationMs", System.currentTimeMillis() - start);
                            out.put("error",      true);
                            return out;
                        }
                    }

                    // -------------------------------------------------------------------------

                    private Map<String, Object> buildResponse(int status, String statusText,
                            Map<String, String> headers, String body, long durationMs) {
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("status",     status);
                        out.put("statusText", statusText);
                        out.put("headers",    headers);
                        out.put("body",       body);
                        out.put("durationMs", durationMs);
                        out.put("error",      status >= 400);
                        return out;
                    }

                    @SuppressWarnings("unchecked")
                    private Map<String, Object> extractEndpoints(Map<String, Object> raw, String baseUrl) {
                        List<Map<String, Object>> endpoints = new ArrayList<>();
                        if (raw == null) return Map.of("endpoints", endpoints, "baseUrl", baseUrl, "total", 0);
                        Object contexts = raw.get("contexts");
                        if (contexts instanceof Map<?, ?> ctxMap) {
                            ctxMap.values().forEach(ctxData -> {
                                if (!(ctxData instanceof Map<?, ?> ctx)) return;
                                Object mappings = ctx.get("mappings");
                                if (!(mappings instanceof Map<?, ?> mMap)) return;
                                Object dispatchers = mMap.get("dispatcherServlets");
                                if (!(dispatchers instanceof Map<?, ?> dsMap)) return;
                                dsMap.values().forEach(dsMappings -> {
                                    if (!(dsMappings instanceof List<?> list)) return;
                                    for (Object mapping : list) {
                                        if (!(mapping instanceof Map<?, ?> mEntry)) continue;
                                        Object details = mEntry.get("details");
                                        if (!(details instanceof Map<?, ?> dMap)) continue;
                                        Object rmc = dMap.get("requestMappingConditions");
                                        if (!(rmc instanceof Map<?, ?> rmcMap)) continue;
                                        Object patterns = rmcMap.get("patterns");
                                        Object methods  = rmcMap.get("methods");
                                        Object consumes = rmcMap.get("consumes");
                                        if (!(patterns instanceof List<?> patList) || patList.isEmpty()) continue;
                                        Map<String, Object> ep = new LinkedHashMap<>();
                                        ep.put("path",    String.valueOf(patList.get(0)));
                                        ep.put("methods", methods instanceof List<?> ml && !ml.isEmpty() ? ml : List.of("GET"));
                                        ep.put("consumes",consumes instanceof List<?> cl ? cl : List.of());
                                        ep.put("handler", mEntry.get("handler"));
                                        endpoints.add(ep);
                                    }
                                });
                            });
                        }
                        endpoints.sort(Comparator.comparing(e -> String.valueOf(e.getOrDefault("path", ""))));
                        return Map.of("endpoints", endpoints, "baseUrl", baseUrl, "total", endpoints.size());
                    }
                }
                """);
    }
}
