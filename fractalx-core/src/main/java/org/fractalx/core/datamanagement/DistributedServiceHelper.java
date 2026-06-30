package org.fractalx.core.datamanagement;

import org.fractalx.core.model.FractalModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Central orchestrator for applying distributed systems capabilities to each
 * generated microservice.
 *
 * <p>Coordinates in order:
 * <ol>
 *   <li>Data isolation ({@link DataIsolationGenerator})</li>
 *   <li>Database configuration ({@link DbConfigurationGenerator})</li>
 *   <li>Driver provisioning ({@link DependencyManager})</li>
 *   <li>Flyway migration scaffold ({@link FlywayMigrationGenerator})</li>
 *   <li>Transactional outbox ({@link OutboxGenerator})</li>
 *   <li>Reference validator ({@link ReferenceValidatorGenerator})</li>
 *   <li>Data README ({@link DataReadmeGenerator})</li>
 * </ol>
 */
public class DistributedServiceHelper {

    private static final Logger log = LoggerFactory.getLogger(DistributedServiceHelper.class);

    private final DataIsolationGenerator    isolationGen;
    private final DependencyManager         dependencyManager;
    private final DbConfigurationGenerator  dbConfigGen;
    private final FlywayMigrationGenerator  flywayGen;
    private final OutboxGenerator           outboxGen;
    private final ReferenceValidatorGenerator referenceValidatorGen;
    private final DataReadmeGenerator       dataReadmeGen;

    public DistributedServiceHelper() {
        this.isolationGen           = new DataIsolationGenerator();
        this.dependencyManager      = new DependencyManager();
        this.dbConfigGen            = new DbConfigurationGenerator();
        this.flywayGen              = new FlywayMigrationGenerator();
        this.outboxGen              = new OutboxGenerator();
        this.referenceValidatorGen  = new ReferenceValidatorGenerator();
        this.dataReadmeGen          = new DataReadmeGenerator();
    }

    /**
     * Upgrades a generated service by applying all distributed systems features.
     *
     * @param module          the module being upgraded
     * @param sourceRoot      the monolith source root (for reading original DB config)
     * @param serviceRoot     the generated service root directory
     * @param basePackage     the generated base package (e.g. "com.acme.generated.orderservice")
     */
    public void upgradeService(FractalModule module, Path sourceRoot, Path serviceRoot,
                               String basePackage,
                               String springBootVersion) throws IOException {
        Path srcMainJava      = serviceRoot.resolve("src/main/java");
        Path srcMainResources = serviceRoot.resolve("src/main/resources");

        log.info("⚡ [Distributed] Applying distributed features to {}...", module.getServiceName());

        // 1. Enforce data isolation (dual-package @EntityScan + @EnableJpaRepositories)
        if (hasJpaContent(module)) {
            isolationGen.generateIsolationConfig(module, srcMainJava, basePackage, springBootVersion);
        } else {
            log.info("   ⏭ No JPA entities in {} — skipping IsolationConfig", module.getServiceName());
        }

        // 2. Detect & apply database configuration (fractalx-config.yml → application.yml)
        String driverClass = dbConfigGen.generateDbConfig(module, sourceRoot, srcMainResources);
        // Patch docker profile to use ddl-auto=validate (production-safe)
        dbConfigGen.applyDockerProductionOverride(srcMainResources);

        // 3. Provision database driver dependency in pom.xml
        if (driverClass != null) {
            if (driverClass.contains("mysql")) {
                dependencyManager.provisionMySQL(module, serviceRoot);
                log.info("   ✓ Provisioned MySQL driver for {}", module.getServiceName());
            } else if (driverClass.contains("postgresql")) {
                dependencyManager.provisionPostgreSQL(module, serviceRoot);
                log.info("   ✓ Provisioned PostgreSQL driver for {}", module.getServiceName());
            }
        }

        // 4. Generate Flyway V1 migration scaffold
        flywayGen.generateMigration(module, serviceRoot);

        // 5. Generate transactional outbox.
        //
        // Cross-module JPA service — has explicit dependencies and persistent state
        // that needs the dual-write guarantee.
        boolean needsOutbox = hasJpaContent(module) && !module.getDependencies().isEmpty();
        if (needsOutbox) {
            outboxGen.generateOutbox(module, serviceRoot, basePackage);
        } else if (!module.getDependencies().isEmpty()) {
            log.info("   ⏭ No JPA entities in {} — skipping Outbox", module.getServiceName());
        }

        // 6. Generate reference validators for decoupled foreign keys
        referenceValidatorGen.generateReferenceValidator(module, serviceRoot, basePackage);

        // 7. Generate DATA_README.md
        dataReadmeGen.generateServiceDataReadme(module, serviceRoot, driverClass);

        // 8. Provision any implied dependencies detected in the fully-generated source
        //    (e.g. Lombok, jakarta.validation copied with model classes from other modules)
        dependencyManager.provisionImpliedDependencies(module, serviceRoot);

        log.info("   ✓ [Distributed] Upgrade complete for {}", module.getServiceName());
    }

    private boolean hasJpaContent(FractalModule module) {
        Set<String> imports = module.getDetectedImports();
        if (imports == null || imports.isEmpty()) return false;
        return imports.stream().anyMatch(i ->
                i.startsWith("jakarta.persistence") ||
                i.startsWith("javax.persistence") ||
                i.startsWith("org.springframework.data.jpa") ||
                i.startsWith("org.springframework.data.repository"));
    }
}
