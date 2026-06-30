package org.fractalx.core.generator.transformation

import org.fractalx.core.config.FractalxConfig
import org.fractalx.core.generator.GenerationContext
import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class CodeCopierSpec extends Specification {

    @TempDir Path monolithRoot
    @TempDir Path serviceRoot

    CodeCopier step = new CodeCopier()

    private FractalModule mod(String name, String pkg) {
        FractalModule.builder()
            .serviceName(name)
            .packageName(pkg)
            .port(8081)
            .build()
    }

    private GenerationContext ctx(FractalModule module) {
        def srcRoot = monolithRoot.resolve("src/main/java")
        Files.createDirectories(srcRoot)
        new GenerationContext(module, srcRoot, serviceRoot, [module], FractalxConfig.defaults())
    }

    def "copies source package files into service src/main/java"() {
        given:
        def module = mod("order-service", "com.example.order")
        def srcPkg = monolithRoot.resolve("src/main/java/com/example/order")
        Files.createDirectories(srcPkg)
        Files.writeString(srcPkg.resolve("OrderService.java"), "package com.example.order; class OrderService {}")

        when:
        step.generate(ctx(module))

        then:
        Files.exists(serviceRoot.resolve("src/main/java/com/example/order/OrderService.java"))
    }

    def "is a no-op when module has no package name"() {
        given:
        def module = FractalModule.builder().serviceName("s").port(8080).build()

        when:
        step.generate(ctx(module))

        then:
        noExceptionThrown()
    }

    def "is a no-op when source package directory does not exist"() {
        given:
        def module = mod("order-service", "com.example.nonexistent")

        when:
        step.generate(ctx(module))

        then:
        noExceptionThrown()
    }

    def "copies nested directories recursively"() {
        given:
        def module = mod("order-service", "com.example.order")
        def nested = monolithRoot.resolve("src/main/java/com/example/order/dto")
        Files.createDirectories(nested)
        Files.writeString(nested.resolve("OrderDto.java"), "package com.example.order.dto; class OrderDto {}")

        when:
        step.generate(ctx(module))

        then:
        Files.exists(serviceRoot.resolve("src/main/java/com/example/order/dto/OrderDto.java"))
    }
}
