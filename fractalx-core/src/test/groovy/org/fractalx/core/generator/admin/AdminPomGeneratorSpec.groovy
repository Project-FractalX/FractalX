package org.fractalx.core.generator.admin

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import org.fractalx.core.config.FractalxConfig

/**
 * Verifies that AdminPomGenerator produces a pom.xml for the admin service with
 * all required dependencies, including spring-boot-starter-mail for alert email
 * notifications and spring-boot-configuration-processor for @ConfigurationProperties.
 */
class AdminPomGeneratorSpec extends Specification {

    @TempDir
    Path serviceRoot

    AdminPomGenerator generator = new AdminPomGenerator()

    private String pom() { Files.readString(serviceRoot.resolve("pom.xml")) }

    // -------------------------------------------------------------------------
    // File existence
    // -------------------------------------------------------------------------

    def "pom.xml is created at the service root"() {
        when:
        generator.generate(serviceRoot, FractalxConfig.defaults())

        then:
        Files.exists(serviceRoot.resolve("pom.xml"))
    }

    // -------------------------------------------------------------------------
    // Project identity
    // -------------------------------------------------------------------------

    def "pom.xml declares artifactId admin-service under org.fractalx.generated"() {
        when:
        generator.generate(serviceRoot, FractalxConfig.defaults())

        then:
        def c = pom()
        c.contains("<artifactId>admin-service</artifactId>")
        c.contains("<groupId>generated</groupId>")
    }

    def "pom.xml uses Spring Boot 3.2.0 dependency management"() {
        when:
        generator.generate(serviceRoot, FractalxConfig.defaults())

        then:
        def c = pom()
        c.contains("spring-boot.version")
        c.contains("3.2.0")
    }

    def "pom.xml sets Java 17 compiler source and target"() {
        when:
        generator.generate(serviceRoot, FractalxConfig.defaults())

        then:
        def c = pom()
        c.contains("<java.version>21</java.version>")
        c.contains("<maven.compiler.source>21</maven.compiler.source>")
        c.contains("<maven.compiler.target>21</maven.compiler.target>")
    }

    // -------------------------------------------------------------------------
    // Core Spring Boot dependencies
    // -------------------------------------------------------------------------

    def "pom.xml includes spring-boot-starter-web"() {
        when:
        generator.generate(serviceRoot, FractalxConfig.defaults())

        then:
        pom().contains("spring-boot-starter-web")
    }

    def "pom.xml includes spring-boot-starter-thymeleaf for server-side rendering"() {
        when:
        generator.generate(serviceRoot, FractalxConfig.defaults())

        then:
        pom().contains("spring-boot-starter-thymeleaf")
    }

    def "pom.xml includes spring-boot-starter-security"() {
        when:
        generator.generate(serviceRoot, FractalxConfig.defaults())

        then:
        pom().contains("spring-boot-starter-security")
    }

    def "pom.xml includes spring-boot-starter-actuator"() {
        when:
        generator.generate(serviceRoot, FractalxConfig.defaults())

        then:
        pom().contains("spring-boot-starter-actuator")
    }

    // -------------------------------------------------------------------------
    // Observability / alerting dependencies
    // -------------------------------------------------------------------------

    def "pom.xml includes spring-boot-starter-mail for alert email channel"() {
        when:
        generator.generate(serviceRoot, FractalxConfig.defaults())

        then:
        pom().contains("spring-boot-starter-mail")
    }

    def "pom.xml includes spring-boot-configuration-processor as optional dependency"() {
        when:
        generator.generate(serviceRoot, FractalxConfig.defaults())

        then:
        def c = pom()
        c.contains("spring-boot-configuration-processor")
        c.contains("<optional>true</optional>")
    }

    // -------------------------------------------------------------------------
    // FractalX runtime dependency
    // -------------------------------------------------------------------------

    def "pom.xml includes fractalx-runtime dependency"() {
        when:
        generator.generate(serviceRoot, FractalxConfig.defaults())

        then:
        pom().contains("fractalx-runtime")
    }

    // -------------------------------------------------------------------------
    // Frontend webjars
    // -------------------------------------------------------------------------

    def "pom.xml includes bootstrap, jquery, and font-awesome webjars"() {
        when:
        generator.generate(serviceRoot, FractalxConfig.defaults())

        then:
        def c = pom()
        c.contains("bootstrap")
        c.contains("jquery")
        c.contains("font-awesome")
    }

    // -------------------------------------------------------------------------
    // Build plugin
    // -------------------------------------------------------------------------

    def "pom.xml includes spring-boot-maven-plugin with repackage goal"() {
        when:
        generator.generate(serviceRoot, FractalxConfig.defaults())

        then:
        def c = pom()
        c.contains("spring-boot-maven-plugin")
        c.contains("<goal>repackage</goal>")
    }
}
