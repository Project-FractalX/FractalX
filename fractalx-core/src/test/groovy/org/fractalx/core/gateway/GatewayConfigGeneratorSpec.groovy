package org.fractalx.core.gateway

import org.fractalx.core.auth.AuthPattern
import org.fractalx.core.config.FractalxConfig
import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class GatewayConfigGeneratorSpec extends Specification {

    @TempDir Path resourcesDir

    FractalxConfig cfg = FractalxConfig.defaults()

    GatewayConfigGenerator gen = new GatewayConfigGenerator()

    private FractalModule mod(String name, int port) {
        FractalModule.builder()
            .serviceName(name)
            .packageName("com.example")
            .port(port)
            .build()
    }

    def "generateConfig creates application.yml"() {
        given:
        def modules = [mod("order-service", 8081)]

        when:
        gen.generateConfig(resourcesDir, modules, [])

        then:
        Files.exists(resourcesDir.resolve("application.yml"))
    }

    def "application.yml contains gateway port"() {
        given:
        def modules = [mod("order-service", 8081)]

        when:
        gen.generateConfig(resourcesDir, modules, [])

        then:
        def content = Files.readString(resourcesDir.resolve("application.yml"))
        content.contains("9999") || content.contains("port")
    }

    def "application.yml contains service name reference"() {
        given:
        def modules = [mod("order-service", 8081)]

        when:
        gen.generateConfig(resourcesDir, modules, [], cfg)

        then:
        def content = Files.readString(resourcesDir.resolve("application.yml"))
        content.contains("order-service") || content.contains("8081")
    }

    def "handles empty module list without error"() {
        when:
        gen.generateConfig(resourcesDir, [], [])

        then:
        noExceptionThrown()
        Files.exists(resourcesDir.resolve("application.yml"))
    }

    def "full generateConfig overload with securityProfile and authPattern"() {
        given:
        def modules = [mod("order-service", 8081)]
        def security = SecurityProfile.none()
        def auth = AuthPattern.none()

        when:
        gen.generateConfig(resourcesDir, modules, [], cfg, security, auth)

        then:
        noExceptionThrown()
        Files.exists(resourcesDir.resolve("application.yml"))
    }

    def "constructor with monolithSrc path"() {
        when:
        def g = new GatewayConfigGenerator(resourcesDir)
        g.generateConfig(resourcesDir, [mod("svc", 8080)], [])

        then:
        noExceptionThrown()
    }

    def "includes multiple module routes"() {
        given:
        def modules = [mod("order-service", 8081), mod("payment-service", 8082)]

        when:
        gen.generateConfig(resourcesDir, modules, [], cfg)

        then:
        def content = Files.readString(resourcesDir.resolve("application.yml"))
        content.contains("8081") || content.contains("order")
        content.contains("8082") || content.contains("payment")
    }
}
