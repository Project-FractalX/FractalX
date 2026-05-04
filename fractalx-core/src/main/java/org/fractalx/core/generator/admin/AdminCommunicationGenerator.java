package org.fractalx.core.generator.admin;

import org.fractalx.core.model.FractalModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates the Communication section REST controller for the admin service.
 * <p>
 * The generated {@code CommunicationController} exposes:
 * <ul>
 *   <li>Service dependency topology with gRPC edges</li>
 *   <li>NetScope dependency links (baked from FractalModule.dependencies)</li>
 *   <li>API gateway health + metrics proxy</li>
 *   <li>Service-discovery registry stats proxy</li>
 * </ul>
 */
class AdminCommunicationGenerator {

    private static final Logger log = LoggerFactory.getLogger(AdminCommunicationGenerator.class);

    void generate(Path srcMainJava, String basePackage, List<FractalModule> modules) throws IOException {
        Path pkg = AdminPackageUtil.createPackagePath(srcMainJava, basePackage + ".communication");
        generateCommunicationController(pkg, modules);
        log.debug("Generated admin communication controller");
    }

    // -------------------------------------------------------------------------

    private void generateCommunicationController(Path pkg, List<FractalModule> modules)
            throws IOException {

        // Build baked-in netscope data: List<Map> with service + dependencies
        StringBuilder netScopeInit = new StringBuilder();
        for (FractalModule m : modules) {
            if (m.getDependencies() == null || m.getDependencies().isEmpty()) continue;
            netScopeInit.append(String.format(
                    "        {Map<String,Object> svc = new LinkedHashMap<>(); svc.put(\"service\",\"%s\"); List<Map<String,Object>> deps = new ArrayList<>();\n",
                    m.getServiceName()));
            for (String dep : m.getDependencies()) {
                String targetName = dep.replaceAll("(?i)(Service|Client)$", "");
                targetName = targetName.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase() + "-service";
                int targetPort = resolveTargetGrpcPort(modules, targetName);
                netScopeInit.append(String.format(
                        "        Map<String,Object> d%s = new LinkedHashMap<>(); d%s.put(\"name\",\"%s\"); d%s.put(\"grpcPort\",%d); d%s.put(\"protocol\",\"NetScope/gRPC\"); d%s.put(\"portConvention\",\"HTTP port + 10000\"); deps.add(d%s);\n",
                        sanitize(targetName), sanitize(targetName), targetName,
                        sanitize(targetName), targetPort,
                        sanitize(targetName), sanitize(targetName), sanitize(targetName)));
            }
            netScopeInit.append("        svc.put(\"dependencies\", deps); netScopeData.add(svc);}\n");
        }

        String content = """
                package org.fractalx.admin.communication;

                import org.fractalx.admin.services.ServiceMetaRegistry;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.*;
                import org.springframework.web.client.RestTemplate;

                import java.util.*;

                /**
                 * REST API for the Communication section of the admin dashboard.
                 *
                 * <pre>
                 * GET /api/communication/topology            — full dependency graph (nodes + gRPC edges)
                 * GET /api/communication/netscope            — all NetScope dependency links
                 * GET /api/communication/netscope/{service} — NetScope links for one service
                 * GET /api/communication/gateway/health     — proxy → gateway :9999/actuator/health
                 * GET /api/communication/gateway/metrics    — proxy → gateway :9999/actuator/metrics
                 * GET /api/communication/discovery/stats    — proxy → registry /services summary
                 * </pre>
                 */
                @RestController
                @RequestMapping("/api/communication")
                @CrossOrigin(origins = "*")
                public class CommunicationController {

                    private final ServiceMetaRegistry registry;
                    private final RestTemplate        restTemplate = new RestTemplate();

                    @Value("${fractalx.registry.url:http://localhost:8761}")
                    private String registryUrl;

                    public CommunicationController(ServiceMetaRegistry registry) {
                        this.registry = registry;
                    }

                    /** Returns the full service dependency graph with gRPC edges. */
                    @GetMapping("/topology")
                    public ResponseEntity<Map<String, Object>> getTopology() {
                        List<Map<String, Object>> nodes = new ArrayList<>();
                        List<Map<String, Object>> edges = new ArrayList<>();

                        for (ServiceMetaRegistry.ServiceMeta meta : registry.getAll()) {
                            Map<String, Object> node = new LinkedHashMap<>();
                            node.put("id",    meta.name());
                            node.put("label", meta.name());
                            node.put("port",  meta.port());
                            node.put("type",  meta.type());
                            nodes.add(node);

                            for (String dep : meta.dependencies()) {
                                Map<String, Object> edge = new LinkedHashMap<>();
                                edge.put("source",   meta.name());
                                edge.put("target",   dep);
                                edge.put("protocol", "NetScope/gRPC");
                                edges.add(edge);
                            }
                        }
                        return ResponseEntity.ok(Map.of("nodes", nodes, "edges", edges));
                    }

                    /** Returns baked-in NetScope dependency information for all services. */
                    @GetMapping("/netscope")
                    public ResponseEntity<List<Map<String, Object>>> getNetScopeLinks() {
                        List<Map<String, Object>> netScopeData = new ArrayList<>();
                %s                return ResponseEntity.ok(netScopeData);
                    }

                    /** Returns NetScope dependency information for a single service. */
                    @GetMapping("/netscope/{service}")
                    public ResponseEntity<Object> getNetScopeForService(@PathVariable("service") String service) {
                        return registry.findByName(service).map(meta -> {
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("service", meta.name());
                            List<Map<String, Object>> deps = new ArrayList<>();
                            for (String dep : meta.dependencies()) {
                                registry.findByName(dep).ifPresent(depMeta -> {
                                    Map<String, Object> d = new LinkedHashMap<>();
                                    d.put("name",     depMeta.name());
                                    d.put("grpcPort", depMeta.grpcPort());
                                    d.put("protocol", "NetScope/gRPC");
                                    deps.add(d);
                                });
                            }
                            result.put("dependencies", deps);
                            return ResponseEntity.ok((Object) result);
                        }).orElse(ResponseEntity.notFound().build());
                    }

                    /** Proxies to the API gateway's actuator health endpoint. */
                    @GetMapping("/gateway/health")
                    public ResponseEntity<Object> getGatewayHealth() {
                        String base = resolveBaseUrl("fractalx-gateway");
                        if (base == null) {
                            return ResponseEntity.ok(Map.of("status", "DOWN",
                                    "error", "Gateway not registered with FractalX Registry"));
                        }
                        try {
                            Object resp = restTemplate.getForObject(
                                    base + "/actuator/health", Object.class);
                            return ResponseEntity.ok(resp);
                        } catch (Exception e) {
                            return ResponseEntity.ok(Map.of("status", "DOWN", "error", e.getMessage()));
                        }
                    }

                    /** Proxies to the API gateway's actuator metrics endpoint. */
                    @GetMapping("/gateway/metrics")
                    public ResponseEntity<Object> getGatewayMetrics() {
                        String base = resolveBaseUrl("fractalx-gateway");
                        if (base == null) {
                            return ResponseEntity.ok(Map.of(
                                    "error", "Gateway not registered with FractalX Registry"));
                        }
                        try {
                            Object resp = restTemplate.getForObject(
                                    base + "/actuator/metrics", Object.class);
                            return ResponseEntity.ok(resp);
                        } catch (Exception e) {
                            return ResponseEntity.ok(Map.of("error", e.getMessage()));
                        }
                    }

                    /**
                     * Resolves a service's live base URL ({@code http://host:port}) by looking
                     * up its registration in the FractalX Registry. Returns {@code null} if
                     * the service is not registered or the registry is unreachable.
                     */
                    @SuppressWarnings("unchecked")
                    private String resolveBaseUrl(String serviceName) {
                        try {
                            Map<String, Object> reg = restTemplate.getForObject(
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

                    /** Returns service-discovery registry stats: registered count + service list. */
                    @GetMapping("/discovery/stats")
                    public ResponseEntity<Map<String, Object>> getDiscoveryStats() {
                        Map<String, Object> result = new LinkedHashMap<>();
                        try {
                            Object services = restTemplate.getForObject(registryUrl + "/services", Object.class);
                            result.put("status",   "UP");
                            result.put("services", services);
                            if (services instanceof List<?> list) {
                                result.put("count", list.size());
                            }
                        } catch (Exception e) {
                            result.put("status", "DOWN");
                            result.put("error",  e.getMessage());
                            result.put("count",  0);
                        }
                        result.put("registryUrl", registryUrl);
                        return ResponseEntity.ok(result);
                    }
                }
                """.formatted(netScopeInit.toString());

        Files.writeString(pkg.resolve("CommunicationController.java"), content);
    }

    // -------------------------------------------------------------------------

    /** Returns the gRPC port for a given target service name, or port+10000 heuristic. */
    private int resolveTargetGrpcPort(List<FractalModule> modules, String targetServiceName) {
        return modules.stream()
                .filter(m -> m.getServiceName().equals(targetServiceName))
                .findFirst()
                .map(FractalModule::grpcPort)
                .orElse(0);
    }

    /** Turns "payment-service" into "paymentservice" for use as a Java identifier fragment. */
    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9]", "");
    }
}
