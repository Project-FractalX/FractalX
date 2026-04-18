package org.fractalx.core.generator.admin

import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies that AdminApiExplorerGenerator produces ApiExplorerController
 * in the org.fractalx.admin.explorer package with service listing,
 * actuator/mappings proxy, and live request relay endpoints.
 */
class AdminApiExplorerGeneratorSpec extends Specification {

    @TempDir
    Path srcMainJava

    AdminApiExplorerGenerator generator = new AdminApiExplorerGenerator()
    String basePackage = "org.fractalx.admin"

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

    private Path explorerPkg() {
        srcMainJava.resolve("org/fractalx/admin/explorer")
    }

    private String readController() {
        Files.readString(explorerPkg().resolve("ApiExplorerController.java"))
    }

    def "ApiExplorerController.java is created in explorer package"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        Files.exists(explorerPkg().resolve("ApiExplorerController.java"))
    }

    def "ApiExplorerController is in org.fractalx.admin.explorer package"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        readController().contains("package org.fractalx.admin.explorer")
    }

    def "ApiExplorerController is a @RestController mapped to /api/explorer"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readController()
        c.contains("@RestController")
        c.contains("/api/explorer")
    }

    def "ApiExplorerController exposes GET /services listing all registered services"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readController()
        c.contains("/services")
        c.contains("services(")
        c.contains("baseUrl")
    }

    def "ApiExplorerController includes port and baseUrl in service listing"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readController()
        c.contains("port")
        c.contains("http://localhost:")
    }

    def "ApiExplorerController exposes GET /{service}/mappings proxying actuator/mappings"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readController()
        c.contains("/mappings")
        c.contains("actuator/mappings")
        c.contains("mappings(")
    }

    def "ApiExplorerController extracts endpoints from dispatcherServlets in mappings response"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readController()
        c.contains("dispatcherServlets")
        c.contains("requestMappingConditions")
        c.contains("patterns")
    }

    def "ApiExplorerController exposes POST /request to relay HTTP calls"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readController()
        c.contains("@PostMapping")
        c.contains("/request")
        c.contains("executeRequest")
    }

    def "ApiExplorerController relay supports method, url, body, and headers"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readController()
        c.contains("method")
        c.contains("url")
        c.contains("body")
        c.contains("headers")
    }

    def "ApiExplorerController relay returns status, statusText, headers, body, durationMs"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readController()
        c.contains("status")
        c.contains("statusText")
        c.contains("durationMs")
    }

    def "ApiExplorerController handles HttpStatusCodeException for error responses"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        readController().contains("HttpStatusCodeException")
    }

    def "ApiExplorerController injects ServiceMetaRegistry"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        readController().contains("ServiceMetaRegistry")
    }

    def "ApiExplorerController sets 3s connect and 10s read timeouts"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readController()
        c.contains("3_000") || c.contains("3000")
        c.contains("10_000") || c.contains("10000")
    }

    def "ApiExplorerController uses version-safe header check (no containsHeader)"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readController()
        !c.contains("containsHeader")
        c.contains("getFirst")
    }

    def "generator works with multiple modules"() {
        when:
        generator.generate(srcMainJava, basePackage, [order, payment])

        then:
        Files.exists(explorerPkg().resolve("ApiExplorerController.java"))
    }
}
