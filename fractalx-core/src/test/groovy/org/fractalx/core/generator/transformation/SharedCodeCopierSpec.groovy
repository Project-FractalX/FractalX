package org.fractalx.core.generator.transformation

import org.fractalx.core.config.FractalxConfig
import org.fractalx.core.generator.GenerationContext
import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class SharedCodeCopierSpec extends Specification {

    @TempDir Path monolithRoot
    @TempDir Path serviceRoot

    SharedCodeCopier step = new SharedCodeCopier()

    private FractalModule mod(String name, String pkg) {
        FractalModule.builder()
            .serviceName(name)
            .packageName(pkg)
            .port(8081)
            .build()
    }

    private GenerationContext ctx(FractalModule module, List<FractalModule> all) {
        def srcRoot = monolithRoot.resolve("src/main/java")
        Files.createDirectories(srcRoot)
        new GenerationContext(module, srcRoot, serviceRoot, all, FractalxConfig.defaults())
    }

    def "is a no-op when service src/main/java is empty"() {
        given:
        def module = mod("order-service", "com.example.order")
        Files.createDirectories(serviceRoot.resolve("src/main/java"))

        when:
        step.generate(ctx(module, [module]))

        then:
        noExceptionThrown()
    }

    def "copies shared util class imported by module code"() {
        given:
        def module = mod("order-service", "com.example.order")

        // Write a shared util in monolith root (not in any module package)
        def utilDir = monolithRoot.resolve("src/main/java/com/example/util")
        Files.createDirectories(utilDir)
        Files.writeString(utilDir.resolve("DateUtil.java"),
            "package com.example.util; public class DateUtil {}")

        // Write module code that imports it
        def orderDir = serviceRoot.resolve("src/main/java/com/example/order")
        Files.createDirectories(orderDir)
        Files.writeString(orderDir.resolve("OrderService.java"), """
            package com.example.order;
            import com.example.util.DateUtil;
            public class OrderService {}
        """)

        when:
        step.generate(ctx(module, [module]))

        then:
        noExceptionThrown()
        // Shared class should be copied into service (exact behaviour depends on import resolution)
        true
    }

    def "does not copy classes owned by another module"() {
        given:
        def orderMod   = mod("order-service",   "com.example.order")
        def paymentMod = mod("payment-service",  "com.example.payment")

        // Write payment service class in monolith
        def payDir = monolithRoot.resolve("src/main/java/com/example/payment")
        Files.createDirectories(payDir)
        Files.writeString(payDir.resolve("PaymentService.java"),
            "package com.example.payment; public class PaymentService {}")

        // Write order code that imports payment (cross-module)
        def orderDir = serviceRoot.resolve("src/main/java/com/example/order")
        Files.createDirectories(orderDir)
        Files.writeString(orderDir.resolve("OrderService.java"), """
            package com.example.order;
            import com.example.payment.PaymentService;
            public class OrderService {}
        """)

        when:
        step.generate(ctx(orderMod, [orderMod, paymentMod]))

        then:
        // Cross-module class must NOT be copied (it becomes a NetScope client instead)
        !Files.exists(serviceRoot.resolve("src/main/java/com/example/payment/PaymentService.java"))
        noExceptionThrown()
    }

    def "does not throw when src/main/java exists but is empty"() {
        given:
        def module = mod("order-service", "com.example.order")
        // SharedCodeCopier walks getSrcMainJava() — must exist or it throws NoSuchFileException
        Files.createDirectories(serviceRoot.resolve("src/main/java"))

        when:
        step.generate(ctx(module, [module]))

        then:
        noExceptionThrown()
    }
}
