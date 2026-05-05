package org.fractalx.core.generator.admin;

import org.fractalx.core.FractalxVersion;
import org.fractalx.core.model.FractalModule;
import org.fractalx.core.model.SagaDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates the Decomposition Stats subsystem for the admin service.
 *
 * <p>Produces 1 class in {@code org.fractalx.admin.stats}:
 * <ul>
 *   <li>{@code DecompositionStatsController} — baked-in stats from the decomposition run</li>
 * </ul>
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /api/decomposition/stats    — full decomposition summary</li>
 *   <li>GET /api/decomposition/services — per-service breakdown</li>
 * </ul>
 */
class AdminDecompositionStatsGenerator {

    private static final Logger log = LoggerFactory.getLogger(AdminDecompositionStatsGenerator.class);

    void generate(Path srcMainJava, String basePackage,
                  List<FractalModule> modules, List<SagaDefinition> sagas) throws IOException {
        Path pkg = AdminPackageUtil.createPackagePath(srcMainJava, basePackage + ".stats");
        generateController(pkg, modules, sagas);
        log.debug("Generated decomposition stats subsystem");
    }

    // -------------------------------------------------------------------------

    private void generateController(Path pkg, List<FractalModule> modules,
                                    List<SagaDefinition> sagas) throws IOException {

        int totalDeps        = modules.stream().mapToInt(m -> m.getDependencies().size()).sum();
        int servicesWithDeps = (int) modules.stream().filter(m -> !m.getDependencies().isEmpty()).count();
        int servicesWithDb   = (int) modules.stream().filter(m -> !m.getOwnedSchemas().isEmpty()).count();
        int totalSchemas     = modules.stream().mapToInt(m -> m.getOwnedSchemas().size()).sum();
        int totalSagas       = sagas.size();
        int totalSagaSteps   = sagas.stream().mapToInt(s -> s.getSteps().size()).sum();
        boolean hasSagaOrch  = !sagas.isEmpty();

        // Per-service JSON entries (baked in at generation time)
        StringBuilder serviceEntries = new StringBuilder();
        for (FractalModule m : modules) {
            String deps    = toJsonArray(m.getDependencies());
            String schemas = toJsonArray(m.getOwnedSchemas());
            serviceEntries.append("""
                            Map.of(
                                "name",           "%s",
                                "className",      "%s",
                                "packageName",    "%s",
                                "port",           %d,
                                "grpcPort",       %d,
                                "dependencies",   List.of(%s),
                                "ownedSchemas",   List.of(%s),
                                "hasDependencies", %b,
                                "hasDb",           %b,
                                "independentDeployment", %b
                            ),
                    """.formatted(
                    m.getServiceName(), m.getClassName(), m.getPackageName(),
                    m.getPort(), m.grpcPort(),
                    deps, schemas,
                    !m.getDependencies().isEmpty(),
                    !m.getOwnedSchemas().isEmpty(),
                    m.isIndependentDeployment()));
        }

        // Per-saga JSON entries
        StringBuilder sagaEntries = new StringBuilder();
        for (SagaDefinition s : sagas) {
            sagaEntries.append("""
                            Map.of(
                                "sagaId",    "%s",
                                "service",   "%s",
                                "method",    "%s",
                                "steps",     %d,
                                "timeoutMs", %dL
                            ),
                    """.formatted(
                    s.getSagaId(), s.getOwnerServiceName(),
                    s.getMethodName(), s.getSteps().size(), s.getTimeoutMs()));
        }

        // Strip trailing comma from last entry so List.of(...) is valid Java
        String svcBlock  = trimTrailingComma(serviceEntries.toString());
        String sagaBlock = trimTrailingComma(sagaEntries.toString());

        Files.writeString(pkg.resolve("DecompositionStatsController.java"), """
                package org.fractalx.admin.stats;

                import org.springframework.web.bind.annotation.*;

                import java.time.Instant;
                import java.util.*;

                /**
                 * Exposes decomposition statistics baked in at generation time by FractalX.
                 * All data is static — reflects the state at the moment {@code mvn fractalx:decompose} ran.
                 */
                @RestController
                @RequestMapping("/api/decomposition")
                public class DecompositionStatsController {

                    private static final String FRACTALX_VERSION = "%s";
                    private static final String GENERATED_AT     = Instant.now().toString();

                    private static final List<Map<String, Object>> SERVICES = List.of(
                %s    );

                    private static final List<Map<String, Object>> SAGAS = List.of(
                %s    );

                    @GetMapping("/stats")
                    public Map<String, Object> stats() {
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("fractalxVersion",    FRACTALX_VERSION);
                        out.put("generatedAt",        GENERATED_AT);
                        out.put("totalServices",      %d);
                        out.put("totalDependencies",  %d);
                        out.put("servicesWithDeps",   %d);
                        out.put("servicesWithDb",     %d);
                        out.put("totalSchemas",       %d);
                        out.put("totalSagas",         %d);
                        out.put("totalSagaSteps",     %d);
                        out.put("hasSagaOrchestrator",%b);
                        // Infrastructure services always added
                        out.put("infraServices", List.of("fractalx-registry", "fractalx-gateway", "admin-service", "logger-service"));
                        return out;
                    }

                    @GetMapping("/services")
                    public List<Map<String, Object>> services() {
                        return SERVICES;
                    }

                    @GetMapping("/sagas")
                    public List<Map<String, Object>> sagas() {
                        return SAGAS;
                    }
                }
                """.formatted(
                FractalxVersion.release(),
                svcBlock,
                sagaBlock,
                modules.size(),
                totalDeps,
                servicesWithDeps,
                servicesWithDb,
                totalSchemas,
                totalSagas,
                totalSagaSteps,
                hasSagaOrch
        ));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Removes the trailing {@code ,\n} (or {@code ,}) from the last List.of entry. */
    private static String trimTrailingComma(String s) {
        if (s == null || s.isBlank()) return "";
        String trimmed = s.stripTrailing();
        if (trimmed.endsWith(",")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed + "\n";
    }

    private static String toJsonArray(List<String> items) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append("\"").append(item).append("\"");
        }
        return sb.toString();
    }
}
