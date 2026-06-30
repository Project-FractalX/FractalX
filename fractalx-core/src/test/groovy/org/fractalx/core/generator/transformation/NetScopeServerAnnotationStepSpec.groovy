package org.fractalx.core.generator.transformation

import org.fractalx.core.config.FractalxConfig
import org.fractalx.core.generator.GenerationContext
import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class NetScopeServerAnnotationStepSpec extends Specification {

    @TempDir Path serviceRoot

    NetScopeServerAnnotationStep step = new NetScopeServerAnnotationStep()

    FractalModule orderModule = FractalModule.builder()
        .serviceName("order-service")
        .packageName("com.example.order")
        .className("com.example.order.OrderModule")
        .port(8081)
        .build()

    // payment-service depends on OrderService (in order-service)
    FractalModule paymentModule = FractalModule.builder()
        .serviceName("payment-service")
        .packageName("com.example.payment")
        .className("com.example.payment.PaymentModule")
        .port(8082)
        .dependencies(["OrderService"])
        .build()

    private Path writeSrc(String pkg, String cls, String code) {
        Path dir = serviceRoot.resolve("src/main/java").resolve(pkg.replace('.', '/'))
        Files.createDirectories(dir)
        def file = dir.resolve("${cls}.java")
        Files.writeString(file, code)
        file
    }

    private GenerationContext ctx(FractalModule module, List<FractalModule> allMods) {
        new GenerationContext(module, serviceRoot, serviceRoot, allMods, FractalxConfig.defaults())
    }

    def "no-op when no other module depends on this service"() {
        given:
        // payment-service has no dependents
        writeSrc("com.example.payment", "PaymentService", """
            package com.example.payment;
            import org.springframework.stereotype.Service;
            @Service public class PaymentService {
                public String pay() { return "paid"; }
            }
        """)
        def ctx = ctx(paymentModule, [paymentModule]) // only payment, no callers

        when:
        step.generate(ctx)

        then:
        def content = Files.readString(serviceRoot.resolve(
            "src/main/java/com/example/payment/PaymentService.java"))
        !content.contains("@NetworkPublic")
        noExceptionThrown()
    }

    def "adds @NetworkPublic to public methods when bean is exposed to other modules"() {
        given:
        writeSrc("com.example.order", "OrderService", """
            package com.example.order;
            import org.springframework.stereotype.Service;
            @Service public class OrderService {
                public String getOrder(Long id) { return "order-" + id; }
                private void internal() {}
            }
        """)
        // Context for order-service, with payment-service depending on it
        def ctx = ctx(orderModule, [orderModule, paymentModule])

        when:
        step.generate(ctx)

        then:
        def content = Files.readString(serviceRoot.resolve(
            "src/main/java/com/example/order/OrderService.java"))
        content.contains("@NetworkPublic")
    }

    def "does not annotate private or static methods"() {
        given:
        writeSrc("com.example.order", "OrderService", """
            package com.example.order;
            @Service public class OrderService {
                public String getOrder(Long id) { return "order-" + id; }
                private void doInternal() {}
                public static void staticHelper() {}
            }
        """)
        def ctx = ctx(orderModule, [orderModule, paymentModule])

        when:
        step.generate(ctx)

        then:
        def content = Files.readString(serviceRoot.resolve(
            "src/main/java/com/example/order/OrderService.java"))
        // Only the public non-static method should be annotated
        content.contains("@NetworkPublic")
    }

    def "adds NetworkPublic import to file"() {
        given:
        writeSrc("com.example.order", "OrderService", """
            package com.example.order;
            public class OrderService {
                public void doSomething() {}
            }
        """)
        def ctx = ctx(orderModule, [orderModule, paymentModule])

        when:
        step.generate(ctx)

        then:
        def content = Files.readString(serviceRoot.resolve(
            "src/main/java/com/example/order/OrderService.java"))
        content.contains("org.fractalx.netscope.server.annotation.NetworkPublic")
    }

    def "skips methods already annotated with @NetworkPublic"() {
        given:
        writeSrc("com.example.order", "OrderService", """
            package com.example.order;
            import org.fractalx.netscope.server.annotation.NetworkPublic;
            public class OrderService {
                @NetworkPublic
                public void alreadyAnnotated() {}
            }
        """)
        def ctx = ctx(orderModule, [orderModule, paymentModule])
        def before = Files.readString(serviceRoot.resolve(
            "src/main/java/com/example/order/OrderService.java"))

        when:
        step.generate(ctx)

        then:
        // Should not duplicate the annotation
        def after = Files.readString(serviceRoot.resolve(
            "src/main/java/com/example/order/OrderService.java"))
        after.count("@NetworkPublic") == before.count("@NetworkPublic")
    }
}
