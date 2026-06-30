package org.fractalx.core.gateway

import org.fractalx.core.config.FractalxConfig
import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class GatewayPomGeneratorSpec extends Specification {

    @TempDir Path gatewayRoot

    GatewayPomGenerator gen = new GatewayPomGenerator()
    FractalxConfig cfg = FractalxConfig.defaults()

    private FractalModule mod(String name, int port) {
        FractalModule.builder()
            .serviceName(name)
            .packageName("com.example")
            .port(port)
            .build()
    }

    def "generates pom.xml in the gateway root directory"() {
        when:
        gen.generatePom(gatewayRoot, [mod("order-service", 8081)], cfg)

        then:
        Files.exists(gatewayRoot.resolve("pom.xml"))
    }

    def "pom.xml contains Spring Cloud Gateway dependency"() {
        when:
        gen.generatePom(gatewayRoot, [mod("order-service", 8081)], cfg)

        then:
        def pom = Files.readString(gatewayRoot.resolve("pom.xml"))
        pom.contains("spring-cloud-starter-gateway")
    }

    def "pom.xml contains artifactId fractalx-api-gateway"() {
        when:
        gen.generatePom(gatewayRoot, [mod("order-service", 8081)], cfg)

        then:
        def pom = Files.readString(gatewayRoot.resolve("pom.xml"))
        pom.contains("fractalx-api-gateway")
    }

    def "pom.xml contains the java version from config"() {
        when:
        gen.generatePom(gatewayRoot, [mod("order-service", 8081)], cfg)

        then:
        def pom = Files.readString(gatewayRoot.resolve("pom.xml"))
        pom.contains("17") || pom.contains("21") // java.version property
    }

    def "pom.xml is valid XML (parseable)"() {
        given:
        gen.generatePom(gatewayRoot, [mod("order-service", 8081)], cfg)
        def pomContent = Files.readString(gatewayRoot.resolve("pom.xml"))

        when:
        def factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        def builder = factory.newDocumentBuilder()
        builder.parse(new org.xml.sax.InputSource(new java.io.StringReader(pomContent)))

        then:
        noExceptionThrown()
    }

    def "pom.xml with multiple modules does not throw"() {
        given:
        def mods = [
            mod("order-service",   8081),
            mod("payment-service", 8082),
            mod("inventory-service", 8083)
        ]

        when:
        gen.generatePom(gatewayRoot, mods, cfg)

        then:
        noExceptionThrown()
        Files.exists(gatewayRoot.resolve("pom.xml"))
    }

    def "pom.xml with empty modules list does not throw"() {
        when:
        gen.generatePom(gatewayRoot, [], cfg)

        then:
        noExceptionThrown()
    }
}
