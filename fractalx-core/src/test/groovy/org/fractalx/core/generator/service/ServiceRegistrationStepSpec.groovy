package org.fractalx.core.generator.service

import org.fractalx.core.config.FractalxConfig
import org.fractalx.core.generator.GenerationContext
import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies that ServiceRegistrationStep generates FractalRegistryClient and
 * ServiceRegistrationAutoConfig in each service's generated package, correctly
 * wiring the service name, HTTP port, and computed gRPC port.
 */
class ServiceRegistrationStepSpec extends Specification {

    @TempDir
    Path serviceRoot

    ServiceRegistrationStep step = new ServiceRegistrationStep()

    FractalModule module = FractalModule.builder()
        .serviceName("order-service")
        .packageName("com.example.order")
        .port(8081)
        .build()

    private GenerationContext ctx(FractalModule m, List<FractalModule> all = [m]) {
        new GenerationContext(m, serviceRoot, serviceRoot, all, FractalxConfig.defaults().withBasePackage("org.fractalx.generated"))
    }

    private Path generatedPkg() {
        serviceRoot.resolve("src/main/java/org/fractalx/generated/orderservice")
    }

    private String client()     { Files.readString(generatedPkg().resolve("FractalRegistryClient.java")) }
    private String autoConfig() { Files.readString(generatedPkg().resolve("ServiceRegistrationAutoConfig.java")) }

    def "FractalRegistryClient.java is created under the generated package"() {
        when:
        step.generate(ctx(module))

        then:
        Files.exists(generatedPkg().resolve("FractalRegistryClient.java"))
    }

    def "ServiceRegistrationAutoConfig.java is created under the generated package"() {
        when:
        step.generate(ctx(module))

        then:
        Files.exists(generatedPkg().resolve("ServiceRegistrationAutoConfig.java"))
    }

    def "FractalRegistryClient is in the correct package"() {
        when:
        step.generate(ctx(module))

        then:
        client().startsWith("package org.fractalx.generated.orderservice;")
    }

    def "FractalRegistryClient is a @Component that reads registry URL from properties"() {
        when:
        step.generate(ctx(module))

        then:
        def c = client()
        c.contains("@Component")
        c.contains("@Value")
        c.contains("fractalx.registry.url")
    }

    def "FractalRegistryClient exposes register, deregister, and heartbeat methods"() {
        when:
        step.generate(ctx(module))

        then:
        def c = client()
        c.contains("public void register(")
        c.contains("public void deregister(")
        c.contains("public boolean heartbeat(")
    }

    def "ServiceRegistrationAutoConfig is @ConditionalOnProperty fractalx.registry.enabled"() {
        when:
        step.generate(ctx(module))

        then:
        autoConfig().contains("@ConditionalOnProperty")
        autoConfig().contains("fractalx.registry.enabled")
    }

    def "ServiceRegistrationAutoConfig registers on startup via @PostConstruct"() {
        when:
        step.generate(ctx(module))

        then:
        autoConfig().contains("@PostConstruct")
        autoConfig().contains("onStartup()")
    }

    def "ServiceRegistrationAutoConfig deregisters on shutdown via @PreDestroy"() {
        when:
        step.generate(ctx(module))

        then:
        autoConfig().contains("@PreDestroy")
        autoConfig().contains("onShutdown()")
    }

    def "ServiceRegistrationAutoConfig sends heartbeat via @Scheduled every 5s with re-register on failure"() {
        when:
        step.generate(ctx(module))

        then:
        autoConfig().contains("@Scheduled")
        autoConfig().contains("5_000")
        autoConfig().contains("sendHeartbeat()")
        autoConfig().contains("onStartup()")
    }

    def "ServiceRegistrationAutoConfig bakes in the correct default HTTP port"() {
        when:
        step.generate(ctx(module))

        then:
        autoConfig().contains("8081")
    }

    def "ServiceRegistrationAutoConfig bakes in gRPC port as HTTP port + 10000"() {
        when:
        step.generate(ctx(module))

        then:
        autoConfig().contains("18081")
    }

    def "step skips nothing for a service with no dependencies"() {
        given:
        def noDepModule = FractalModule.builder()
            .serviceName("simple-service")
            .packageName("com.example.simple")
            .port(8083)
            .build()

        when:
        step.generate(ctx(noDepModule))

        then:
        Files.exists(serviceRoot.resolve(
            "src/main/java/org/fractalx/generated/simpleservice/FractalRegistryClient.java"))
    }

    def "hyphenated service names produce a valid Java package identifier"() {
        given:
        def hyphenated = FractalModule.builder()
            .serviceName("my-complex-service")
            .packageName("com.example.complex")
            .port(8090)
            .build()

        when:
        step.generate(ctx(hyphenated))

        then:
        Files.exists(serviceRoot.resolve(
            "src/main/java/org/fractalx/generated/mycomplexservice/FractalRegistryClient.java"))
    }
}
