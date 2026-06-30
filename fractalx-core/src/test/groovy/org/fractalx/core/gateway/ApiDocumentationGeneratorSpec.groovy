package org.fractalx.core.gateway

import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class ApiDocumentationGeneratorSpec extends Specification {

    @TempDir Path tempDir

    ApiDocumentationGenerator gen = new ApiDocumentationGenerator()

    private FractalModule mod(String name, int port) {
        FractalModule.builder()
            .serviceName(name)
            .packageName("com.example")
            .port(port)
            .build()
    }

    def "generateDocumentation creates docs/API_CATALOG.md"() {
        given:
        def modules = [mod("order-service", 8081)]

        when:
        gen.generateDocumentation(tempDir, modules, [])

        then:
        Files.exists(tempDir.resolve("docs/API_CATALOG.md"))
    }

    def "API_CATALOG.md contains service name"() {
        given:
        def modules = [mod("payment-service", 8082)]

        when:
        gen.generateDocumentation(tempDir, modules, [])

        then:
        def content = Files.readString(tempDir.resolve("docs/API_CATALOG.md"))
        content.contains("payment-service") || content.contains("Payment")
    }

    def "API_CATALOG.md contains service port"() {
        given:
        def modules = [mod("order-service", 8081)]

        when:
        gen.generateDocumentation(tempDir, modules, [])

        then:
        def content = Files.readString(tempDir.resolve("docs/API_CATALOG.md"))
        content.contains("8081")
    }

    def "handles empty module list without error"() {
        when:
        gen.generateDocumentation(tempDir, [], [])

        then:
        noExceptionThrown()
        Files.exists(tempDir.resolve("docs/API_CATALOG.md"))
    }

    def "generates catalog for multiple modules"() {
        given:
        def modules = [mod("order-service", 8081), mod("payment-service", 8082)]

        when:
        gen.generateDocumentation(tempDir, modules, [])

        then:
        def content = Files.readString(tempDir.resolve("docs/API_CATALOG.md"))
        content.contains("8081")
        content.contains("8082")
    }
}
