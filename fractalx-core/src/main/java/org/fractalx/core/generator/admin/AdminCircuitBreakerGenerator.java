package org.fractalx.core.generator.admin;

import org.fractalx.core.model.FractalModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates the Circuit Breaker monitoring subsystem for the admin service.
 *
 * <p>Produces 1 class in {@code org.fractalx.admin.circuitbreaker}:
 * <ul>
 *   <li>{@code CircuitBreakerController} — REST API: /api/circuit-breakers/**</li>
 * </ul>
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /api/circuit-breakers         — all services aggregated CB state</li>
 *   <li>GET /api/circuit-breakers/{service} — CB state for one service</li>
 * </ul>
 *
 * <p>Each service is polled at its {@code /actuator/circuitbreakers} endpoint.
 * Requires {@code circuitbreakers} in {@code management.endpoints.web.exposure.include}.
 */
class AdminCircuitBreakerGenerator {

    private static final Logger log = LoggerFactory.getLogger(AdminCircuitBreakerGenerator.class);

    void generate(Path srcMainJava, String basePackage, List<FractalModule> modules) throws IOException {
        Path pkg = AdminPackageUtil.createPackagePath(srcMainJava, basePackage + ".circuitbreaker");
        generateController(pkg);
        log.debug("Generated admin circuit breaker subsystem ({} modules)", modules.size());
    }

    // -------------------------------------------------------------------------

    private void generateController(Path pkg) throws IOException {
        Files.writeString(pkg.resolve("CircuitBreakerController.java"), """
                package org.fractalx.admin.circuitbreaker;

                import org.fractalx.admin.services.ServiceMetaRegistry;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.http.client.SimpleClientHttpRequestFactory;
                import org.springframework.web.bind.annotation.*;
                import org.springframework.web.client.RestTemplate;

                import java.util.*;

                /**
                 * Proxies Resilience4j circuit breaker state from each microservice's
                 * {@code /actuator/circuitbreakers} endpoint.
                 *
                 * <ul>
                 *   <li>GET /api/circuit-breakers           — all services, all CBs</li>
                 *   <li>GET /api/circuit-breakers/{service} — one service's CBs</li>
                 * </ul>
                 */
                @RestController
                @RequestMapping("/api/circuit-breakers")
                public class CircuitBreakerController {

                    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerController.class);

                    private final ServiceMetaRegistry registry;
                    private final RestTemplate        rest;

                    @Value("${fractalx.registry.url:http://localhost:8761}")
                    private String registryUrl;

                    public CircuitBreakerController(ServiceMetaRegistry registry) {
                        this.registry = registry;
                        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
                        f.setConnectTimeout(2_000);
                        f.setReadTimeout(4_000);
                        this.rest = new RestTemplate(f);
                    }

                    /** Returns circuit breaker state for every known microservice. */
                    @GetMapping
                    public List<Map<String, Object>> all() {
                        List<Map<String, Object>> result = new ArrayList<>();
                        registry.getByType("microservice").forEach(meta ->
                                result.add(fetchForService(meta)));
                        return result;
                    }

                    /** Returns circuit breaker state for a single named service. */
                    @GetMapping("/{service}")
                    public Map<String, Object> one(@PathVariable String service) {
                        return registry.findByName(service)
                                .map(this::fetchForService)
                                .orElse(Map.of("service", service, "error", "Unknown service",
                                               "circuitBreakers", List.of()));
                    }

                    // -------------------------------------------------------------------------

                    @SuppressWarnings("unchecked")
                    private Map<String, Object> fetchForService(ServiceMetaRegistry.ServiceMeta meta) {
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("service", meta.name());
                        out.put("port",    meta.port());
                        String base = resolveBaseUrl(meta.name());
                        if (base == null) {
                            out.put("error", "service not registered");
                            out.put("circuitBreakers", List.of());
                            return out;
                        }
                        String url = base + "/actuator/circuitbreakers";
                        try {
                            Map<String, Object> raw = rest.getForObject(url, Map.class);
                            List<Map<String, Object>> cbs = new ArrayList<>();
                            if (raw != null && raw.get("circuitBreakers") instanceof List<?> list) {
                                for (Object item : list) {
                                    if (item instanceof Map<?, ?> cb) {
                                        Map<String, Object> entry = new LinkedHashMap<>();
                                        entry.put("name",                 cb.get("name"));
                                        entry.put("state",                cb.get("state"));
                                        entry.put("failureRate",          cb.get("failureRate"));
                                        entry.put("slowCallRate",         cb.get("slowCallRate"));
                                        entry.put("failureRateThreshold", cb.get("failureRateThreshold"));
                                        entry.put("bufferedCalls",        cb.get("bufferedCalls"));
                                        entry.put("failedCalls",          cb.get("failedCalls"));
                                        entry.put("successfulCalls",      cb.get("successfulCalls"));
                                        entry.put("notPermittedCalls",    cb.get("notPermittedCalls"));
                                        cbs.add(entry);
                                    }
                                }
                            }
                            out.put("circuitBreakers", cbs);
                            out.put("reachable", true);
                        } catch (Exception e) {
                            log.debug("Could not fetch CB state for {}: {}", meta.name(), e.getMessage());
                            out.put("circuitBreakers", List.of());
                            out.put("reachable", false);
                            out.put("error", e.getMessage());
                        }
                        return out;
                    }

                    /**
                     * Resolves a service's live base URL ({@code http://host:port}) by looking
                     * up its registration in the FractalX Registry.
                     */
                    @SuppressWarnings("unchecked")
                    private String resolveBaseUrl(String serviceName) {
                        try {
                            Map<String, Object> reg = rest.getForObject(
                                    registryUrl + "/services/" + serviceName, Map.class);
                            if (reg == null) return null;
                            Object host = reg.get("host");
                            Object port = reg.get("port");
                            if (host == null || !(port instanceof Number)) return null;
                            return "http://" + host + ":" + ((Number) port).intValue();
                        } catch (Exception e) {
                            return null;
                        }
                    }
                }
                """);
    }
}
