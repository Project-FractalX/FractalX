package org.fractalx.core.gateway

import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies that GatewayCircuitBreakerGenerator creates GatewayFallbackController
 * with per-service fallback endpoints and GatewayResilienceConfig with
 * per-module ReactiveCircuitBreaker beans.
 */
class GatewayCircuitBreakerGeneratorSpec extends Specification {

    @TempDir
    Path srcMainJava

    GatewayCircuitBreakerGenerator generator = new GatewayCircuitBreakerGenerator()

    FractalModule order = FractalModule.builder()
        .serviceName("order-service")
        .packageName("com.example.order")
        .port(8081)
        .build()

    FractalModule payment = FractalModule.builder()
        .serviceName("payment-service")
        .packageName("com.example.payment")
        .port(8082)
        .build()

    private Path resiliencePkg() {
        srcMainJava.resolve("org/fractalx/gateway/resilience")
    }

    private String fallbackController() {
        Files.readString(resiliencePkg().resolve("GatewayFallbackController.java"))
    }

    private String resilienceConfig() {
        Files.readString(resiliencePkg().resolve("GatewayResilienceConfig.java"))
    }

    def "GatewayFallbackController.java is created in the resilience package"() {
        when:
        generator.generate(srcMainJava, [order])

        then:
        Files.exists(resiliencePkg().resolve("GatewayFallbackController.java"))
    }

    def "GatewayResilienceConfig.java is created in the resilience package"() {
        when:
        generator.generate(srcMainJava, [order])

        then:
        Files.exists(resiliencePkg().resolve("GatewayResilienceConfig.java"))
    }

    def "GatewayFallbackController is a @RestController in the correct package"() {
        when:
        generator.generate(srcMainJava, [order])

        then:
        def c = fallbackController()
        c.startsWith("package org.fractalx.gateway.resilience;")
        c.contains("@RestController")
    }

    def "fallback controller exposes a GET and POST endpoint for each module"() {
        when:
        generator.generate(srcMainJava, [order, payment])

        then:
        def c = fallbackController()
        c.contains("@RequestMapping(\"/fallback\")")
        c.contains("/order-service")
        c.contains("/payment-service")
        c.contains("@GetMapping")
        c.contains("@PostMapping")
    }

    def "fallback endpoints return HTTP 503 SERVICE_UNAVAILABLE"() {
        when:
        generator.generate(srcMainJava, [order])

        then:
        fallbackController().contains("SERVICE_UNAVAILABLE")
    }

    def "fallback response includes service name in the body"() {
        when:
        generator.generate(srcMainJava, [order])

        then:
        fallbackController().contains("order-service")
    }

    def "GatewayResilienceConfig is a @Configuration in the correct package"() {
        when:
        generator.generate(srcMainJava, [order])

        then:
        def c = resilienceConfig()
        c.startsWith("package org.fractalx.gateway.resilience;")
        c.contains("@Configuration")
    }

    def "GatewayResilienceConfig defines a @Bean ReactiveCircuitBreaker for each module"() {
        when:
        generator.generate(srcMainJava, [order, payment])

        then:
        def c = resilienceConfig()
        c.contains("ReactiveCircuitBreaker")
        c.contains("@Bean")
        c.contains("order-service")
        c.contains("payment-service")
    }
}
