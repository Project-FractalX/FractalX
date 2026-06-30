package org.fractalx.core.generator.service

import org.fractalx.core.config.FractalxConfig
import org.fractalx.core.generator.GenerationContext
import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies that CorrelationIdGenerator produces logback-spring.xml
 * with the correct correlation ID log pattern.
 */
class CorrelationIdGeneratorSpec extends Specification {

    @TempDir
    Path serviceRoot

    CorrelationIdGenerator generator = new CorrelationIdGenerator()

    FractalModule module = FractalModule.builder()
        .serviceName("order-service")
        .packageName("org.fractalx.test.order")
        .port(8081)
        .dependencies([])
        .build()

    private GenerationContext ctx() {
        def resourcesDir = serviceRoot.resolve("src/main/resources")
        Files.createDirectories(resourcesDir)
        new GenerationContext(module, serviceRoot, serviceRoot, [], FractalxConfig.defaults())
    }

    private String logback() {
        Files.readString(serviceRoot.resolve("src/main/resources/logback-spring.xml"))
    }

    def "logback-spring.xml is generated"() {
        when:
        generator.generate(ctx())

        then:
        Files.exists(serviceRoot.resolve("src/main/resources/logback-spring.xml"))
    }

    def "logback-spring.xml includes correlationId MDC key with NO_CORR fallback"() {
        when:
        generator.generate(ctx())

        then:
        logback().contains("%X{correlationId:-NO_CORR}")
    }

    def "logback-spring.xml uses merged Spring Boot + correlationId pattern"() {
        when:
        generator.generate(ctx())

        then:
        def content = logback()
        // ISO-8601 timestamp with timezone offset
        content.contains("%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX}")
        // process ID — uses ${PID} system property (ProcessIdConverter removed in Spring Boot 3.x)
        content.contains("\${PID:-????}")
        // Spring Boot --- separator and app-name column
        content.contains("---")
        content.contains("\${APP_NAME}")
        // padded thread column
        content.contains("%15.15t")
        // abbreviated logger padded to 40 chars
        content.contains("%-40.40logger{39}")
    }

    def "logback-spring.xml does not use %pid converter (removed in Spring Boot 3.x)"() {
        when:
        generator.generate(ctx())

        then:
        def content = logback()
        // ProcessIdConverter no longer exists in Spring Boot 3 — must use ${PID} property instead
        !content.contains("ProcessIdConverter")
        !content.contains("%pid")
    }

    def "logback-spring.xml binds spring.application.name via springProperty"() {
        when:
        generator.generate(ctx())

        then:
        def content = logback()
        content.contains("<springProperty")
        content.contains("spring.application.name")
        content.contains("APP_NAME")
    }

    def "logback-spring.xml includes CONSOLE and FILE appenders"() {
        when:
        generator.generate(ctx())

        then:
        def content = logback()
        content.contains("CONSOLE")
        content.contains("FILE")
    }

    def "logback-spring.xml includes the service name in file appender path"() {
        when:
        generator.generate(ctx())

        then:
        logback().contains("order-service")
    }

    def "generator is idempotent — skips if logback-spring.xml already exists"() {
        given: "resources dir already exists with a custom logback file"
        def context = ctx()  // creates resources dir as side effect
        def existing = serviceRoot.resolve("src/main/resources/logback-spring.xml")
        Files.writeString(existing, "<!-- custom -->")

        when:
        generator.generate(context)

        then: "existing file is preserved"
        Files.readString(existing) == "<!-- custom -->"
    }
}
