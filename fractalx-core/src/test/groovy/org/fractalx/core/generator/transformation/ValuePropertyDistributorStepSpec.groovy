package org.fractalx.core.generator.transformation

import org.fractalx.core.config.FractalxConfig
import org.fractalx.core.generator.GenerationContext
import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class ValuePropertyDistributorStepSpec extends Specification {

    @TempDir Path monolithRoot
    @TempDir Path serviceRoot

    ValuePropertyDistributorStep step = new ValuePropertyDistributorStep()

    FractalModule module = FractalModule.builder()
        .serviceName("order-service")
        .packageName("com.example.order")
        .port(8081)
        .build()

    FractalxConfig cfg = FractalxConfig.defaults()

    private GenerationContext ctx() {
        // sourceRoot is monolithRoot/src/main/java; step looks at parent/resources
        def srcRoot = monolithRoot.resolve("src/main/java")
        Files.createDirectories(srcRoot)
        new GenerationContext(module, srcRoot, serviceRoot, [module], cfg)
    }

    private void writeMonolithYml(String content) {
        Path res = monolithRoot.resolve("src/main/resources")
        Files.createDirectories(res)
        Files.writeString(res.resolve("application.yml"), content)
    }

    private void writeServiceJava(String content) {
        Path dir = serviceRoot.resolve("src/main/java/com/example/order")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("OrderService.java"), content)
    }

    private Path createDevYml() {
        Path devYml = serviceRoot.resolve("src/main/resources/application-dev.yml")
        Files.createDirectories(devYml.parent)
        Files.writeString(devYml, "server:\n  port: 8081\n")
        devYml
    }

    def "is a no-op when no @Value or @ConfigurationProperties annotations in source"() {
        given:
        writeServiceJava("""
            package com.example.order;
            public class OrderService { }
        """)
        createDevYml()
        writeMonolithYml("app:\n  timeout: 30\n")

        when:
        step.generate(ctx())

        then:
        noExceptionThrown()
    }

    def "appends resolved properties to application-dev.yml"() {
        given:
        writeMonolithYml("myapp:\n  timeout: 30\n")
        writeServiceJava("""
            package com.example.order;
            import org.springframework.beans.factory.annotation.Value;
            public class OrderService {
                @Value("\${myapp.timeout}")
                private int timeout;
            }
        """)
        def devYml = createDevYml()

        when:
        step.generate(ctx())

        then:
        def content = Files.readString(devYml)
        content.contains("myapp.timeout") || content.contains("myapp") // resolved
    }

    def "does not distribute blocked keys like server.port"() {
        given:
        writeMonolithYml("server:\n  port: 9090\n")
        writeServiceJava("""
            package com.example.order;
            import org.springframework.beans.factory.annotation.Value;
            public class OrderService {
                @Value("\${server.port}")
                private int port;
            }
        """)
        def devYml = createDevYml()
        def originalContent = Files.readString(devYml)

        when:
        step.generate(ctx())

        then:
        // server.port is blocked — devYml should not gain it
        def content = Files.readString(devYml)
        !content.contains("server.port: 9090")
    }

    def "adds warning comment for missing keys"() {
        given:
        writeMonolithYml("other:\n  key: value\n")
        writeServiceJava("""
            package com.example.order;
            import org.springframework.beans.factory.annotation.Value;
            public class OrderService {
                @Value("\${missing.config.key}")
                private String key;
            }
        """)
        def devYml = createDevYml()

        when:
        step.generate(ctx())

        then:
        def content = Files.readString(devYml)
        content.contains("FRACTALX-WARNING") || content.contains("REQUIRED") || content.contains("missing.config.key")
    }

    def "is a no-op when monolith resources directory does not exist"() {
        given:
        writeServiceJava("""
            package com.example.order;
            import org.springframework.beans.factory.annotation.Value;
            public class OrderService {
                @Value("\${some.key}")
                private String key;
            }
        """)
        // service-side dev yml must exist so the step can write warnings to it
        createDevYml()
        // No monolith resources directory → monolith props will be empty

        when:
        step.generate(ctx())

        then:
        // Step finds @Value but no monolith props → writes FRACTALX-WARNING comment; must not throw
        noExceptionThrown()
    }
}
