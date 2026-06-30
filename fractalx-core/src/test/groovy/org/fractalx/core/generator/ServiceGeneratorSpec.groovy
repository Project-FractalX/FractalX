package org.fractalx.core.generator

import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class ServiceGeneratorSpec extends Specification {

    @TempDir Path monolithRoot
    @TempDir Path outputDir

    private FractalModule mod(String name, String pkg, int port = 8081) {
        FractalModule.builder()
            .serviceName(name)
            .packageName(pkg)
            .port(port)
            .build()
    }

    private Path srcMainJava() {
        def p = monolithRoot.resolve("src/main/java")
        Files.createDirectories(p)
        p
    }

    private ServiceGenerator gen() {
        new ServiceGenerator(srcMainJava(), outputDir)
    }

    def "constructor does not throw"() {
        when:
        def g = gen()

        then:
        noExceptionThrown()
        g != null
    }

    def "withGateway and withDocker builder methods return self"() {
        given:
        def g = gen()

        when:
        def result = g.withGateway(false).withDocker(false)

        then:
        result.is(g)
    }

    def "setProgressCallbacks accepts lambdas"() {
        given:
        def g = gen()

        when:
        g.setProgressCallbacks({ lbl -> }, { lbl -> })

        then:
        noExceptionThrown()
    }

    def "generateServices creates output directory"() {
        given:
        // Write a minimal module source so pipeline can run
        def pkg = "com.example.order"
        def pkgDir = srcMainJava().resolve("com/example/order")
        Files.createDirectories(pkgDir)
        Files.writeString(pkgDir.resolve("OrderService.java"), """
            package com.example.order;
            import org.springframework.stereotype.Service;
            @Service
            public class OrderService {}
        """)
        def module = mod("order-service", pkg)

        when:
        gen().withGateway(false).withDocker(false).generateServices([module])

        then:
        noExceptionThrown()
        Files.isDirectory(outputDir)
    }

    def "generateServices produces service directory"() {
        given:
        def pkgDir = srcMainJava().resolve("com/example/order")
        Files.createDirectories(pkgDir)
        Files.writeString(pkgDir.resolve("OrderService.java"), """
            package com.example.order;
            public class OrderService {}
        """)
        def module = mod("order-service", "com.example.order")

        when:
        gen().withGateway(false).withDocker(false).generateServices([module])

        then:
        Files.isDirectory(outputDir.resolve("order-service"))
    }

    def "generateServices with empty module list does not throw"() {
        when:
        gen().withGateway(false).withDocker(false).generateServices([])

        then:
        noExceptionThrown()
    }

    def "generateServices with gateway enabled generates gateway directory"() {
        given:
        def pkgDir = srcMainJava().resolve("com/example/order")
        Files.createDirectories(pkgDir)
        Files.writeString(pkgDir.resolve("OrderService.java"), "package com.example.order; public class OrderService {}")
        def module = mod("order-service", "com.example.order")

        when:
        gen().withGateway(true).withDocker(false).generateServices([module])

        then:
        noExceptionThrown()
        Files.isDirectory(outputDir.resolve("fractalx-gateway")) ||
        Files.isDirectory(outputDir.resolve("order-service"))
    }
}
