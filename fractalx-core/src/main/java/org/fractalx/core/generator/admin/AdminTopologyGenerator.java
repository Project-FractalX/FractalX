package org.fractalx.core.generator.admin;

import org.fractalx.core.model.FractalModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates the service topology model, provider, and REST controller for the admin
 * service. The module dependency graph is baked in at generation time so the admin
 * service can serve the topology without any live analysis.
 */
class AdminTopologyGenerator {

    private static final Logger log = LoggerFactory.getLogger(AdminTopologyGenerator.class);

    void generate(Path srcMainJava, String basePackage, List<FractalModule> modules) throws IOException {
        generate(srcMainJava, basePackage, modules, false);
    }

    void generate(Path srcMainJava, String basePackage, List<FractalModule> modules, boolean hasAuth) throws IOException {
        Path pkg = AdminPackageUtil.createPackagePath(srcMainJava, basePackage + ".topology");

        generateTopologyModel(pkg);
        generateTopologyProvider(pkg, modules, hasAuth);
        generateTopologyController(pkg, modules, hasAuth);

        log.debug("Generated admin topology");
    }

    private void generateTopologyModel(Path pkg) throws IOException {
        String content = """
                package org.fractalx.admin.topology;

                import java.util.List;

                public class TopologyGraph {

                    private final List<ServiceNode> nodes;
                    private final List<ServiceEdge> edges;

                    public TopologyGraph(List<ServiceNode> nodes, List<ServiceEdge> edges) {
                        this.nodes = nodes;
                        this.edges = edges;
                    }

                    public List<ServiceNode> getNodes() { return nodes; }
                    public List<ServiceEdge> getEdges() { return edges; }

                    public record ServiceNode(String id, String label, int port, String type) {}
                    public record ServiceEdge(String source, String target, String protocol) {}
                }
                """;
        Files.writeString(pkg.resolve("TopologyGraph.java"), content);
    }

    private void generateTopologyProvider(Path pkg, List<FractalModule> modules, boolean hasAuth) throws IOException {
        StringBuilder nodes = new StringBuilder();
        StringBuilder edges = new StringBuilder();

        for (FractalModule m : modules) {
            nodes.append(String.format(
                    "        nodes.add(new TopologyGraph.ServiceNode(\"%s\", \"%s\", %d, \"microservice\"));\n",
                    m.getServiceName(), m.getServiceName(), m.getPort()));
            for (String dep : m.getDependencies()) {
                String target = dep.replaceAll("(?i)(Service|Client)$", "");
                target = target.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase() + "-service";
                edges.append(String.format(
                        "        edges.add(new TopologyGraph.ServiceEdge(\"%s\", \"%s\", \"grpc\"));\n",
                        m.getServiceName(), target));
            }
        }
        if (hasAuth) {
            nodes.append("        nodes.add(new TopologyGraph.ServiceNode(\"auth-service\",      \"Auth Service\",    8090, \"auth\"));\n");
        }
        nodes.append("        nodes.add(new TopologyGraph.ServiceNode(\"fractalx-gateway\",   \"API Gateway\",     9999, \"gateway\"));\n");
        nodes.append("        nodes.add(new TopologyGraph.ServiceNode(\"fractalx-registry\",  \"Service Registry\", 8761, \"registry\"));\n");
        nodes.append("        nodes.add(new TopologyGraph.ServiceNode(\"admin-service\",       \"Admin Dashboard\",  9090, \"admin\"));\n");

        String content = """
                package org.fractalx.admin.topology;

                import org.springframework.stereotype.Component;

                import java.util.ArrayList;
                import java.util.List;

                /** Baked-in service topology graph — nodes and edges derived at generation time. */
                @Component
                public class ServiceTopologyProvider {

                    public TopologyGraph getTopology() {
                        List<TopologyGraph.ServiceNode> nodes = new ArrayList<>();
                        List<TopologyGraph.ServiceEdge> edges = new ArrayList<>();

                %s
                %s
                        return new TopologyGraph(nodes, edges);
                    }
                }
                """.formatted(nodes.toString(), edges.toString());
        Files.writeString(pkg.resolve("ServiceTopologyProvider.java"), content);
    }

    private void generateTopologyController(Path pkg, List<FractalModule> modules, boolean hasAuth) throws IOException {
        StringBuilder healthChecks = new StringBuilder();
        for (FractalModule m : modules) {
            healthChecks.append(String.format(
                    "        summary.put(\"%s\", checkHealth(\"%s\", %d, \"/actuator/health\"));\n",
                    m.getServiceName(), m.getServiceName(), m.getPort()));
        }
        if (hasAuth) {
            healthChecks.append("        summary.put(\"auth-service\",      checkHealth(\"auth-service\",    8090, \"/actuator/health\"));\n");
        }
        healthChecks.append("        summary.put(\"fractalx-registry\", checkHealth(\"fractalx-registry\", 8761, \"/services/health\"));\n");
        healthChecks.append("        summary.put(\"fractalx-gateway\",  checkHealth(\"fractalx-gateway\",  9999, \"/actuator/health\"));\n");
        healthChecks.append("        summary.put(\"admin-service\",     \"UP\"); // self — always reachable if this endpoint is responding\n");
        healthChecks.append("        summary.put(\"logger-service\",    checkHealth(\"logger-service\", 9099, \"/actuator/health\"));\n");

        String content = """
                package org.fractalx.admin.topology;

                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.core.env.Environment;
                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.client.RestTemplate;

                import java.net.InetSocketAddress;
                import java.net.Socket;
                import java.util.Arrays;
                import java.util.LinkedHashMap;
                import java.util.Map;

                @RestController
                @RequestMapping("/api")
                public class TopologyController {

                    private final ServiceTopologyProvider topologyProvider;
                    private final RestTemplate restTemplate = new RestTemplate();
                    private final Environment  environment;

                    @Value("${fractalx.registry.url:http://localhost:8761}")
                    private String registryUrl;

                    public TopologyController(ServiceTopologyProvider topologyProvider, Environment environment) {
                        this.topologyProvider = topologyProvider;
                        this.environment      = environment;
                    }

                    /** Returns the static service dependency graph (nodes + edges). */
                    @GetMapping("/topology")
                    public ResponseEntity<TopologyGraph> getTopology() {
                        return ResponseEntity.ok(topologyProvider.getTopology());
                    }

                    /** Polls live health from each known service and returns a name→status map. */
                    @GetMapping("/health/summary")
                    public ResponseEntity<Map<String, String>> getHealthSummary() {
                        Map<String, String> summary = new LinkedHashMap<>();
                %s
                        return ResponseEntity.ok(summary);
                    }

                    /** Proxies the fractalx-registry /services endpoint for the admin UI. */
                    @GetMapping("/services")
                    public ResponseEntity<Object> getLiveServices() {
                        try {
                            return ResponseEntity.ok(
                                    restTemplate.getForObject(registryUrl + "/services", Object.class));
                        } catch (Exception e) {
                            return ResponseEntity.ok(
                                    Map.of("error", "Registry unavailable: " + e.getMessage()));
                        }
                    }

                    /**
                     * TCP-first check: RUNNING if the port accepts a connection,
                     * then optionally confirm via actuator path.
                     */
                    private String checkHealth(String serviceName, int port, String actuatorPath) {
                        boolean docker = Arrays.asList(environment.getActiveProfiles()).contains("docker");
                        String host = docker ? serviceName : "localhost";
                        // Phase 1: is the process up at all?
                        try (Socket s = new Socket()) {
                            s.connect(new InetSocketAddress(host, port), 2000);
                        } catch (Exception e) {
                            return "DOWN";
                        }
                        // Phase 2: actuator health (parse JSON for robust status detection)
                        try {
                            @SuppressWarnings("unchecked")
                            java.util.Map<String, Object> body = restTemplate.getForObject(
                                    "http://" + host + ":" + port + actuatorPath, java.util.Map.class);
                            if (body == null) return "RUNNING";
                            String status = String.valueOf(body.getOrDefault("status", "UNKNOWN"));
                            if ("UP".equalsIgnoreCase(status)) return "UP";
                            if ("DOWN".equalsIgnoreCase(status)) return "DEGRADED";
                            if ("OUT_OF_SERVICE".equalsIgnoreCase(status)) return "DEGRADED";
                            return "RUNNING";
                        } catch (Exception e) {
                            return "RUNNING"; // port open but actuator not exposed
                        }
                    }
                }
                """.formatted(healthChecks.toString());
        Files.writeString(pkg.resolve("TopologyController.java"), content);
    }
}
