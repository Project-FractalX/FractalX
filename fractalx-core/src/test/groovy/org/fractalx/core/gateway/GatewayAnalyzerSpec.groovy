package org.fractalx.core.gateway

import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class GatewayAnalyzerSpec extends Specification {

    @TempDir Path outputDir

    GatewayAnalyzer analyzer = new GatewayAnalyzer()

    private FractalModule mod(String name, int port) {
        FractalModule.builder()
            .serviceName(name)
            .packageName("com.example")
            .port(port)
            .build()
    }

    def "analyzeServiceRoutes returns routes for a module"() {
        given:
        def module = mod("order-service", 8081)

        when:
        def routes = analyzer.analyzeServiceRoutes(outputDir, module)

        then:
        !routes.isEmpty()
    }

    def "routes have the module service name"() {
        given:
        def module = mod("payment-service", 8082)

        when:
        def routes = analyzer.analyzeServiceRoutes(outputDir, module)

        then:
        routes.every { it.getServiceName() == "payment-service" }
    }

    def "routes include health endpoint"() {
        given:
        def module = mod("order-service", 8081)

        when:
        def routes = analyzer.analyzeServiceRoutes(outputDir, module)

        then:
        routes.any { it.getPath().contains("health") }
    }

    def "routes have correct service port"() {
        given:
        def module = mod("order-service", 8081)

        when:
        def routes = analyzer.analyzeServiceRoutes(outputDir, module)

        then:
        routes.every { it.getServicePort() == 8081 }
    }

    def "generates routes for different HTTP methods"() {
        given:
        def module = mod("order-service", 8081)

        when:
        def routes = analyzer.analyzeServiceRoutes(outputDir, module)
        def methods = routes.collect { it.getMethod() }.toSet()

        then:
        methods.contains("GET")
        methods.contains("POST")
    }
}
