package org.fractalx.core.generator.transformation

import org.fractalx.core.config.FractalxConfig
import org.fractalx.core.generator.GenerationContext
import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class ImportPreserverSpec extends Specification {

    @TempDir Path serviceRoot

    ImportPreserver step = new ImportPreserver()

    FractalModule module = FractalModule.builder()
        .serviceName("order-service")
        .packageName("com.example.order")
        .port(8081)
        .build()

    private GenerationContext ctx() {
        new GenerationContext(module, serviceRoot, serviceRoot, [module], FractalxConfig.defaults())
    }

    private Path writeJava(String relativePath, String content) {
        Path f = serviceRoot.resolve(relativePath)
        Files.createDirectories(f.parent)
        Files.writeString(f, content)
        f
    }

    def "does not modify files that have no Spring annotations (name avoids substring match)"() {
        given:
        // Use class name 'OrderManager' — none of the Spring annotation names (Service, Component, etc.)
        // appear as substrings. ImportPreserver checks content.contains("@X") || content.contains("X ").
        def file = writeJava("src/main/java/com/example/order/OrderManager.java", """
            package com.example.order;
            public class OrderManager { }
        """)
        def original = Files.readString(file)

        when:
        step.generate(ctx())

        then:
        Files.readString(file) == original
    }

    def "does not modify Application.java files"() {
        given:
        def file = writeJava("src/main/java/com/example/order/OrderApplication.java", """
            package com.example.order;
            @Service public class OrderApplication { }
        """)
        def original = Files.readString(file)

        when:
        step.generate(ctx())

        then:
        // Application.java is excluded from processing
        Files.readString(file) == original
    }

    def "does not throw when serviceRoot has no java files"() {
        given:
        Files.createDirectories(serviceRoot.resolve("src/main/java"))

        when:
        step.generate(ctx())

        then:
        noExceptionThrown()
    }

    def "does not throw on empty service root"() {
        when:
        step.generate(ctx())

        then:
        noExceptionThrown()
    }

    def "preserves existing imports that are already present"() {
        given:
        def file = writeJava("src/main/java/com/example/order/OrderService.java", """
            package com.example.order;
            import org.springframework.stereotype.Service;
            @Service public class OrderService { }
        """)
        def original = Files.readString(file)

        when:
        step.generate(ctx())

        then:
        // File should still have the import, not have it duplicated
        def result = Files.readString(file)
        result.count("import org.springframework.stereotype.Service;") == 1
    }
}
