package org.fractalx.core.generator.admin;

import org.fractalx.core.config.FractalxConfig;
import org.fractalx.core.model.FractalModule;
import org.fractalx.core.model.SagaDefinition;
import org.fractalx.core.model.SagaStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the Data Consistency section for the admin service:
 * <ul>
 *   <li>{@code SagaMetaRegistry} — baked-in saga definitions</li>
 *   <li>{@code DataConsistencyController} — REST API for sagas, databases, outbox</li>
 * </ul>
 */
class AdminDataConsistencyGenerator {

    private static final Logger log = LoggerFactory.getLogger(AdminDataConsistencyGenerator.class);

    void generate(Path srcMainJava, String basePackage, List<FractalModule> modules) throws IOException {
        generate(srcMainJava, basePackage, modules, List.of(), FractalxConfig.defaults(), null);
    }

    void generate(Path srcMainJava, String basePackage, List<FractalModule> modules,
                  List<SagaDefinition> sagaDefinitions) throws IOException {
        generate(srcMainJava, basePackage, modules, sagaDefinitions, FractalxConfig.defaults(), null);
    }

    void generate(Path srcMainJava, String basePackage, List<FractalModule> modules,
                  List<SagaDefinition> sagaDefinitions, FractalxConfig fractalxConfig) throws IOException {
        generate(srcMainJava, basePackage, modules, sagaDefinitions, fractalxConfig, null);
    }

    void generate(Path srcMainJava, String basePackage, List<FractalModule> modules,
                  List<SagaDefinition> sagaDefinitions, FractalxConfig fractalxConfig,
                  Path outputRoot) throws IOException {
        Path pkg = AdminPackageUtil.createPackagePath(srcMainJava, basePackage + ".data");

        generateSagaMetaRegistry(pkg, modules, sagaDefinitions);
        generateDataConsistencyController(pkg, modules, fractalxConfig, outputRoot);

        log.debug("Generated admin data consistency components");
    }

    // -------------------------------------------------------------------------

    private void generateSagaMetaRegistry(Path pkg, List<FractalModule> modules,
                                           List<SagaDefinition> sagaDefinitions) throws IOException {
        StringBuilder sagaEntries = new StringBuilder();

        if (!sagaDefinitions.isEmpty()) {
            // Use real @DistributedSaga definitions detected at generation time
            for (SagaDefinition saga : sagaDefinitions) {
                StringBuilder steps = new StringBuilder("List.of(");
                List<SagaStep> stepList = saga.getSteps();
                for (int i = 0; i < stepList.size(); i++) {
                    SagaStep s = stepList.get(i);
                    steps.append("\"").append(s.getTargetServiceName())
                         .append(":").append(s.getMethodName()).append("\"");
                    if (i < stepList.size() - 1) steps.append(", ");
                }
                steps.append(")");

                StringBuilder compensations = new StringBuilder("List.of(");
                for (int i = 0; i < stepList.size(); i++) {
                    SagaStep s = stepList.get(i);
                    if (s.hasCompensation()) {
                        compensations.append("\"").append(s.getTargetServiceName())
                                     .append(":").append(s.getCompensationMethodName()).append("\"");
                    } else {
                        compensations.append("\"\"");
                    }
                    if (i < stepList.size() - 1) compensations.append(", ");
                }
                compensations.append(")");

                sagaEntries.append(String.format(
                        "        new SagaInfo(\"%s\", \"%s\", %s, %s, true),\n",
                        saga.getSagaId(), saga.getOwnerServiceName(),
                        steps.toString(), compensations.toString()));
            }
        } else {
            // Fallback: derive approximate saga entries from module dependencies
            for (FractalModule m : modules) {
                if (m.getDependencies() != null && !m.getDependencies().isEmpty()) {
                    String sagaId = toCamelCase(m.getServiceName()) + "Saga";
                    StringBuilder steps = new StringBuilder("List.of(");
                    for (int i = 0; i < m.getDependencies().size(); i++) {
                        steps.append("\"Call ").append(m.getDependencies().get(i)).append("\"");
                        if (i < m.getDependencies().size() - 1) steps.append(", ");
                    }
                    steps.append(")");
                    sagaEntries.append(String.format(
                            "        new SagaInfo(\"%s\", \"%s\", %s, List.of(\"Compensate\"), true),\n",
                            sagaId, m.getServiceName(), steps.toString()));
                }
            }
        }

        String content = """
                package org.fractalx.admin.data;

                import org.springframework.stereotype.Component;

                import java.util.List;
                import java.util.Optional;

                /**
                 * Baked-in registry of saga orchestration definitions derived at generation time.
                 * Reflects modules that have cross-service dependencies and may participate in sagas.
                 */
                @Component
                public class SagaMetaRegistry {

                    public record SagaInfo(
                            String sagaId, String orchestratedBy,
                            List<String> steps, List<String> compensationSteps, boolean enabled) {}

                    private static final List<SagaInfo> SAGAS = List.of(
                %s    );

                    public List<SagaInfo> getAll()                     { return SAGAS; }
                    public int            count()                      { return SAGAS.size(); }

                    public Optional<SagaInfo> findById(String sagaId) {
                        return SAGAS.stream().filter(s -> s.sagaId().equals(sagaId)).findFirst();
                    }

                    public boolean hasSagas() { return !SAGAS.isEmpty(); }
                }
                """.formatted(stripTrailingComma(sagaEntries.toString()));

        Files.writeString(pkg.resolve("SagaMetaRegistry.java"), content);
    }

    private void generateDataConsistencyController(Path pkg, List<FractalModule> modules,
                                                    FractalxConfig fractalxConfig,
                                                    Path outputRoot) throws IOException {

        // Build baked datasource config constants and DB checks
        StringBuilder dsConfigEntries = new StringBuilder();
        StringBuilder dbChecks = new StringBuilder();

        for (FractalModule m : modules) {
            String svcName = m.getServiceName();
            FractalxConfig.ServiceOverride ov = fractalxConfig.serviceOverrides().get(svcName);

            // Read actual values from the generated service's application YAMLs (most accurate)
            Map<String, String> svcYaml = readServiceYamlConfig(outputRoot, svcName);

            // datasource config constants for admin — service YAML takes priority over fractalx-config.yml
            String defaultH2Url = "jdbc:h2:mem:" + svcName.replace("-", "_");
            String dsUrl  = svcYaml.getOrDefault("url",
                    (ov != null && ov.hasDatasource()) ? ov.datasourceUrl() : defaultH2Url);
            String dsUser = svcYaml.getOrDefault("username",
                    (ov != null && ov.hasDatasource()) ? ov.datasourceUsername() : "sa");
            String dsPass = svcYaml.getOrDefault("password",
                    (ov != null && ov.hasDatasource()) ? ov.datasourcePassword() : "");
            String dsDrv  = svcYaml.getOrDefault("driver",
                    (ov != null && ov.hasDatasource())
                            ? (ov.datasourceDriver() != null ? ov.datasourceDriver() : deriveDriver(dsUrl))
                            : "org.h2.Driver");
            int    port   = svcYaml.containsKey("port")
                    ? parseInt(svcYaml.get("port"), m.getPort()) : m.getPort();
            boolean isH2  = dsUrl.startsWith("jdbc:h2");

            dsConfigEntries.append(String.format(
                    "        DS_CONFIG.put(\"%s\", new DsConfig(\"%s\",\"%s\",\"%s\",\"%s\",%s,%d));\n",
                    svcName, dsUrl, dsUser, dsPass, dsDrv, isH2, port));

            String schemas = (m.getOwnedSchemas() != null && !m.getOwnedSchemas().isEmpty())
                    ? String.join(", ", m.getOwnedSchemas()) : "default";
            dbChecks.append(String.format(
                    "        {Map<String,Object> db = new LinkedHashMap<>(); db.put(\"service\",\"%s\"); db.put(\"schemas\",\"%s\"); db.put(\"health\", fetchDbHealth(\"%s\")); dbList.add(db);}\n",
                    svcName, schemas, svcName));
        }

        // Append saga-orchestrator DB — read from generated YAML, fall back to known defaults
        {
            Map<String, String> sagaYaml = readServiceYamlConfig(outputRoot, "fractalx-saga-orchestrator");
            String sagaUrl  = sagaYaml.getOrDefault("url",  "jdbc:h2:mem:saga_db");
            String sagaUser = sagaYaml.getOrDefault("username", "sa");
            String sagaPass = sagaYaml.getOrDefault("password", "");
            String sagaDrv  = sagaYaml.getOrDefault("driver", "org.h2.Driver");
            int    sagaPort = sagaYaml.containsKey("port") ? parseInt(sagaYaml.get("port"), 8099) : 8099;
            boolean sagaH2  = sagaUrl.startsWith("jdbc:h2");
            dsConfigEntries.append(String.format(
                "        DS_CONFIG.put(\"saga-orchestrator\", new DsConfig(\"%s\",\"%s\",\"%s\",\"%s\",%s,%d));\n",
                sagaUrl, sagaUser, sagaPass, sagaDrv, sagaH2, sagaPort));
        }
        dbChecks.append(
            "        {Map<String,Object> db = new LinkedHashMap<>();" +
            " db.put(\"service\",\"saga-orchestrator\");" +
            " db.put(\"schemas\",\"saga_instance\");" +
            " db.put(\"health\", fetchDbHealth(\"fractalx-saga-orchestrator\"));" +
            " dbList.add(db);}\n"
        );

        // Outbox check for services with dependencies (they generated outbox support)
        StringBuilder outboxChecks = new StringBuilder();
        for (FractalModule m : modules) {
            outboxChecks.append(String.format(
                    "        {Map<String,Object> ob = new LinkedHashMap<>(); ob.put(\"service\",\"%s\"); ob.put(\"metrics\", fetchOutboxMetrics(\"%s\")); outboxList.add(ob);}\n",
                    m.getServiceName(), m.getServiceName()));
        }

        int serviceCount = modules.size();

        String content = """
                package org.fractalx.admin.data;

                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.*;
                import org.springframework.web.client.RestTemplate;

                import java.util.*;

                /**
                 * REST API for the Data Consistency section of the admin dashboard.
                 *
                 * <pre>
                 * GET /api/data/overview                     — summary of all data consistency features
                 * GET /api/data/sagas                        — all baked saga definitions
                 * GET /api/data/sagas/instances              — proxy to saga-orchestrator GET /saga
                 * GET /api/data/sagas/{sagaId}/instances     — instances filtered by sagaId
                 * GET /api/data/databases                    — per-service DB health + row counts
                 * GET /api/data/databases/config/{service}   — datasource config details for a service
                 * GET /api/data/schemas                      — per-service owned schemas
                 * GET /api/data/outbox                       — per-service outbox metrics
                 * </pre>
                 */
                @RestController
                @RequestMapping("/api/data")
                @CrossOrigin(origins = "*")
                public class DataConsistencyController {

                    private static final int SAGA_ORCHESTRATOR_PORT = 8099;

                    /** Baked-in datasource configuration per service (from fractalx-config.yml). */
                    public record DsConfig(String url, String username, String password,
                                           String driverClassName, boolean isH2, int port) {}

                    private static final Map<String, DsConfig> DS_CONFIG = new LinkedHashMap<>();
                    static {
                %s    }

                    private final SagaMetaRegistry registry;
                    private final RestTemplate     restTemplate = new RestTemplate();

                    @Value("${fractalx.registry.url:http://localhost:8761}")
                    private String registryUrl;

                    public DataConsistencyController(SagaMetaRegistry registry) {
                        this.registry = registry;
                    }

                    /**
                     * Resolves a service's live base URL ({@code http://host:port}) by
                     * looking up its registration in the FractalX Registry. Returns
                     * {@code null} if the service is not registered or the registry
                     * is unreachable — callers should fall back to a sensible default.
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

                    /** Overview: service count, saga count, schema summary. */
                    @GetMapping("/overview")
                    public ResponseEntity<Map<String, Object>> getOverview() {
                        Map<String, Object> overview = new LinkedHashMap<>();
                        overview.put("totalServices",  %d);
                        overview.put("totalSagas",     registry.count());
                        overview.put("hasSagas",       registry.hasSagas());
                        overview.put("sagaOrchestrator",
                            Map.of("port", SAGA_ORCHESTRATOR_PORT,
                                   "health", fetchSagaOrchestratorHealth()));
                        return ResponseEntity.ok(overview);
                    }

                    /** Returns all baked saga definitions. */
                    @GetMapping("/sagas")
                    public ResponseEntity<List<SagaMetaRegistry.SagaInfo>> getSagas() {
                        return ResponseEntity.ok(registry.getAll());
                    }

                    /** Proxies to saga-orchestrator GET /saga for all saga instances. */
                    @GetMapping("/sagas/instances")
                    public ResponseEntity<Object> getAllSagaInstances() {
                        String base = resolveBaseUrl("fractalx-saga-orchestrator");
                        if (base == null) {
                            return ResponseEntity.ok(Map.of(
                                "error", "Saga orchestrator not registered with FractalX Registry"));
                        }
                        try {
                            Object resp = restTemplate.getForObject(base + "/saga", Object.class);
                            return ResponseEntity.ok(resp);
                        } catch (Exception e) {
                            return ResponseEntity.ok(Map.of(
                                "error", "Saga orchestrator unavailable: " + e.getMessage()));
                        }
                    }

                    /** Proxies to saga-orchestrator filtered by sagaId. */
                    @GetMapping("/sagas/{sagaId}/instances")
                    public ResponseEntity<Object> getSagaInstances(@PathVariable("sagaId") String sagaId) {
                        String base = resolveBaseUrl("fractalx-saga-orchestrator");
                        if (base == null) {
                            return ResponseEntity.ok(Map.of(
                                "error", "Saga orchestrator not registered with FractalX Registry"));
                        }
                        try {
                            Object resp = restTemplate.getForObject(
                                    base + "/saga?sagaId=" + sagaId, Object.class);
                            return ResponseEntity.ok(resp);
                        } catch (Exception e) {
                            return ResponseEntity.ok(Map.of(
                                "error", "Saga orchestrator unavailable: " + e.getMessage()));
                        }
                    }

                    /**
                     * Returns saga instances enriched with per-step status derived from
                     * the baked SagaMetaRegistry definitions.
                     */
                    @GetMapping("/sagas/instances/enriched")
                    @SuppressWarnings("unchecked")
                    public ResponseEntity<Object> getEnrichedInstances() {
                        String base = resolveBaseUrl("fractalx-saga-orchestrator");
                        if (base == null) {
                            return ResponseEntity.ok(Map.of(
                                "error", "Saga orchestrator not registered with FractalX Registry"));
                        }
                        try {
                            List<Map<String, Object>> instances = (List<Map<String, Object>>)
                                    restTemplate.getForObject(base + "/saga", List.class);
                            if (instances == null) return ResponseEntity.ok(new ArrayList<>());

                            List<Map<String, Object>> result = new ArrayList<>();
                            for (Map<String, Object> inst : instances) {
                                Map<String, Object> enriched = new LinkedHashMap<>(inst);
                                String sagaId     = (String) inst.get("sagaId");
                                String currentStep = (String) inst.get("currentStep");
                                String sagaStatus  = String.valueOf(inst.get("status"));
                                registry.findById(sagaId).ifPresent(def -> {
                                    enriched.put("steps", def.steps());
                                    enriched.put("compensationSteps", def.compensationSteps());
                                    List<Map<String, Object>> progress = new ArrayList<>();
                                    boolean[] found = {false};
                                    for (String step : def.steps()) {
                                        Map<String, Object> sp = new LinkedHashMap<>();
                                        sp.put("step", step);
                                        String st;
                                        if ("DONE".equals(sagaStatus)) {
                                            st = "COMPLETED";
                                        } else if (step.equals(currentStep)) {
                                            st = ("FAILED".equals(sagaStatus) || "COMPENSATING".equals(sagaStatus))
                                                    ? "FAILED" : "IN_PROGRESS";
                                            found[0] = true;
                                        } else if (!found[0]) {
                                            st = "COMPLETED";
                                        } else {
                                            st = "PENDING";
                                        }
                                        sp.put("status", st);
                                        progress.add(sp);
                                    }
                                    enriched.put("stepProgress", progress);
                                });
                                result.add(enriched);
                            }
                            return ResponseEntity.ok(result);
                        } catch (Exception e) {
                            return ResponseEntity.ok(Map.of(
                                "error", "Saga orchestrator unavailable: " + e.getMessage()));
                        }
                    }

                    /** Per-service database health + row counts from /api/internal/db-summary. */
                    @GetMapping("/databases")
                    public ResponseEntity<List<Map<String, Object>>> getDatabases() {
                        List<Map<String, Object>> dbList = new ArrayList<>();
                %s                return ResponseEntity.ok(dbList);
                    }

                    /**
                     * Returns baked datasource config for a service (url, username, driver, isH2).
                     * Password is included for H2 (empty) but masked as "***" for external DBs.
                     */
                    @GetMapping("/databases/config/{service}")
                    public ResponseEntity<Map<String, Object>> getDatabaseConfig(
                            @PathVariable("service") String service) {
                        DsConfig cfg = DS_CONFIG.get(service);
                        if (cfg == null) {
                            return ResponseEntity.ok(Map.of("error", "No config found for " + service));
                        }
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("service",         service);
                        result.put("url",             cfg.url());
                        result.put("username",        cfg.username());
                        result.put("password",        cfg.isH2() ? cfg.password() : "***");
                        result.put("driverClassName", cfg.driverClassName());
                        result.put("isH2",            cfg.isH2());
                        result.put("port",            cfg.port());
                        if (cfg.isH2()) {
                            result.put("h2ConsoleUrl",
                                    "http://localhost:" + cfg.port() + "/h2-console");
                        }
                        return ResponseEntity.ok(result);
                    }

                    /** Per-service owned schema info (baked from generation metadata). */
                    @GetMapping("/schemas")
                    public ResponseEntity<List<Map<String, Object>>> getSchemas() {
                        List<Map<String, Object>> result = new ArrayList<>();
                        for (SagaMetaRegistry.SagaInfo saga : registry.getAll()) {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("service", saga.orchestratedBy());
                            result.add(entry);
                        }
                        return ResponseEntity.ok(result);
                    }

                    /** Per-service outbox metrics from /actuator/metrics. */
                    @GetMapping("/outbox")
                    public ResponseEntity<List<Map<String, Object>>> getOutbox() {
                        List<Map<String, Object>> outboxList = new ArrayList<>();
                %s                return ResponseEntity.ok(outboxList);
                    }

                    // -------------------------------------------------------------------------

                    @SuppressWarnings("unchecked")
                    private String fetchDbHealth(String service) {
                        String base = resolveBaseUrl(service);
                        if (base == null) return "UNKNOWN";
                        try {
                            java.util.Map<String, Object> body = restTemplate.getForObject(
                                    base + "/actuator/health/db", java.util.Map.class);
                            return body != null && "UP".equalsIgnoreCase(String.valueOf(body.get("status")))
                                    ? "UP" : "DOWN";
                        } catch (Exception e) {
                            return "UNKNOWN";
                        }
                    }

                    @SuppressWarnings("unchecked")
                    private Map<String, Object> fetchDbSummary(String service) {
                        String base = resolveBaseUrl(service);
                        if (base == null) {
                            return Map.of("unavailable", true, "reason", "service not registered");
                        }
                        try {
                            Map<String, Object> resp = restTemplate.getForObject(
                                    base + "/api/internal/db-summary", Map.class);
                            // restTemplate.getForObject can return null for empty responses — normalise to empty summary
                            if (resp == null) {
                                return new LinkedHashMap<>(Map.of("totalRows", 0, "entityCounts", Map.of()));
                            }
                            // Ensure totalRows key is always present so the UI can display the count
                            if (!resp.containsKey("totalRows")) {
                                Map<String, Object> normalised = new LinkedHashMap<>(resp);
                                normalised.put("totalRows", 0);
                                return normalised;
                            }
                            return resp;
                        } catch (Exception e) {
                            // Service unavailable — return sentinel so JS shows "—" rather than 0
                            return Map.of("unavailable", true, "reason", e.getMessage());
                        }
                    }

                    private Object fetchOutboxMetrics(String service) {
                        String base = resolveBaseUrl(service);
                        if (base == null) {
                            return Map.of("error", "service not registered");
                        }
                        try {
                            return restTemplate.getForObject(
                                    base + "/actuator/metrics", Object.class);
                        } catch (Exception e) {
                            return Map.of("error", e.getMessage());
                        }
                    }

                    @SuppressWarnings("unchecked")
                    private String fetchSagaOrchestratorHealth() {
                        String base = resolveBaseUrl("fractalx-saga-orchestrator");
                        if (base == null) return "DOWN";
                        try {
                            java.util.Map<String, Object> body = restTemplate.getForObject(
                                    base + "/actuator/health", java.util.Map.class);
                            return body != null && "UP".equalsIgnoreCase(String.valueOf(body.get("status")))
                                    ? "UP" : "DOWN";
                        } catch (Exception e) {
                            return "DOWN";
                        }
                    }

                    @SuppressWarnings("unchecked")
                    private int fetchSagaInstanceCount() {
                        String base = resolveBaseUrl("fractalx-saga-orchestrator");
                        if (base == null) return -1;
                        try {
                            List<?> resp = (List<?>) restTemplate.getForObject(
                                    base + "/saga", List.class);
                            return resp != null ? resp.size() : 0;
                        } catch (Exception e) {
                            return -1;
                        }
                    }
                }
                """.formatted(dsConfigEntries.toString(), serviceCount, dbChecks.toString(), outboxChecks.toString());

        Files.writeString(pkg.resolve("DataConsistencyController.java"), content);
    }

    // -------------------------------------------------------------------------

    private String deriveDriver(String url) {
        if (url == null) return "org.h2.Driver";
        if (url.startsWith("jdbc:mysql"))    return "com.mysql.cj.jdbc.Driver";
        if (url.startsWith("jdbc:postgresql")) return "org.postgresql.Driver";
        if (url.startsWith("jdbc:mariadb"))  return "org.mariadb.jdbc.Driver";
        if (url.startsWith("jdbc:oracle"))   return "oracle.jdbc.OracleDriver";
        if (url.startsWith("jdbc:sqlserver")) return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
        return "org.h2.Driver";
    }

    private int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }

    /**
     * Reads datasource URL, driver, username, password, and server port from a generated
     * service's {@code application.yml} and {@code application-dev.yml} files.
     * Returns an empty map (all keys absent) if the files cannot be read — callers fall back
     * to {@link FractalxConfig} values in that case.
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> readServiceYamlConfig(Path outputRoot, String serviceName) {
        Map<String, String> result = new HashMap<>();
        if (outputRoot == null) return result;
        Yaml yaml = new Yaml();

        // Base application.yml: server.port and (for some services) spring.datasource.*
        Path baseYml = outputRoot.resolve(serviceName).resolve("src/main/resources/application.yml");
        if (Files.exists(baseYml)) {
            try (InputStream is = Files.newInputStream(baseYml)) {
                Map<String, Object> data = yaml.load(is);
                if (data != null) {
                    if (data.get("server") instanceof Map<?, ?> serverMap) {
                        Object p = serverMap.get("port");
                        if (p != null) result.put("port", String.valueOf(p));
                    }
                    // Some services (e.g. saga-orchestrator) put datasource in the base YAML
                    extractDatasource(data, result);
                }
            } catch (Exception ignored) {}
        }

        // Dev profile YAML overrides datasource config for regular services
        // (application-dev.yml takes priority over application.yml for datasource values)
        Path devYml = outputRoot.resolve(serviceName).resolve("src/main/resources/application-dev.yml");
        if (Files.exists(devYml)) {
            try (InputStream is = Files.newInputStream(devYml)) {
                Map<String, Object> data = yaml.load(is);
                if (data != null) {
                    extractDatasource(data, result);  // dev values win
                }
            } catch (Exception ignored) {}
        }
        return result;
    }

    /** Extracts spring.datasource.{url,driver-class-name,username,password} into result map. */
    private void extractDatasource(Map<String, Object> data, Map<String, String> result) {
        if (!(data.get("spring") instanceof Map<?, ?> springMap)) return;
        if (!(springMap.get("datasource") instanceof Map<?, ?> dsMap)) return;
        if (dsMap.get("url") != null) result.put("url", String.valueOf(dsMap.get("url")));
        if (dsMap.get("driver-class-name") != null) result.put("driver", String.valueOf(dsMap.get("driver-class-name")));
        if (dsMap.get("username") != null) result.put("username", String.valueOf(dsMap.get("username")));
        Object pwd = dsMap.get("password");
        result.put("password", (pwd != null && !"null".equals(String.valueOf(pwd))) ? String.valueOf(pwd) : "");
    }

    /** Removes a trailing {@code ,\n} so List.of() method calls don't end with a stray comma. */
    private String stripTrailingComma(String s) {
        return s.endsWith(",\n") ? s.substring(0, s.length() - 2) + "\n" : s;
    }

    private String toCamelCase(String kebabCase) {
        String[] parts = kebabCase.split("-");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(Character.toUpperCase(parts[i].charAt(0)))
                  .append(parts[i].substring(1));
            }
        }
        return sb.toString();
    }
}
