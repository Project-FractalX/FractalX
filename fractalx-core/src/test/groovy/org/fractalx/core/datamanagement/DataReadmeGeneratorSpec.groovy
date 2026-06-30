package org.fractalx.core.datamanagement

import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class DataReadmeGeneratorSpec extends Specification {

    @TempDir Path serviceRoot

    DataReadmeGenerator gen = new DataReadmeGenerator()

    FractalModule module = FractalModule.builder()
        .serviceName("order-service")
        .packageName("com.example.order")
        .port(8081)
        .build()

    def "generates DATA_README.md in service root"() {
        when:
        gen.generateServiceDataReadme(module, serviceRoot, "org.h2.Driver")

        then:
        Files.exists(serviceRoot.resolve("DATA_README.md"))
    }

    def "DATA_README.md contains service name as header"() {
        when:
        gen.generateServiceDataReadme(module, serviceRoot, "org.h2.Driver")

        then:
        def content = Files.readString(serviceRoot.resolve("DATA_README.md"))
        content.contains("order-service")
    }

    def "DATA_README.md mentions Database-per-Service pattern"() {
        when:
        gen.generateServiceDataReadme(module, serviceRoot, "org.h2.Driver")

        then:
        def content = Files.readString(serviceRoot.resolve("DATA_README.md"))
        content.contains("Database-per-Service")
    }

    def "DATA_README.md shows H2 when H2 driver is used"() {
        when:
        gen.generateServiceDataReadme(module, serviceRoot, "org.h2.Driver")

        then:
        def content = Files.readString(serviceRoot.resolve("DATA_README.md"))
        content.contains("H2")
    }

    def "DATA_README.md shows MySQL when MySQL driver is used"() {
        when:
        gen.generateServiceDataReadme(module, serviceRoot, "com.mysql.cj.jdbc.Driver")

        then:
        def content = Files.readString(serviceRoot.resolve("DATA_README.md"))
        content.toLowerCase().contains("mysql")
    }

    def "DATA_README.md contains driver class name"() {
        when:
        gen.generateServiceDataReadme(module, serviceRoot, "org.postgresql.Driver")

        then:
        def content = Files.readString(serviceRoot.resolve("DATA_README.md"))
        content.contains("org.postgresql.Driver")
    }

    def "DATA_README.md with null driver class does not throw"() {
        when:
        gen.generateServiceDataReadme(module, serviceRoot, null)

        then:
        noExceptionThrown()
        Files.exists(serviceRoot.resolve("DATA_README.md"))
    }

    def "DATA_README.md contains port in H2 console link"() {
        when:
        gen.generateServiceDataReadme(module, serviceRoot, "org.h2.Driver")

        then:
        def content = Files.readString(serviceRoot.resolve("DATA_README.md"))
        content.contains("8081")
    }

    def "DATA_README.md contains fractalx-config.yml instructions"() {
        when:
        gen.generateServiceDataReadme(module, serviceRoot, "org.h2.Driver")

        then:
        def content = Files.readString(serviceRoot.resolve("DATA_README.md"))
        content.contains("fractalx")
    }
}
