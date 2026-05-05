package org.fractalx.core.generator.service

import org.fractalx.core.config.FractalxConfig
import org.fractalx.core.generator.GenerationContext
import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies that ConfigurationGenerator produces three profile-specific YAML files:
 * application.yml (base), application-dev.yml (dev), and application-docker.yml (docker).
 */
class ConfigurationGeneratorSpec extends Specification {

    @TempDir
    Path serviceRoot

    ConfigurationGenerator generator = new ConfigurationGenerator()

    FractalModule moduleWithDeps = FractalModule.builder()
        .serviceName("order-service")
        .packageName("com.example.order")
        .port(8081)
        .dependencies(["PaymentService"])
        .build()

    FractalModule payment = FractalModule.builder()
        .serviceName("payment-service")
        .packageName("com.example.payment")
        .port(8082)
        .build()

    FractalModule moduleNoDeps = FractalModule.builder()
        .serviceName("simple-service")
        .packageName("com.example.simple")
        .port(8083)
        .build()

    private GenerationContext ctx(FractalModule m, List<FractalModule> all = [m]) {
        def resourcesDir = serviceRoot.resolve("src/main/resources")
        Files.createDirectories(resourcesDir)
        new GenerationContext(m, serviceRoot, serviceRoot, all, FractalxConfig.defaults(), [])
    }

    /** Builds a context with a custom flyway setup: project-level flag + optional per-service override. */
    private GenerationContext ctxWithFlyway(FractalModule m, boolean projectFlyway,
                                             Map<String, Boolean> svcFlyway = [:]) {
        def resourcesDir = serviceRoot.resolve("src/main/resources")
        Files.createDirectories(resourcesDir)

        def features = new FractalxConfig.FeatureFlags(true, true, true, true, true, true, true, true, projectFlyway)
        Map<String, FractalxConfig.ServiceOverride> overrides = svcFlyway.collectEntries { name, flag ->
            [name, new FractalxConfig.ServiceOverride(0, true, null, null, null, null, flag)]
        }
        def d = FractalxConfig.defaults()
        def cfg = new FractalxConfig(
                d.registryUrl(), d.loggerUrl(), d.otelEndpoint(),
                d.gatewayPort(), d.corsAllowedOrigins(), d.oauth2JwksUri(),
                d.adminPort(), overrides, d.basePackage(), d.javaVersion(),
                d.initialServiceVersion(), d.springBootVersion(), d.springCloudVersion(),
                d.registryPort(), d.loggerPort(), d.sagaPort(),
                d.jaegerUiPort(), d.jaegerOtlpPort(),
                d.resilience(), d.dockerImages(), features, d.naming()
        )
        new GenerationContext(m, serviceRoot, serviceRoot, [m], cfg, [])
    }

    private String base()   { Files.readString(serviceRoot.resolve("src/main/resources/application.yml")) }
    private String dev()    { Files.readString(serviceRoot.resolve("src/main/resources/application-dev.yml")) }
    private String docker() { Files.readString(serviceRoot.resolve("src/main/resources/application-docker.yml")) }

    def "application.yml base config is created"() {
        when:
        generator.generate(ctx(moduleNoDeps))

        then:
        Files.exists(serviceRoot.resolve("src/main/resources/application.yml"))
    }

    def "application-dev.yml is created"() {
        when:
        generator.generate(ctx(moduleNoDeps))

        then:
        Files.exists(serviceRoot.resolve("src/main/resources/application-dev.yml"))
    }

    def "application-docker.yml is created"() {
        when:
        generator.generate(ctx(moduleNoDeps))

        then:
        Files.exists(serviceRoot.resolve("src/main/resources/application-docker.yml"))
    }

    def "base YAML contains spring application name and server port"() {
        when:
        generator.generate(ctx(moduleNoDeps))

        then:
        def c = base()
        c.contains("name: simple-service")
        c.contains("port: 8083")
    }

    def "base YAML references SPRING_PROFILES_ACTIVE env var with dev default"() {
        when:
        generator.generate(ctx(moduleNoDeps))

        then:
        base().contains('${SPRING_PROFILES_ACTIVE:dev}')
    }

    def "base YAML includes fractalx.registry block with URL and enabled flag"() {
        when:
        generator.generate(ctx(moduleNoDeps))

        then:
        def c = base()
        c.contains("fractalx:")
        c.contains("registry:")
        c.contains("FRACTALX_REGISTRY_URL")
        c.contains("http://localhost:8761")
        c.contains("enabled: true")
    }

    def "base YAML includes netscope gRPC port as HTTP port + 10000"() {
        when:
        generator.generate(ctx(moduleNoDeps))

        then:
        base().contains("18083")
    }

    def "dev YAML contains H2 in-memory datasource"() {
        when:
        generator.generate(ctx(moduleNoDeps))

        then:
        def c = dev()
        c.contains("jdbc:h2:mem:")
        c.contains("org.h2.Driver")
        c.contains("flyway:")
    }

    def "dev YAML contains netscope client.servers block for each dependency on localhost"() {
        when:
        generator.generate(ctx(moduleWithDeps, [moduleWithDeps, payment]))

        then:
        def c = dev()
        c.contains("payment-service:")
        c.contains("host: localhost")
        c.contains("18082")
    }

    def "dev YAML has no netscope client block for service with no dependencies"() {
        when:
        generator.generate(ctx(moduleNoDeps))

        then:
        !dev().contains("netscope:")
    }

    def "docker YAML uses DB_URL env var for datasource"() {
        when:
        generator.generate(ctx(moduleNoDeps))

        then:
        docker().contains("\${DB_URL:")
    }

    def "docker YAML uses env vars for netscope client host and port"() {
        when:
        generator.generate(ctx(moduleWithDeps, [moduleWithDeps, payment]))

        then:
        def c = docker()
        c.contains("PAYMENT_SERVICE_HOST")
        c.contains("PAYMENT_SERVICE_GRPC_PORT")
    }

    def "docker YAML has no hardcoded localhost for services with dependencies"() {
        when:
        generator.generate(ctx(moduleWithDeps, [moduleWithDeps, payment]))

        then:
        // Docker profile must not have static localhost for netscope servers
        def netScopeSection = docker().contains("netscope:") ?
            docker().substring(docker().indexOf("netscope:")) : ""
        !netScopeSection.contains("host: localhost")
    }

    def "base YAML includes OTEL endpoint under fractalx.observability.otel"() {
        when:
        generator.generate(ctx(moduleNoDeps))

        then:
        def c = base()
        c.contains("otel:")
        c.contains("OTEL_EXPORTER_OTLP_ENDPOINT")
        c.contains("http://localhost:4317")
    }

    def "base YAML includes fractalx.observability.logger-url"() {
        when:
        generator.generate(ctx(moduleNoDeps))

        then:
        base().contains("logger-url:")
    }

    def "base YAML exposes prometheus endpoint via management.endpoints.web.exposure"() {
        when:
        generator.generate(ctx(moduleNoDeps))

        then:
        def c = base()
        c.contains("prometheus")
        c.contains("include:")
    }

    def "base YAML includes management.endpoint.health with show-details: always"() {
        when:
        generator.generate(ctx(moduleNoDeps))

        then:
        def c = base()
        c.contains("show-details: always")
    }

    def "base YAML includes management.tracing.sampling.probability"() {
        when:
        generator.generate(ctx(moduleNoDeps))

        then:
        def c = base()
        c.contains("sampling:")
        c.contains("probability: 1.0")
    }

    def "docker YAML includes OTEL endpoint pointing to jaeger container"() {
        when:
        generator.generate(ctx(moduleNoDeps))

        then:
        def c = docker()
        c.contains("OTEL_EXPORTER_OTLP_ENDPOINT")
        c.contains("jaeger:4317")
    }

    def "docker YAML includes logger-url pointing to logger-service container"() {
        when:
        generator.generate(ctx(moduleNoDeps))

        then:
        docker().contains("logger-service")
    }

    // ── Flyway enable/disable tests ───────────────────────────────────────────

    def "dev YAML contains flyway block when distributed-data is enabled (project default)"() {
        when:
        generator.generate(ctx(moduleNoDeps))

        then:
        def c = dev()
        c.contains("flyway:")
        c.contains("enabled: true")
        c.contains("classpath:db/migration")
    }

    def "dev YAML omits flyway block when distributed-data is false"() {
        when:
        generator.generate(ctxWithFlyway(moduleNoDeps, false))

        then:
        !dev().contains("flyway:")
    }

    def "dev YAML uses ddl-auto: update when distributed-data is false"() {
        when:
        generator.generate(ctxWithFlyway(moduleNoDeps, false))

        then:
        dev().contains("ddl-auto: update")
    }

    def "docker YAML contains flyway block when distributed-data is enabled"() {
        when:
        generator.generate(ctx(moduleNoDeps))

        then:
        def c = docker()
        c.contains("flyway:")
        c.contains("enabled: true")
    }

    def "docker YAML omits flyway block when distributed-data is false"() {
        when:
        generator.generate(ctxWithFlyway(moduleNoDeps, false))

        then:
        !docker().contains("flyway:")
    }

    def "per-service flyway.enabled: false overrides project-level true"() {
        when:
        generator.generate(ctxWithFlyway(moduleNoDeps, true, ["simple-service": false]))

        then:
        !dev().contains("flyway:")
        !docker().contains("flyway:")
    }

    def "per-service flyway.enabled: true overrides project-level false"() {
        when:
        generator.generate(ctxWithFlyway(moduleNoDeps, false, ["simple-service": true]))

        then:
        def d = dev()
        d.contains("flyway:")
        d.contains("enabled: true")
    }

    def "service with no per-service override inherits project default"() {
        when:
        // project=false, override map is empty → should inherit false
        generator.generate(ctxWithFlyway(moduleNoDeps, false, [:]))

        then:
        !dev().contains("flyway:")
    }
}
