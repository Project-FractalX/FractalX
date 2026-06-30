package org.fractalx.core.generator.transformation

import org.fractalx.core.config.FractalxConfig
import org.fractalx.core.datamanagement.RelationshipDecoupler
import org.fractalx.core.generator.GenerationContext
import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class CodeTransformerSpec extends Specification {

    @TempDir Path monolithRoot
    @TempDir Path serviceRoot

    AnnotationRemover    annotationRemover    = new AnnotationRemover()
    RelationshipDecoupler decoupler           = new RelationshipDecoupler()
    ImportPreserver      importPreserver      = new ImportPreserver()
    ImportCleaner        importCleaner        = new ImportCleaner()

    CodeTransformer transformer = new CodeTransformer(
        annotationRemover, decoupler, importPreserver, importCleaner)

    private FractalModule mod() {
        FractalModule.builder()
            .serviceName("order-service")
            .packageName("com.example.order")
            .port(8081)
            .build()
    }

    private GenerationContext ctx() {
        def srcRoot = monolithRoot.resolve("src/main/java")
        Files.createDirectories(srcRoot)
        def svcJava = serviceRoot.resolve("src/main/java")
        Files.createDirectories(svcJava)
        new GenerationContext(mod(), srcRoot, serviceRoot, [mod()], FractalxConfig.defaults())
    }

    def "does not throw when service src directory is empty"() {
        when:
        transformer.generate(ctx())

        then:
        noExceptionThrown()
    }

    def "does not throw when service has valid Java files"() {
        given:
        def dir = serviceRoot.resolve("src/main/java/com/example/order")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("OrderService.java"), """
            package com.example.order;
            import org.springframework.stereotype.Service;
            @Service
            public class OrderService {}
        """)

        when:
        transformer.generate(ctx())

        then:
        noExceptionThrown()
    }
}
