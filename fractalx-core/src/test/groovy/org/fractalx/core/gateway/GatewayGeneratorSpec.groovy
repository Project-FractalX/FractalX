package org.fractalx.core.gateway

import org.fractalx.core.auth.AuthPattern
import org.fractalx.core.config.FractalxConfig
import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class GatewayGeneratorSpec extends Specification {

    @TempDir Path sourceRoot
    @TempDir Path outputDir

    FractalxConfig cfg = FractalxConfig.defaults()

    GatewayGenerator gen

    def setup() {
        gen = new GatewayGenerator(sourceRoot, outputDir, cfg)
    }

    private FractalModule mod(String name, int port) {
        FractalModule.builder()
            .serviceName(name)
            .packageName("com.example")
            .port(port)
            .build()
    }

    def "generateGateway creates fractalx-gateway directory"() {
        given:
        def modules = [mod("order-service", 8081)]

        when:
        gen.generateGateway(modules, AuthPattern.none())

        then:
        Files.isDirectory(outputDir.resolve("fractalx-gateway"))
    }

    def "generateGateway creates pom.xml"() {
        given:
        def modules = [mod("order-service", 8081)]

        when:
        gen.generateGateway(modules, AuthPattern.none())

        then:
        Files.exists(outputDir.resolve("fractalx-gateway/pom.xml"))
    }

    def "generateGateway creates application.yml"() {
        given:
        def modules = [mod("order-service", 8081)]

        when:
        gen.generateGateway(modules, AuthPattern.none())

        then:
        def yml = outputDir.resolve("fractalx-gateway/src/main/resources/application.yml")
        Files.exists(yml)
    }

    def "generateGateway works with empty module list"() {
        when:
        gen.generateGateway([], AuthPattern.none())

        then:
        noExceptionThrown()
        Files.isDirectory(outputDir.resolve("fractalx-gateway"))
    }

    def "generateGateway with explicit AuthPattern.none() does not throw"() {
        given:
        def modules = [mod("order-service", 8081)]

        when:
        gen.generateGateway(modules, AuthPattern.none())

        then:
        noExceptionThrown()
    }

    def "generateGateway produces src/main/java directory"() {
        given:
        def modules = [mod("order-service", 8081)]

        when:
        gen.generateGateway(modules, AuthPattern.none())

        then:
        Files.isDirectory(outputDir.resolve("fractalx-gateway/src/main/java"))
    }

    def "default constructor uses defaults config"() {
        when:
        def g = new GatewayGenerator(sourceRoot, outputDir)
        g.generateGateway([mod("svc", 8080)], AuthPattern.none())

        then:
        noExceptionThrown()
    }
}
