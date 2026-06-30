package org.fractalx.core.datamanagement

import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class StateStoreGeneratorSpec extends Specification {

    @TempDir Path serviceRoot

    StateStoreGenerator gen = new StateStoreGenerator()

    FractalModule module = FractalModule.builder()
        .serviceName("order-service")
        .packageName("com.example.order")
        .port(8081)
        .build()

    def "generates DistributedStateConfig.java in config package"() {
        given:
        def srcMainJava = serviceRoot.resolve("src/main/java")
        Files.createDirectories(srcMainJava)

        when:
        gen.generateStateStoreConfig(module, srcMainJava, "com.example.generated.orderservice")

        then:
        Files.exists(srcMainJava.resolve(
            "com/example/generated/orderservice/config/DistributedStateConfig.java"))
    }

    def "DistributedStateConfig.java contains Redis template bean"() {
        given:
        def srcMainJava = serviceRoot.resolve("src/main/java")
        Files.createDirectories(srcMainJava)

        when:
        gen.generateStateStoreConfig(module, srcMainJava, "com.example.generated.orderservice")

        then:
        def content = Files.readString(srcMainJava.resolve(
            "com/example/generated/orderservice/config/DistributedStateConfig.java"))
        content.contains("RedisTemplate")
        content.contains("@Bean")
    }

    def "DistributedStateConfig.java contains @Configuration annotation"() {
        given:
        def srcMainJava = serviceRoot.resolve("src/main/java")
        Files.createDirectories(srcMainJava)

        when:
        gen.generateStateStoreConfig(module, srcMainJava, "com.example.generated.orderservice")

        then:
        def content = Files.readString(srcMainJava.resolve(
            "com/example/generated/orderservice/config/DistributedStateConfig.java"))
        content.contains("@Configuration")
    }

    def "DistributedStateConfig.java has correct package declaration"() {
        given:
        def srcMainJava = serviceRoot.resolve("src/main/java")
        Files.createDirectories(srcMainJava)

        when:
        gen.generateStateStoreConfig(module, srcMainJava, "com.example.generated.orderservice")

        then:
        def content = Files.readString(srcMainJava.resolve(
            "com/example/generated/orderservice/config/DistributedStateConfig.java"))
        content.startsWith("package com.example.generated.orderservice.config")
            || content.contains("package com.example.generated.orderservice.config;")
    }

    def "generates in nested config package within basePackage"() {
        given:
        def srcMainJava = serviceRoot.resolve("src/main/java")
        Files.createDirectories(srcMainJava)

        when:
        gen.generateStateStoreConfig(module, srcMainJava, "org.acme.gen.mysvc")

        then:
        Files.exists(srcMainJava.resolve("org/acme/gen/mysvc/config/DistributedStateConfig.java"))
    }
}
