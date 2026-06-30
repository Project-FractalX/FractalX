package org.fractalx.core.generator.service

import org.fractalx.core.config.FractalxConfig
import org.fractalx.core.generator.GenerationContext
import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies that NetScopeRegistryBridgeStep generates a NetScopeRegistryBridge
 * component only for services that have cross-module gRPC dependencies.
 * The bridge must query fractalx-registry at startup and override
 * netscope.client.servers.* via ConfigurableEnvironment.
 */
class NetScopeRegistryBridgeStepSpec extends Specification {

    @TempDir
    Path serviceRoot

    NetScopeRegistryBridgeStep step = new NetScopeRegistryBridgeStep()

    FractalModule moduleWithDep = FractalModule.builder()
        .serviceName("order-service")
        .packageName("com.example.order")
        .port(8081)
        .dependencies(["PaymentService"])
        .build()

    FractalModule moduleNoDep = FractalModule.builder()
        .serviceName("payment-service")
        .packageName("com.example.payment")
        .port(8082)
        .build()

    private GenerationContext ctx(FractalModule m) {
        new GenerationContext(m, serviceRoot, serviceRoot, [m], FractalxConfig.defaults().withBasePackage("org.fractalx.generated"))
    }

    private Path bridgeFile(String servicePkg) {
        serviceRoot.resolve("src/main/java/org/fractalx/generated/${servicePkg}/NetScopeRegistryBridge.java")
    }

    private String content() { Files.readString(bridgeFile("orderservice")) }

    def "NetScopeRegistryBridge.java is created for a service with dependencies"() {
        when:
        step.generate(ctx(moduleWithDep))

        then:
        Files.exists(bridgeFile("orderservice"))
    }

    def "NetScopeRegistryBridge.java is NOT created for a service without dependencies"() {
        when:
        step.generate(ctx(moduleNoDep))

        then:
        !Files.exists(bridgeFile("paymentservice"))
    }

    def "bridge is in the correct generated package"() {
        when:
        step.generate(ctx(moduleWithDep))

        then:
        content().startsWith("package org.fractalx.generated.orderservice;")
    }

    def "bridge is a @Component conditional on fractalx.registry.enabled"() {
        when:
        step.generate(ctx(moduleWithDep))

        then:
        def c = content()
        c.contains("@Component")
        c.contains("@ConditionalOnProperty")
        c.contains("fractalx.registry.enabled")
    }

    def "bridge resolves peers in a @PostConstruct method"() {
        when:
        step.generate(ctx(moduleWithDep))

        then:
        def c = content()
        c.contains("@PostConstruct")
        c.contains("resolvePeers()")
    }

    def "bridge includes the resolved target service name in the peer resolution call"() {
        when:
        step.generate(ctx(moduleWithDep))

        then:
        content().contains("payment-service")
    }

    def "bridge reads registry URL from fractalx.registry.url property"() {
        when:
        step.generate(ctx(moduleWithDep))

        then:
        content().contains("fractalx.registry.url")
    }

    def "bridge updates netscope client host and port via ConfigurableEnvironment"() {
        when:
        step.generate(ctx(moduleWithDep))

        then:
        def c = content()
        c.contains("ConfigurableEnvironment")
        c.contains("netscope.client.servers.")
        c.contains(".host")
        c.contains(".port")
    }

    def "bridge retries resolution up to 5 times with backoff before falling back"() {
        when:
        step.generate(ctx(moduleWithDep))

        then:
        def c = content()
        c.contains("5")
        c.contains("attempt")
        c.contains("Thread.sleep")
    }

    def "bridge uses MapPropertySource to override resolved properties"() {
        when:
        step.generate(ctx(moduleWithDep))

        then:
        content().contains("MapPropertySource")
    }
}
