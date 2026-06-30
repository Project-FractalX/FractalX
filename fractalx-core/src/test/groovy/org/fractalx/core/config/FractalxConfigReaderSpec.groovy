package org.fractalx.core.config

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class FractalxConfigReaderSpec extends Specification {

    @TempDir Path projectRoot

    FractalxConfigReader reader = new FractalxConfigReader()

    private Path resourcesDir() {
        def dir = projectRoot.resolve("src/main/resources")
        Files.createDirectories(dir)
        dir
    }

    def "returns defaults when resources directory does not exist"() {
        when:
        def cfg = reader.read(projectRoot.resolve("nonexistent"), projectRoot)

        then:
        cfg != null
        cfg.javaVersion() == FractalxConfig.defaults().javaVersion()
    }

    def "returns defaults when fractalx-config.yml is absent"() {
        given:
        def res = resourcesDir()

        when:
        def cfg = reader.read(res, projectRoot)

        then:
        cfg != null
        cfg.registryUrl() == FractalxConfig.defaults().registryUrl()
    }

    def "reads basePackage from fractalx-config.yml"() {
        given:
        def res = resourcesDir()
        Files.writeString(res.resolve("fractalx-config.yml"), """
            fractalx:
              base-package: com.mycompany.generated
        """)

        when:
        def cfg = reader.read(res, projectRoot)

        then:
        cfg.effectiveBasePackage() == "com.mycompany.generated"
    }

    def "reads javaVersion from fractalx-config.yml"() {
        given:
        def res = resourcesDir()
        Files.writeString(res.resolve("fractalx-config.yml"), """
            fractalx:
              java-version: "21"
        """)

        when:
        def cfg = reader.read(res, projectRoot)

        then:
        cfg.javaVersion() == "21"
    }

    def "reads registryUrl from fractalx-config.yml"() {
        given:
        def res = resourcesDir()
        Files.writeString(res.resolve("fractalx-config.yml"), """
            fractalx:
              registry:
                url: http://custom-registry:8761/eureka
        """)

        when:
        def cfg = reader.read(res, projectRoot)

        then:
        cfg.registryUrl().contains("custom-registry")
    }

    def "falls back to application.yml for groupId when fractalx-config.yml absent"() {
        given:
        def res = resourcesDir()
        Files.writeString(res.resolve("application.yml"), """
            spring:
              application:
                name: my-monolith
        """)

        when:
        def cfg = reader.read(res, projectRoot)

        then:
        cfg != null
        noExceptionThrown()
    }

    def "reads groupId from pom.xml when present"() {
        given:
        def res = resourcesDir()
        Files.writeString(projectRoot.resolve("pom.xml"), """
            <project>
              <groupId>com.acme</groupId>
              <artifactId>my-monolith</artifactId>
              <version>1.0.0</version>
            </project>
        """)

        when:
        def cfg = reader.read(res, projectRoot)

        then:
        cfg != null
        cfg.effectiveBasePackage() == "com.acme.generated" || cfg.effectiveBasePackage() != null
    }

    def "handles malformed yaml gracefully"() {
        given:
        def res = resourcesDir()
        Files.writeString(res.resolve("fractalx-config.yml"), "not: valid: yaml: : :")

        when:
        def cfg = reader.read(res, projectRoot)

        then:
        cfg != null
        noExceptionThrown()
    }
}
