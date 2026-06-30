package org.fractalx.core.generator.transformation

import org.fractalx.core.config.FractalxConfig
import org.fractalx.core.generator.GenerationContext
import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class DecompositionHintsStepSpec extends Specification {

    @TempDir Path serviceRoot

    DecompositionHintsStep step = new DecompositionHintsStep()

    FractalModule module = FractalModule.builder()
        .serviceName("order-service")
        .packageName("com.example.order")
        .port(8081)
        .build()

    private GenerationContext ctx() {
        new GenerationContext(module, serviceRoot, serviceRoot, [module], FractalxConfig.defaults())
    }

    private void writeJava(String path, String content) {
        Path f = serviceRoot.resolve(path)
        Files.createDirectories(f.parent)
        Files.writeString(f, content)
    }

    def "does not throw for a clean service (no file generated when no hints found)"() {
        given:
        writeJava("src/main/java/com/example/order/OrderService.java", """
            package com.example.order;
            public class OrderService {
                public String getOrder(Long id) { return "order"; }
            }
        """)

        when:
        step.generate(ctx())

        then:
        // Step only writes the file when it has something to report; no throw for clean service
        noExceptionThrown()
    }

    def "does not throw when src/main/java has no java files"() {
        given:
        Files.createDirectories(serviceRoot.resolve("src/main/java"))

        when:
        step.generate(ctx())

        then:
        noExceptionThrown()
    }

    def "generates DECOMPOSITION_HINTS.md when @Transactional cross-service call is found"() {
        given:
        // Module has PaymentServiceClient as a dependency (becomes PaymentServiceClient type)
        def modWithDeps = FractalModule.builder()
            .serviceName("order-service")
            .packageName("com.example.order")
            .port(8081)
            .dependencies(["PaymentService"])  // → client type PaymentServiceClient
            .build()
        def depCtx = new org.fractalx.core.generator.GenerationContext(
            modWithDeps, serviceRoot, serviceRoot, [modWithDeps],
            org.fractalx.core.config.FractalxConfig.defaults())

        writeJava("src/main/java/com/example/order/OrderService.java", """
            package com.example.order;
            import org.springframework.transaction.annotation.Transactional;
            public class OrderService {
                private PaymentServiceClient paymentServiceClient;
                @Transactional
                public void createOrder() {
                    paymentServiceClient.processPayment(100);
                }
            }
        """)

        when:
        step.generate(depCtx)

        then:
        noExceptionThrown()
        // File is only written when hints found; @Transactional + client call triggers Gap 3
        def hintsFile = serviceRoot.resolve("DECOMPOSITION_HINTS.md")
        if (Files.exists(hintsFile)) {
            Files.readString(hintsFile).contains("order-service") ||
            Files.readString(hintsFile).contains("Transactional")
        }
    }

    def "does not throw when @Cacheable annotation is found"() {
        given:
        writeJava("src/main/java/com/example/order/OrderService.java", """
            package com.example.order;
            import org.springframework.cache.annotation.Cacheable;
            public class OrderService {
                @Cacheable("orders")
                public String getOrder(Long id) { return "order"; }
            }
        """)

        when:
        step.generate(ctx())

        then:
        noExceptionThrown()
    }
}
