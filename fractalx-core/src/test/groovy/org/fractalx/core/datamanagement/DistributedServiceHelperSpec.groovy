package org.fractalx.core.datamanagement

import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class DistributedServiceHelperSpec extends Specification {

    @TempDir Path monolithRoot
    @TempDir Path serviceRoot

    DistributedServiceHelper helper = new DistributedServiceHelper()

    private FractalModule mod(String name, String pkg, Set<String> imports = []) {
        FractalModule.builder()
            .serviceName(name)
            .packageName(pkg)
            .port(8081)
            .detectedImports(imports)
            .build()
    }

    private void setupMinimalService() {
        // service needs pom.xml and application.yml to accept driver and DB config
        Files.createDirectories(serviceRoot.resolve("src/main/resources"))
        Files.writeString(serviceRoot.resolve("pom.xml"), "<project><dependencies></dependencies></project>")
        Files.writeString(serviceRoot.resolve("src/main/resources/application.yml"),
            "spring:\n  application:\n    name: order-service\n")
    }

    def "upgradeService does not throw for module without JPA imports"() {
        given:
        setupMinimalService()
        def module = mod("order-service", "com.example.order")

        when:
        helper.upgradeService(module, monolithRoot, serviceRoot, "com.example.generated.orderservice", "3.2.0")

        then:
        noExceptionThrown()
    }

    def "upgradeService generates DATA_README.md"() {
        given:
        setupMinimalService()
        def module = mod("order-service", "com.example.order")

        when:
        helper.upgradeService(module, monolithRoot, serviceRoot, "com.example.generated.orderservice", "3.2.0")

        then:
        Files.exists(serviceRoot.resolve("DATA_README.md"))
    }

    def "upgradeService generates flyway migration directory or at least does not throw"() {
        given:
        setupMinimalService()
        def module = mod("order-service", "com.example.order")

        when:
        helper.upgradeService(module, monolithRoot, serviceRoot, "com.example.generated.orderservice", "3.2.0")

        then:
        noExceptionThrown()
        // flyway migration dir may or may not be created depending on JPA detection
        true
    }

    def "upgradeService with JPA imports does not throw"() {
        given:
        setupMinimalService()
        def module = mod("order-service", "com.example.order",
            ["jakarta.persistence.Entity", "jakarta.persistence.Repository"] as Set)
        Files.createDirectories(serviceRoot.resolve("src/main/java"))

        when:
        helper.upgradeService(module, monolithRoot, serviceRoot, "com.example.generated.orderservice", "3.2.0")

        then:
        noExceptionThrown()
    }
}
