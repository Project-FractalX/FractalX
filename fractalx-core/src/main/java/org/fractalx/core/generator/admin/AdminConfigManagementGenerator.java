package org.fractalx.core.generator.admin;

import org.fractalx.core.config.FractalxConfig;
import org.fractalx.core.model.FractalModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates the configuration management sub-system for the admin service:
 * <ul>
 *   <li>{@code ServiceConfigStore}      — baked-in per-service config (ports, env vars, schemas)</li>
 *   <li>{@code ConfigController}        — REST API to view service configurations and lifecycle commands</li>
 *   <li>{@code RuntimeConfigController} — REST API to view/override platform-level config at runtime</li>
 * </ul>
 */
class AdminConfigManagementGenerator {

    private static final Logger log = LoggerFactory.getLogger(AdminConfigManagementGenerator.class);

    void generate(Path srcMainJava, String basePackage, List<FractalModule> modules,
                  FractalxConfig fractalxConfig) throws IOException {
        Path pkg = AdminPackageUtil.createPackagePath(srcMainJava, basePackage + ".svcconfig");

        generateServiceConfigStore(pkg, modules, fractalxConfig);
        generateConfigController(pkg);
        generateRuntimeConfigController(pkg, fractalxConfig);

        log.debug("Generated admin config management components");
    }

    // -------------------------------------------------------------------------

    private void generateServiceConfigStore(Path pkg, List<FractalModule> modules,
                                            FractalxConfig fractalxConfig) throws IOException {
        StringBuilder configEntries = new StringBuilder();

        for (FractalModule m : modules) {
            String schemas = buildListLiteral(m.getOwnedSchemas());
            String envVars = buildEnvVarsLiteral(m, fractalxConfig);
            boolean hasOutbox = m.getDependencies() != null && !m.getDependencies().isEmpty();

            configEntries.append(String.format(
                    "        new ServiceConfig(\"%s\", %d, %d, \"%s\", \"%s\", %b, false, %s, %s),\n",
                    m.getServiceName(), m.getPort(), m.grpcPort(),
                    m.getPackageName(), m.getClassName(),
                    hasOutbox, schemas, envVars));
        }
        // Infra services — ports from fractalxConfig where applicable
        configEntries.append(String.format(
                "        new ServiceConfig(\"fractalx-registry\", 8761, 0, \"\", \"\", false, false, List.of(), Map.of(\"SPRING_PROFILES_ACTIVE\",\"docker\",\"FRACTALX_REGISTRY_URL\",\"%s\")),\n",
                fractalxConfig.registryUrl()));
        configEntries.append(String.format(
                "        new ServiceConfig(\"fractalx-gateway\", %d, 0, \"\", \"\", false, false, List.of(), Map.of(\"SPRING_PROFILES_ACTIVE\",\"docker\")),\n",
                fractalxConfig.gatewayPort()));
        configEntries.append(String.format(
                "        new ServiceConfig(\"admin-service\",     %d, 0, \"\", \"\", false, false, List.of(), Map.of(\"SPRING_PROFILES_ACTIVE\",\"docker\",\"FRACTALX_LOGGER_URL\",\"%s\")),\n",
                fractalxConfig.adminPort(), fractalxConfig.loggerUrl()));
        configEntries.append("        new ServiceConfig(\"logger-service\",    9099, 0, \"\", \"\", false, false, List.of(), Map.of(\"SPRING_PROFILES_ACTIVE\",\"docker\"))\n");

        String content = """
                package org.fractalx.admin.svcconfig;

                import org.springframework.stereotype.Component;

                import java.util.List;
                import java.util.Map;
                import java.util.Optional;

                /**
                 * Baked-in configuration registry for all generated services.
                 * Each entry captures port assignments, package info, environment variables,
                 * schema ownership, and feature flags derived at generation time.
                 */
                @Component
                public class ServiceConfigStore {

                    public record ServiceConfig(
                            String          name,
                            int             httpPort,
                            int             grpcPort,
                            String          packageName,
                            String          className,
                            boolean         hasOutbox,
                            boolean         hasSaga,
                            List<String>    ownedSchemas,
                            Map<String, String> envVars) {}

                    private static final List<ServiceConfig> CONFIGS = List.of(
                %s    );

                    public List<ServiceConfig> getAll()                       { return CONFIGS; }
                    public int                 count()                        { return CONFIGS.size(); }

                    public Optional<ServiceConfig> findByName(String name) {
                        return CONFIGS.stream().filter(c -> c.name().equals(name)).findFirst();
                    }

                    public List<ServiceConfig> getMicroservices() {
                        return CONFIGS.stream()
                                .filter(c -> c.httpPort() > 0 && c.grpcPort() > 0)
                                .toList();
                    }
                }
                """.formatted(configEntries.toString());

        Files.writeString(pkg.resolve("ServiceConfigStore.java"), content);
    }

    private void generateConfigController(Path pkg) throws IOException {
        String content = """
                package org.fractalx.admin.svcconfig;

                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.*;

                import java.util.*;

                /**
                 * REST API for the Configuration section of the admin dashboard.
                 *
                 * <pre>
                 * GET /api/config/services         — all service configurations
                 * GET /api/config/services/{name}  — single service configuration
                 * GET /api/config/environment      — all env vars grouped by service
                 * GET /api/config/ports            — port mapping summary (HTTP + gRPC)
                 * GET /api/config/commands/{name}  — docker-compose lifecycle commands
                 * </pre>
                 */
                @RestController
                @RequestMapping("/api/config")
                @CrossOrigin(origins = "*")
                public class ConfigController {

                    private final ServiceConfigStore configStore;

                    public ConfigController(ServiceConfigStore configStore) {
                        this.configStore = configStore;
                    }

                    /** Returns the full configuration list for all services. */
                    @GetMapping("/services")
                    public ResponseEntity<List<ServiceConfigStore.ServiceConfig>> getAllConfigs() {
                        return ResponseEntity.ok(configStore.getAll());
                    }

                    /** Returns the configuration for a single service by name. */
                    @GetMapping("/services/{name}")
                    public ResponseEntity<ServiceConfigStore.ServiceConfig> getServiceConfig(
                            @PathVariable("name") String name) {
                        return configStore.findByName(name)
                                .map(ResponseEntity::ok)
                                .orElse(ResponseEntity.notFound().build());
                    }

                    /** Returns all environment variables grouped by service name. */
                    @GetMapping("/environment")
                    public ResponseEntity<Map<String, Map<String, String>>> getEnvironment() {
                        Map<String, Map<String, String>> result = new LinkedHashMap<>();
                        for (ServiceConfigStore.ServiceConfig cfg : configStore.getAll()) {
                            result.put(cfg.name(), cfg.envVars());
                        }
                        return ResponseEntity.ok(result);
                    }

                    /** Returns HTTP and gRPC port mapping for all services. */
                    @GetMapping("/ports")
                    public ResponseEntity<List<Map<String, Object>>> getPortMapping() {
                        List<Map<String, Object>> result = new ArrayList<>();
                        for (ServiceConfigStore.ServiceConfig cfg : configStore.getAll()) {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("name",     cfg.name());
                            entry.put("httpPort", cfg.httpPort());
                            entry.put("grpcPort", cfg.grpcPort());
                            entry.put("hasOutbox",cfg.hasOutbox());
                            entry.put("hasSaga",  cfg.hasSaga());
                            result.add(entry);
                        }
                        return ResponseEntity.ok(result);
                    }

                    /** Returns docker-compose lifecycle commands for a service. */
                    @GetMapping("/commands/{name}")
                    public ResponseEntity<Map<String, String>> getLifecycleCommands(
                            @PathVariable("name") String name) {
                        Map<String, String> cmds = new LinkedHashMap<>();
                        cmds.put("start",   "docker compose up -d " + name);
                        cmds.put("stop",    "docker compose stop " + name);
                        cmds.put("restart", "docker compose restart " + name);
                        cmds.put("logs",    "docker compose logs -f " + name);
                        cmds.put("build",   "docker compose build " + name);
                        cmds.put("pull",    "docker compose pull " + name);
                        return ResponseEntity.ok(cmds);
                    }
                }
                """;

        Files.writeString(pkg.resolve("ConfigController.java"), content);
    }

    private void generateRuntimeConfigController(Path pkg, FractalxConfig cfg) throws IOException {
        String content = """
                package org.fractalx.admin.svcconfig;

                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.*;

                import java.util.*;
                import java.util.concurrent.ConcurrentHashMap;

                /**
                 * Exposes the platform-level configuration baked in at generation time and allows
                 * in-memory overrides that are visible to the admin dashboard.
                 *
                 * <pre>
                 * GET    /api/config/runtime          — generation defaults + current overrides + effective values
                 * PUT    /api/config/runtime/{key}    — set an in-memory override (body: {"value":"..."})
                 * DELETE /api/config/runtime/{key}    — remove override (revert to generation default)
                 * GET    /api/config/runtime/diff     — list keys that differ from generation defaults
                 * </pre>
                 *
                 * <p>Overrides are stored in memory only and reset on service restart.
                 * To make changes permanent, update the source application.yml / fractalx-config.yml
                 * and re-run {@code mvn fractalx:decompose}.
                 */
                @RestController
                @RequestMapping("/api/config/runtime")
                @CrossOrigin(origins = "*")
                public class RuntimeConfigController {

                    /** Values captured from the pre-decomposed application config at generation time. */
                    private static final Map<String, String> GENERATION_DEFAULTS;
                    static {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("registryUrl",        "%s");
                        m.put("loggerUrl",          "%s");
                        m.put("otelEndpoint",       "%s");
                        m.put("gatewayPort",        "%d");
                        m.put("adminPort",          "%d");
                        m.put("corsAllowedOrigins", "%s");
                        m.put("oauth2JwksUri",      "%s");
                        GENERATION_DEFAULTS = Collections.unmodifiableMap(m);
                    }

                    /** In-memory overrides — reset on service restart. */
                    private final Map<String, String> overrides = new ConcurrentHashMap<>();

                    /** Returns defaults, current overrides, and the effective merged view. */
                    @GetMapping
                    public ResponseEntity<Map<String, Object>> getRuntimeConfig() {
                        Map<String, String> effective = new LinkedHashMap<>(GENERATION_DEFAULTS);
                        effective.putAll(overrides);
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("generationDefaults", GENERATION_DEFAULTS);
                        result.put("overrides", overrides);
                        result.put("effective", effective);
                        return ResponseEntity.ok(result);
                    }

                    /** Stores an in-memory override for a config key. */
                    @PutMapping("/{key}")
                    public ResponseEntity<Map<String, String>> setOverride(
                            @PathVariable("key") String key,
                            @RequestBody Map<String, String> body) {
                        String value = body.getOrDefault("value", "");
                        overrides.put(key, value);
                        return ResponseEntity.ok(Map.of("key", key, "value", value, "status", "overridden"));
                    }

                    /** Removes an in-memory override, reverting to the generation-time default. */
                    @DeleteMapping("/{key}")
                    public ResponseEntity<Map<String, String>> removeOverride(
                            @PathVariable("key") String key) {
                        overrides.remove(key);
                        return ResponseEntity.ok(Map.of("key", key, "status", "reset-to-default"));
                    }

                    /** Returns keys whose current effective value differs from the generation default. */
                    @GetMapping("/diff")
                    public ResponseEntity<List<Map<String, String>>> getDiff() {
                        List<Map<String, String>> diffs = new ArrayList<>();
                        for (Map.Entry<String, String> entry : overrides.entrySet()) {
                            String def = GENERATION_DEFAULTS.getOrDefault(entry.getKey(), "(not set)");
                            if (!def.equals(entry.getValue())) {
                                Map<String, String> row = new LinkedHashMap<>();
                                row.put("key",      entry.getKey());
                                row.put("default",  def);
                                row.put("override", entry.getValue());
                                diffs.add(row);
                            }
                        }
                        return ResponseEntity.ok(diffs);
                    }
                }
                """.formatted(
                cfg.registryUrl(), cfg.loggerUrl(), cfg.otelEndpoint(),
                cfg.gatewayPort(), cfg.adminPort(),
                cfg.corsAllowedOrigins(), cfg.oauth2JwksUri());

        Files.writeString(pkg.resolve("RuntimeConfigController.java"), content);
    }

    // -------------------------------------------------------------------------

    private String buildListLiteral(List<String> items) {
        if (items == null || items.isEmpty()) return "List.of()";
        StringBuilder sb = new StringBuilder("List.of(");
        for (int i = 0; i < items.size(); i++) {
            sb.append("\"").append(items.get(i)).append("\"");
            if (i < items.size() - 1) sb.append(", ");
        }
        sb.append(")");
        return sb.toString();
    }

    private String buildEnvVarsLiteral(FractalModule m, FractalxConfig cfg) {
        StringBuilder sb = new StringBuilder("Map.of(");
        sb.append("\"SPRING_PROFILES_ACTIVE\",\"docker\"");
        sb.append(", \"OTEL_EXPORTER_OTLP_ENDPOINT\",\"").append(cfg.otelEndpoint()).append("\"");
        sb.append(", \"OTEL_SERVICE_NAME\",\"").append(m.getServiceName()).append("\"");
        sb.append(", \"FRACTALX_REGISTRY_URL\",\"").append(cfg.registryUrl()).append("\"");
        sb.append(", \"FRACTALX_LOGGER_URL\",\"").append(cfg.loggerUrl()).append("\"");

        // Per-dependency host + gRPC port env vars (only first few to avoid Map.of() limit of 10)
        if (m.getDependencies() != null) {
            int depIdx = 0;
            for (String dep : m.getDependencies()) {
                if (depIdx >= 2) break; // Map.of() supports max 10 entries; leave room
                String serviceName = dep.replaceAll("(?i)(Service|Client)$", "");
                serviceName = serviceName.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase() + "-service";
                String envKey = serviceName.replace("-", "_").toUpperCase();
                sb.append(", \"").append(envKey).append("_HOST\",\"").append(serviceName).append("\"");
                depIdx++;
            }
        }
        sb.append(")");
        return sb.toString();
    }
}
