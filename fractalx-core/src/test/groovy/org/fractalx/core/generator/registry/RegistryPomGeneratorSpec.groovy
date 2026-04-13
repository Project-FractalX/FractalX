package org.fractalx.core.generator.registry

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import org.fractalx.core.config.FractalxConfig

/**
 * Verifies that RegistryPomGenerator writes a well-formed pom.xml for the
 * fractalx-registry service containing all required coordinates and the
 * spring-boot-starter-web and actuator dependencies.
 */
class RegistryPomGeneratorSpec extends Specification {

    @TempDir
    Path registryRoot

    RegistryPomGenerator generator = new RegistryPomGenerator()

    private String pom() { Files.readString(registryRoot.resolve("pom.xml")) }

    def "pom.xml is created at the registry root"() {
        when:
        generator.generate(registryRoot, FractalxConfig.defaults())

        then:
        Files.exists(registryRoot.resolve("pom.xml"))
    }

    def "pom.xml declares the correct groupId and artifactId"() {
        when:
        generator.generate(registryRoot, FractalxConfig.defaults())

        then:
        pom().contains("<groupId>org.fractalx</groupId>")
        pom().contains("<artifactId>fractalx-registry</artifactId>")
    }

    def "pom.xml targets Java 21 and Spring Boot 3.2.0"() {
        when:
        generator.generate(registryRoot, FractalxConfig.defaults())

        then:
        pom().contains("<java.version>21</java.version>")
        pom().contains("<spring-boot.version>3.2.0</spring-boot.version>")
    }

    def "pom.xml includes spring-boot-starter-web dependency"() {
        when:
        generator.generate(registryRoot, FractalxConfig.defaults())

        then:
        pom().contains("spring-boot-starter-web")
    }

    def "pom.xml includes spring-boot-starter-actuator dependency"() {
        when:
        generator.generate(registryRoot, FractalxConfig.defaults())

        then:
        pom().contains("spring-boot-starter-actuator")
    }

    def "pom.xml includes spring-boot-starter-validation so @Valid on register endpoint works"() {
        when:
        generator.generate(registryRoot, FractalxConfig.defaults())

        then:
        pom().contains("spring-boot-starter-validation")
    }

    def "pom.xml uses spring-boot-dependencies BOM for dependency management"() {
        when:
        generator.generate(registryRoot, FractalxConfig.defaults())

        then:
        pom().contains("spring-boot-dependencies")
        pom().contains("<type>pom</type>")
        pom().contains("<scope>import</scope>")
    }

    def "pom.xml configures the spring-boot-maven-plugin with repackage goal"() {
        when:
        generator.generate(registryRoot, FractalxConfig.defaults())

        then:
        pom().contains("spring-boot-maven-plugin")
        pom().contains("<goal>repackage</goal>")
    }

    def "pom.xml is valid XML (not empty and starts with XML declaration)"() {
        when:
        generator.generate(registryRoot, FractalxConfig.defaults())

        then:
        pom().startsWith("<?xml")
        pom().contains("<project")
        pom().contains("</project>")
    }
}
