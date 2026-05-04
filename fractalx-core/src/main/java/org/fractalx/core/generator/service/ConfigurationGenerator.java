package org.fractalx.core.generator.service;

import org.fractalx.core.generator.GenerationContext;
import org.fractalx.core.generator.ServiceFileGenerator;
import org.fractalx.core.model.FractalModule;
import org.fractalx.core.util.SpringBootVersionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Generates application.yml for a microservice.
 */
public class ConfigurationGenerator implements ServiceFileGenerator {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationGenerator.class);

    @Override
    public void generate(GenerationContext context) throws IOException {
        FractalModule module = context.getModule();
        log.debug("Generating application YAMLs for {}", module.getServiceName());

        // Read per-service datasource from fractalx-config.yml in the monolith's resources.
        // When present, the generated dev/docker profiles use the specified DB instead of H2.
        Map<String, Object> monolithDs = readDatasourceFromMonolith(
                module.getServiceName(), context.getSourceRoot());

        // Base config (profile-agnostic, references env vars)
        Files.writeString(context.getSrcMainResources().resolve("application.yml"),
                buildBaseYml(module, context.getFractalxConfig()));

        // Dev profile
        Files.writeString(context.getSrcMainResources().resolve("application-dev.yml"),
                buildDevYml(module, context.getAllModules(), context.getFractalxConfig(), monolithDs));

        // Docker profile
        Files.writeString(context.getSrcMainResources().resolve("application-docker.yml"),
                buildDockerYml(module, context.getAllModules(), context.getFractalxConfig(), monolithDs));
    }

    // -------------------------------------------------------------------------
    // Profile-specific YAML builders
    // -------------------------------------------------------------------------

    /** Base application.yml — references env vars, activates dev profile by default. */
    private String buildBaseYml(FractalModule module, org.fractalx.core.config.FractalxConfig cfg) {
        boolean tracingEnabled = cfg.isTracingEnabled(module.getServiceName());
        String otelBlock = tracingEnabled
                ? "    otel:\n      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:%s}\n".formatted(cfg.otelEndpoint())
                : "    otel:\n      enabled: false\n";
        String samplingProbability = tracingEnabled ? "1.0" : "0.0";
        // Spring Boot 4.x: configure OTel exporter via management.otlp.tracing.endpoint instead of
        // a custom OtelConfig bean (which conflicts with Boot 4.x's managed OTel dependency versions).
        boolean isBoot4 = SpringBootVersionUtil.isBoot4Plus(cfg.springBootVersion());
        String otlpEndpointBlock = (tracingEnabled && isBoot4)
                ? "  otlp:\n    tracing:\n      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:"
                    + cfg.otelEndpoint() + "}/v1/traces\n"
                : "";

        return """
                spring:
                  application:
                    name: %s
                  profiles:
                    active: ${SPRING_PROFILES_ACTIVE:dev}
                  autoconfigure:
                    exclude:
                      - org.springframework.cloud.autoconfigure.LifecycleMvcEndpointAutoConfiguration
                      # FractalX services use gRPC/NetScope, not Feign. Excluding prevents startup failure
                      # when spring-cloud-context is not on the classpath (e.g. Spring Boot 4.x).
                      - org.springframework.cloud.openfeign.FeignAutoConfiguration

                server:
                  port: %d

                fractalx:
                  enabled: true
                  registry:
                    url: ${FRACTALX_REGISTRY_URL:%s}
                    enabled: true
                    host: ${FRACTALX_REGISTRY_HOST:localhost}
                  saga:
                    orchestrator:
                      url: ${FRACTALX_SAGA_ORCHESTRATOR_URL:http://localhost:%d}
                  observability:
                    tracing: %s
                    metrics: true
                    logger-url: ${FRACTALX_LOGGER_URL:%s/api/logs}
                %s
                netscope:
                  server:
                    grpc:
                      port: %d
                    security:
                      enabled: false

                management:
                  endpoints:
                    web:
                      exposure:
                        include: health,info,metrics,prometheus,refresh,mappings,circuitbreakers
                  endpoint:
                    health:
                      show-details: always
                      show-components: always
                  health:
                    circuitbreakers:
                      enabled: true
                  tracing:
                    sampling:
                      probability: %s
                %s
                logging:
                  level:
                    org.fractalx: DEBUG
                    org.fractalx.netscope: DEBUG
                """.formatted(module.getServiceName(), module.getPort(),
                cfg.registryUrl(), cfg.sagaPort(), tracingEnabled,
                cfg.loggerUrl().replaceAll("/api/logs$", ""),
                otelBlock, module.grpcPort(), samplingProbability, otlpEndpointBlock);
    }

    /** application-dev.yml — localhost, H2 by default or PostgreSQL from fractalx-config.yml. */
    private String buildDevYml(FractalModule module, List<FractalModule> allModules,
                                org.fractalx.core.config.FractalxConfig cfg,
                                Map<String, Object> monolithDs) {
        boolean flyway = effectiveFlyway(cfg, module.getServiceName());

        if (monolithDs != null) {
            // Use the datasource specified in the monolith's fractalx-config.yml
            String url      = (String) monolithDs.getOrDefault("url", "jdbc:h2:mem:" + module.getServiceName().replace("-", "_"));
            String driver   = monolithDs.containsKey("driver-class-name")
                    ? (String) monolithDs.get("driver-class-name")
                    : deriveDriver(url);
            String username = monolithDs.containsKey("username") ? String.valueOf(monolithDs.get("username")) : "postgres";
            String password = monolithDs.containsKey("password") && monolithDs.get("password") != null
                    ? String.valueOf(monolithDs.get("password")) : "";
            log.info("  [Config] Using monolith datasource for {} dev profile: {}", module.getServiceName(), url);
            // When Flyway manages the schema ddl-auto must be validate; without Flyway use update
            // so Hibernate keeps the schema in sync without requiring migration scripts.
            String ddlAuto     = flyway ? "validate" : "update";
            String flywayBlock = flyway
                    ? "  flyway:\n    enabled: true\n    locations: classpath:db/migration\n"
                    : "";
            return """
                    # Dev profile — datasource from fractalx-config.yml
                    spring:
                      datasource:
                        url: %s
                        driver-class-name: %s
                        username: %s
                        password: %s
                        hikari:
                          maximum-pool-size: 10
                          minimum-idle: 5
                          connection-timeout: 30000
                          idle-timeout: 600000
                      jpa:
                        hibernate:
                          ddl-auto: %s
                        show-sql: false
                        properties:
                          hibernate:
                            format_sql: false
                    %s%s
                    """.formatted(url, driver, username, password, ddlAuto,
                    flywayBlock, buildClientServersConfig(module, allModules));
        }

        // Default: H2 in-memory
        String flywayBlock = flyway
                ? "  flyway:\n    enabled: true\n    locations: classpath:db/migration\n"
                : "";
        return """
                # Dev profile — all services run on localhost
                spring:
                  datasource:
                    url: jdbc:h2:mem:%s
                    driver-class-name: org.h2.Driver
                    username: sa
                    password:
                    hikari:
                      maximum-pool-size: 10
                      minimum-idle: 5
                      connection-timeout: 30000
                      idle-timeout: 600000
                  jpa:
                    hibernate:
                      ddl-auto: update
                    show-sql: false
                    properties:
                      hibernate:
                        format_sql: false
                  h2:
                    console:
                      enabled: true
                %s%s
                """.formatted(
                module.getServiceName().replace("-", "_"),
                flywayBlock, buildClientServersConfig(module, allModules)
        );
    }

    /** application-docker.yml — all values driven by env vars, no hardcoded localhost. */
    private String buildDockerYml(FractalModule module, List<FractalModule> allModules,
                                   org.fractalx.core.config.FractalxConfig cfg,
                                   Map<String, Object> monolithDs) {
        StringBuilder sb = new StringBuilder("# Docker profile — all values from environment variables\n");
        sb.append("spring:\n");
        sb.append("  datasource:\n");
        if (monolithDs != null) {
            String devUrl   = (String) monolithDs.getOrDefault("url", "");
            String driver   = monolithDs.containsKey("driver-class-name")
                    ? (String) monolithDs.get("driver-class-name")
                    : deriveDriver(devUrl);
            String username = monolithDs.containsKey("username") ? String.valueOf(monolithDs.get("username")) : "postgres";
            String password = monolithDs.containsKey("password") ? String.valueOf(monolithDs.get("password")) : "";
            // Convert localhost URL to docker-compose service name (postgres) as the fallback default
            String dockerUrl = toDockerUrl(devUrl, module.getServiceName());
            sb.append("    url: ${DB_URL:").append(dockerUrl).append("}\n");
            sb.append("    driver-class-name: ").append(driver).append("\n");
            sb.append("    username: ${DB_USERNAME:").append(username).append("}\n");
            sb.append("    password: ${DB_PASSWORD:").append(password).append("}\n");
        } else {
            sb.append("    url: ${DB_URL:jdbc:h2:mem:").append(module.getServiceName().replace("-", "_")).append("}\n");
            sb.append("    username: ${DB_USERNAME:sa}\n");
            sb.append("    password: ${DB_PASSWORD:}\n");
        }
        if (effectiveFlyway(cfg, module.getServiceName())) {
            sb.append("  flyway:\n");
            sb.append("    enabled: true\n");
            sb.append("    locations: classpath:db/migration\n");
        }
        sb.append("fractalx:\n");
        sb.append("  observability:\n");
        sb.append("    otel:\n");
        sb.append("      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://jaeger:4317}\n");
        sb.append("    logger-url: ${FRACTALX_LOGGER_URL:http://logger-service:9099/api/logs}\n");

        List<String> deps = module.getDependencies();
        if (!deps.isEmpty()) {
            sb.append("netscope:\n  client:\n    servers:\n");
            for (String beanType : deps) {
                String targetServiceName = NetScopeClientGenerator.beanTypeToServiceName(beanType);
                FractalModule target = NetScopeClientGenerator.findModule(targetServiceName, allModules);
                if (target != null) {
                    String envPfx = targetServiceName.toUpperCase().replace("-", "_");
                    sb.append("      ").append(targetServiceName).append(":\n");
                    sb.append("        host: ${").append(envPfx).append("_HOST:")
                      .append(targetServiceName).append("}\n");
                    sb.append("        port: ${").append(envPfx).append("_GRPC_PORT:")
                      .append(target.grpcPort()).append("}\n");
                } else {
                    log.warn("ConfigurationGenerator: module not found for dependency '{}' "
                            + "(derived service name '{}') — omitted from application-docker.yml "
                            + "netscope.client.servers block. Check that the module name matches "
                            + "the bean type after normalization.",
                            beanType, targetServiceName);
                }
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Monolith datasource reading
    // -------------------------------------------------------------------------

    /**
     * Reads the {@code datasource} block for the given service from
     * {@code fractalx-config.yml} in the monolith's {@code src/main/resources} directory.
     * Returns {@code null} if no entry is found so callers can fall back to H2 defaults.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readDatasourceFromMonolith(String serviceName, Path sourceRoot) {
        Path resourcesDir = sourceRoot.getParent().resolve("resources");
        Path fractalxConfig = resourcesDir.resolve("fractalx-config.yml");
        if (!Files.exists(fractalxConfig)) return null;
        try (FileInputStream fis = new FileInputStream(fractalxConfig.toFile())) {
            Map<String, Object> root = new Yaml().load(fis);
            if (root == null) return null;
            Map<String, Object> fractalx = (Map<String, Object>) root.get("fractalx");
            if (fractalx == null) return null;
            Map<String, Object> services = (Map<String, Object>) fractalx.get("services");
            if (services == null || !services.containsKey(serviceName)) return null;
            Map<String, Object> svc = (Map<String, Object>) services.get(serviceName);
            if (svc == null) return null;
            return (Map<String, Object>) svc.get("datasource");
        } catch (Exception e) {
            log.debug("Could not read fractalx-config.yml datasource for {}: {}", serviceName, e.getMessage());
            return null;
        }
    }

    /**
     * Derives the JDBC driver class from the URL scheme when not explicitly configured.
     */
    private static String deriveDriver(String url) {
        if (url == null)                       return "org.h2.Driver";
        if (url.contains(":postgresql:"))      return "org.postgresql.Driver";
        if (url.contains(":mysql:"))           return "com.mysql.cj.jdbc.Driver";
        if (url.contains(":mariadb:"))         return "org.mariadb.jdbc.Driver";
        if (url.contains(":sqlserver:"))       return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
        return "org.h2.Driver";
    }

    /**
     * Converts a dev datasource URL (with {@code localhost}) to its Docker equivalent by
     * replacing {@code localhost} with the conventional docker-compose service name for the
     * JDBC scheme (mysql → {@code mysql}, postgresql → {@code postgres}, mariadb → {@code
     * mariadb}, sqlserver → {@code mssql}). The user is expected to provide a matching
     * service entry in {@code docker-compose.yml} (or override via {@code DB_URL} env var).
     * Falls back to an H2 URL if the input is blank.
     */
    private static String toDockerUrl(String devUrl, String serviceName) {
        if (devUrl == null || devUrl.isBlank()) {
            return "jdbc:h2:mem:" + serviceName.replace("-", "_");
        }
        String dockerHost = dockerHostForJdbcUrl(devUrl);
        return devUrl.replace("//localhost:", "//" + dockerHost + ":");
    }

    /**
     * Returns the conventional docker-compose service name for the given JDBC URL's scheme.
     * Defaults to {@code postgres} when the scheme is unrecognized — historic behavior.
     */
    private static String dockerHostForJdbcUrl(String url) {
        if (url == null)                         return "postgres";
        if (url.contains(":mysql:"))             return "mysql";
        if (url.contains(":mariadb:"))           return "mariadb";
        if (url.contains(":postgresql:"))        return "postgres";
        if (url.contains(":sqlserver:"))         return "mssql";
        return "postgres";
    }

    /** Builds the legacy dev localhost {@code netscope.client.servers} block. */
    private String buildYml(FractalModule module, List<FractalModule> allModules) {
        // kept for backward-compat — not called by generate() anymore
        return buildBaseYml(module, org.fractalx.core.config.FractalxConfig.defaults());
    }

    /**
     * Resolves whether Flyway should be enabled for the given service.
     * A per-service {@code flyway.enabled} override takes precedence; when absent
     * the project-level {@code features.distributed-data} flag is used as the default.
     */
    private static boolean effectiveFlyway(org.fractalx.core.config.FractalxConfig cfg, String serviceName) {
        org.fractalx.core.config.FractalxConfig.ServiceOverride override =
                cfg.serviceOverrides().get(serviceName);
        return override != null
                ? override.effectiveFlyway(cfg.features().distributedData())
                : cfg.features().distributedData();
    }

    /**
     * Builds the {@code netscope.client.servers} YAML block for modules that
     * have cross-module dependencies. Returns an empty string if there are none.
     */
    private String buildClientServersConfig(FractalModule module, List<FractalModule> allModules) {
        List<String> deps = module.getDependencies();
        if (deps.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("netscope:\n  client:\n    servers:\n");
        for (String beanType : deps) {
            String targetServiceName = NetScopeClientGenerator.beanTypeToServiceName(beanType);
            FractalModule target = NetScopeClientGenerator.findModule(targetServiceName, allModules);
            if (target != null) {
                sb.append("      ").append(targetServiceName).append(":\n");
                sb.append("        host: localhost\n");
                sb.append("        port: ").append(target.grpcPort()).append("\n");
            } else {
                log.warn("ConfigurationGenerator: module not found for dependency '{}' "
                        + "(derived service name '{}') — omitted from application-dev.yml "
                        + "netscope.client.servers block. Check that the module name matches "
                        + "the bean type after normalization.",
                        beanType, targetServiceName);
            }
        }
        return sb.toString();
    }

}
