package org.fractalx.core.config;

import org.fractalx.core.naming.NamingConventions;

import java.util.Map;

/**
 * Immutable snapshot of all FractalX platform-level configuration values read
 * from the pre-decomposed application's {@code fractalx-config.yml} (and,
 * optionally, its {@code application.yml}).
 *
 * <p>Every field carries a sensible default so generators remain functional
 * even when no {@code fractalx-config.yml} is present. When a value is
 * explicitly set in the config file it overrides the default.
 *
 * <p>Expected {@code fractalx-config.yml} structure:
 * <pre>
 * fractalx:
 *   base-package: com.acme.generated       # defaults to monolith groupId + ".generated"
 *   java-version: 21                       # defaults to version read from source pom.xml, then 21
 *   spring-boot-version: 3.3.2             # defaults to version read from source pom.xml
 *   spring-cloud-version: 2023.0.1
 *   registry:
 *     url: http://registry-host:8761
 *     port: 8761
 *   logger:
 *     url: http://logger-host:9099
 *     port: 9099
 *   initial-service-version: 1.0.0-SNAPSHOT  # version for generated service pom.xml artifacts
 *   saga:
 *     port: 8099
 *   otel:
 *     endpoint: http://jaeger-host:4317
 *     jaeger-ui-port: 16686
 *     jaeger-otlp-port: 4317
 *   gateway:
 *     port: 9999
 *     cors:
 *       allowed-origins: "http://myapp.com,http://localhost:3000"
 *     security:
 *       oauth2:
 *         jwk-set-uri: http://auth-host:8080/realms/fractalx/protocol/openid-connect/certs
 *   admin:
 *     port: 9090
 *   resilience:
 *     failure-rate-threshold: 50
 *     wait-duration-in-open-state: 30s
 *     permitted-calls-in-half-open-state: 5
 *     sliding-window-size: 10
 *     retry-max-attempts: 3
 *     retry-wait-duration: 100ms
 *     timeout-duration: 2s
 *   docker:
 *     maven-build-image: maven:3.9-eclipse-temurin-21  # defaults derived from java-version
 *     jre-runtime-image: eclipse-temurin:21-jre-jammy  # defaults derived from java-version
 *     jaeger-image: jaegertracing/all-in-one:1.53
 *   services:
 *     order-service:
 *       port: 8081
 *       datasource:
 *         url: jdbc:mysql://db-host:3306/order_db
 *         username: root
 *         password: secret
 *     payment-service:
 *       tracing:
 *         enabled: false   # disables OTel + Jaeger for this service only
 * </pre>
 */
public record FractalxConfig(
        String registryUrl,
        String loggerUrl,
        String otelEndpoint,
        int    gatewayPort,
        String corsAllowedOrigins,
        String oauth2JwksUri,
        int    adminPort,
        Map<String, ServiceOverride> serviceOverrides,

        // ── Generalisation fields ─────────────────────────────────────────────
        String basePackage,          // null → derived from source pom groupId at read time
        String javaVersion,          // defaults to version read from source pom.xml, then "21"
        String initialServiceVersion, // version for generated service pom.xml artifacts
        String springBootVersion,
        String springCloudVersion,
        int    registryPort,
        int    loggerPort,
        int    sagaPort,
        int    jaegerUiPort,
        int    jaegerOtlpPort,
        ResilienceDefaults resilience,
        DockerImages dockerImages,
        FeatureFlags features,
        NamingConventions naming
) {

    // ── Nested records ────────────────────────────────────────────────────────

    /** Per-service overrides read from fractalx-config.yml. */
    public record ServiceOverride(int port, boolean tracingEnabled,
                                  String datasourceUrl, String datasourceUsername,
                                  String datasourcePassword, String datasourceDriver) {
        public boolean hasPort() { return port > 0; }
        public boolean hasDatasource() { return datasourceUrl != null && !datasourceUrl.isBlank(); }
        public boolean isH2() { return datasourceUrl != null && datasourceUrl.startsWith("jdbc:h2"); }
    }

    /** Resilience4j circuit-breaker / retry / timeout defaults for generated services. */
    public record ResilienceDefaults(
            int    failureRateThreshold,
            String waitDurationInOpenState,
            int    permittedCallsInHalfOpenState,
            int    slidingWindowSize,
            int    retryMaxAttempts,
            String retryWaitDuration,
            String timeoutDuration
    ) {
        public static ResilienceDefaults defaults() {
            return new ResilienceDefaults(50, "30s", 5, 10, 3, "100ms", "2s");
        }
    }

    /**
     * Controls which FractalX features are generated. All flags default to {@code true} so that
     * existing projects with no {@code fractalx.features} section in their config are unaffected.
     *
     * <pre>
     * fractalx:
     *   features:
     *     gateway: true          # fractalx-gateway service
     *     admin: true            # admin-service dashboard
     *     registry: true         # fractalx-registry service discovery
     *     logger: true           # logger-service centralised logging
     *     saga: true             # fractalx-saga-orchestrator + saga code injection
     *     docker: true           # docker-compose.yml + per-service Dockerfiles
     *     observability: true    # OTel tracing, health metrics, structured logging
     *     resilience: true       # Resilience4j circuit-breaker / retry per service
     *     distributed-data: true # Flyway migrations, transactional outbox, DB isolation
     * </pre>
     */
    public record FeatureFlags(
            boolean gateway,
            boolean admin,
            boolean registry,
            boolean logger,
            boolean saga,
            boolean docker,
            boolean observability,
            boolean resilience,
            boolean distributedData
    ) {
        public static FeatureFlags defaults() {
            return new FeatureFlags(true, true, true, true, true, true, true, true, true);
        }
    }

    /** Docker image tags used when generating Dockerfiles and docker-compose.yml. */
    public record DockerImages(
            String mavenBuildImage,
            String jreRuntimeImage,
            String jaegerImage
    ) {
        public static DockerImages defaults() {
            return defaults("21");
        }

        public static DockerImages defaults(String javaVersion) {
            return new DockerImages(
                    "maven:3.9-eclipse-temurin-" + javaVersion,
                    "eclipse-temurin:" + javaVersion + "-jre-jammy",
                    "jaegertracing/all-in-one:1.53"
            );
        }
    }

    // ── Defaults ─────────────────────────────────────────────────────────────

    public static FractalxConfig defaults() {
        return new FractalxConfig(
                "http://localhost:8761",
                "http://localhost:9099",
                "http://localhost:4317",
                9999,
                "http://localhost:3000,http://localhost:4200",
                "http://localhost:8080/realms/fractalx/protocol/openid-connect/certs",
                9090,
                Map.of(),
                null,
                "21",
                "1.0.0-SNAPSHOT",
                "3.2.0",
                "2023.0.0",
                8761,
                9099,
                8099,
                16686,
                4317,
                ResilienceDefaults.defaults(),
                DockerImages.defaults(),
                FeatureFlags.defaults(),
                NamingConventions.defaults()
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the base Java package for generated infrastructure classes.
     * Falls back to {@code "generated"} when no explicit value is configured
     * and the source pom.xml could not be read.
     */
    public String effectiveBasePackage() {
        return (basePackage != null && !basePackage.isBlank()) ? basePackage : "generated";
    }

    /** Returns a copy of this config with the given base package. Convenient for tests. */
    public FractalxConfig withBasePackage(String basePackage) {
        return new FractalxConfig(registryUrl, loggerUrl, otelEndpoint, gatewayPort,
                corsAllowedOrigins, oauth2JwksUri, adminPort, serviceOverrides,
                basePackage, javaVersion, initialServiceVersion, springBootVersion, springCloudVersion,
                registryPort, loggerPort, sagaPort, jaegerUiPort, jaegerOtlpPort,
                resilience, dockerImages, features, naming);
    }

    /** Returns the configured port for a service, or {@code defaultPort} if not set. */
    public int portFor(String serviceName, int defaultPort) {
        ServiceOverride ov = serviceOverrides.get(serviceName);
        return (ov != null && ov.hasPort()) ? ov.port() : defaultPort;
    }

    /**
     * Returns {@code false} only when the service explicitly sets
     * {@code tracing.enabled: false} in its fractalx-config.yml services block.
     * Defaults to {@code true} for all services.
     */
    public boolean isTracingEnabled(String serviceName) {
        ServiceOverride ov = serviceOverrides.get(serviceName);
        return ov == null || ov.tracingEnabled();
    }
}
