package org.fractalx.core

import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for ModuleAnalyzer — the core AST analysis engine that detects
 * @DecomposableModule classes and extracts their metadata.
 */
class ModuleAnalyzerSpec extends Specification {

    @TempDir
    Path sourceRoot

    ModuleAnalyzer analyzer = new ModuleAnalyzer()

    // ── helpers ───────────────────────────────────────────────────────────────

    private void writeJava(String relativePath, String content) {
        Path file = sourceRoot.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    // ── basic module detection ────────────────────────────────────────────────

    def "single @DecomposableModule is detected and its serviceName extracted"() {
        given:
        writeJava("com/example/order/OrderModule.java", """
            package com.example.order;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "order-service", port = 8081)
            public class OrderModule {}
        """)

        when:
        List<FractalModule> modules = analyzer.analyzeProject(sourceRoot)

        then:
        modules.size() == 1
        modules[0].serviceName == "order-service"
    }

    def "port is extracted correctly from @DecomposableModule"() {
        given:
        writeJava("com/example/order/OrderModule.java", """
            package com.example.order;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "order-service", port = 8081)
            public class OrderModule {}
        """)

        when:
        List<FractalModule> modules = analyzer.analyzeProject(sourceRoot)

        then:
        modules[0].port == 8081
    }

    def "packageName is extracted from the class package declaration"() {
        given:
        writeJava("com/example/order/OrderModule.java", """
            package com.example.order;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "order-service", port = 8081)
            public class OrderModule {}
        """)

        when:
        List<FractalModule> modules = analyzer.analyzeProject(sourceRoot)

        then:
        modules[0].packageName == "com.example.order"
    }

    def "hyphenated serviceName is preserved exactly"() {
        given:
        writeJava("com/example/inventory/InventoryModule.java", """
            package com.example.inventory;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "inventory-management-service", port = 8085)
            public class InventoryModule {}
        """)

        when:
        List<FractalModule> modules = analyzer.analyzeProject(sourceRoot)

        then:
        modules[0].serviceName == "inventory-management-service"
    }

    def "independentDeployment = false is extracted"() {
        given:
        writeJava("com/example/payment/PaymentModule.java", """
            package com.example.payment;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "payment-service", port = 8082, independentDeployment = false)
            public class PaymentModule {}
        """)

        when:
        List<FractalModule> modules = analyzer.analyzeProject(sourceRoot)

        then:
        !modules[0].independentDeployment
    }

    def "no @DecomposableModule returns an empty list"() {
        given:
        writeJava("com/example/plain/PlainService.java", """
            package com.example.plain;
            public class PlainService { }
        """)

        when:
        List<FractalModule> modules = analyzer.analyzeProject(sourceRoot)

        then:
        modules.isEmpty()
    }

    def "multiple @DecomposableModule classes are all detected"() {
        given:
        writeJava("com/example/order/OrderModule.java", """
            package com.example.order;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "order-service", port = 8081)
            public class OrderModule {}
        """)
        writeJava("com/example/payment/PaymentModule.java", """
            package com.example.payment;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "payment-service", port = 8082)
            public class PaymentModule {}
        """)

        when:
        List<FractalModule> modules = analyzer.analyzeProject(sourceRoot)

        then:
        modules.size() == 2
        modules*.serviceName.toSet() == ["order-service", "payment-service"].toSet()
    }

    // ── dependency detection ──────────────────────────────────────────────────

    def "field injection of a cross-module Service type is detected as dependency"() {
        given:
        // The referenced type must exist in source with @Service annotation
        writeJava("com/example/order/OrderService.java", """
            package com.example.order;
            import org.springframework.stereotype.Service;
            @Service
            public class OrderService {}
        """)
        writeJava("com/example/payment/PaymentModule.java", """
            package com.example.payment;
            import org.fractalx.annotations.DecomposableModule;
            import com.example.order.OrderService;
            @DecomposableModule(serviceName = "payment-service", port = 8082)
            public class PaymentModule {
                private OrderService orderService;
            }
        """)

        when:
        List<FractalModule> modules = analyzer.analyzeProject(sourceRoot)

        then:
        modules[0].dependencies.contains("OrderService")
    }

    def "field injection of a cross-module Component type is detected as dependency"() {
        given:
        // The referenced type must exist in source with @Component annotation
        writeJava("com/example/payment/PaymentClient.java", """
            package com.example.payment;
            import org.springframework.stereotype.Component;
            @Component
            public class PaymentClient {}
        """)
        writeJava("com/example/order/OrderModule.java", """
            package com.example.order;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "order-service", port = 8081)
            public class OrderModule {
                private PaymentClient paymentClient;
            }
        """)

        when:
        List<FractalModule> modules = analyzer.analyzeProject(sourceRoot)

        then:
        modules[0].dependencies.contains("PaymentClient")
    }

    def "constructor parameter of a cross-module type is detected as dependency"() {
        given:
        // The referenced type must exist in source with @Service annotation
        writeJava("com/example/payment/PaymentService.java", """
            package com.example.payment;
            import org.springframework.stereotype.Service;
            @Service
            public class PaymentService {}
        """)
        writeJava("com/example/order/OrderModule.java", """
            package com.example.order;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "order-service", port = 8081)
            public class OrderModule {
                public OrderModule(PaymentService paymentService) {}
            }
        """)

        when:
        List<FractalModule> modules = analyzer.analyzeProject(sourceRoot)

        then:
        modules[0].dependencies.contains("PaymentService")
    }

    def "fields without Spring stereotype annotation are NOT added to dependencies"() {
        given:
        writeJava("com/example/order/OrderModule.java", """
            package com.example.order;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "order-service", port = 8081)
            public class OrderModule {
                private String name;
                private OrderRepository orderRepository;
            }
        """)

        when:
        List<FractalModule> modules = analyzer.analyzeProject(sourceRoot)

        then:
        modules[0].dependencies.isEmpty()
    }

    def "module with no injected dependencies has empty dependency list"() {
        given:
        writeJava("com/example/order/OrderModule.java", """
            package com.example.order;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "order-service", port = 8081)
            public class OrderModule {}
        """)

        when:
        List<FractalModule> modules = analyzer.analyzeProject(sourceRoot)

        then:
        modules[0].dependencies.isEmpty()
    }

    // ── import collection ─────────────────────────────────────────────────────

    def "imports from other files in the same package are collected"() {
        given:
        writeJava("com/example/order/OrderModule.java", """
            package com.example.order;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "order-service", port = 8081)
            public class OrderModule {}
        """)
        writeJava("com/example/order/OrderService.java", """
            package com.example.order;
            import jakarta.persistence.Entity;
            import org.springframework.stereotype.Service;
            public class OrderService {}
        """)

        when:
        List<FractalModule> modules = analyzer.analyzeProject(sourceRoot)

        then:
        modules[0].detectedImports.contains("jakarta.persistence.Entity")
        modules[0].detectedImports.contains("org.springframework.stereotype.Service")
    }

    def "imports from a different module's package are NOT collected"() {
        given:
        writeJava("com/example/order/OrderModule.java", """
            package com.example.order;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "order-service", port = 8081)
            public class OrderModule {}
        """)
        writeJava("com/example/payment/PaymentService.java", """
            package com.example.payment;
            import com.some.unrelated.PaymentGateway;
            public class PaymentService {}
        """)

        when:
        List<FractalModule> modules = analyzer.analyzeProject(sourceRoot)

        then:
        !modules[0].detectedImports.contains("com.some.unrelated.PaymentGateway")
    }

    // ── edge cases ────────────────────────────────────────────────────────────

    def "empty source directory returns empty list"() {
        when:
        List<FractalModule> modules = analyzer.analyzeProject(sourceRoot)

        then:
        modules.isEmpty()
    }

    def "non-Java files in source tree are ignored"() {
        given:
        Files.writeString(sourceRoot.resolve("README.md"), "# Project")

        when:
        List<FractalModule> modules = analyzer.analyzeProject(sourceRoot)

        then:
        modules.isEmpty()
    }

    // ── explicit dependencies attribute ───────────────────────────────────────

    def "explicit dependencies={Foo.class, Bar.class} are extracted regardless of type name suffix"() {
        given:
        writeJava("com/example/order/OrderModule.java", """
            package com.example.order;
            import org.fractalx.annotations.DecomposableModule;
            import com.example.payment.PaymentProcessor;
            import com.example.inventory.InventoryManager;
            @DecomposableModule(serviceName = "order-service", port = 8081,
                                dependencies = {PaymentProcessor.class, InventoryManager.class})
            public class OrderModule {}
        """)

        when:
        List<FractalModule> modules = analyzer.analyzeProject(sourceRoot)

        then:
        modules.size() == 1
        modules[0].dependencies.toSet() == ["PaymentProcessor", "InventoryManager"].toSet()
    }

    def "single explicit dependency = Foo.class (no braces) is extracted"() {
        given:
        writeJava("com/example/order/OrderModule.java", """
            package com.example.order;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "order-service", port = 8081,
                                dependencies = PaymentProcessor.class)
            public class OrderModule {}
        """)

        when:
        List<FractalModule> modules = analyzer.analyzeProject(sourceRoot)

        then:
        modules[0].dependencies == ["PaymentProcessor"]
    }

    def "explicit dependencies take precedence over heuristic detection"() {
        given: "a module with an explicit dep that does not end in Service/Client, plus a field that does"
        writeJava("com/example/order/OrderModule.java", """
            package com.example.order;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "order-service", port = 8081,
                                dependencies = {PaymentProcessor.class})
            public class OrderModule {
                private NotADep notADep;
                private SomeService ignoredByExplicit;
            }
        """)

        when:
        List<FractalModule> modules = analyzer.analyzeProject(sourceRoot)

        then: "only the explicitly declared dep is present"
        modules[0].dependencies == ["PaymentProcessor"]
    }

    // ── package conflict validation ───────────────────────────────────────────

    def "two @DecomposableModule classes in the same package throw IllegalStateException"() {
        given:
        writeJava("com/example/shared/OrderModule.java", """
            package com.example.shared;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "order-service", port = 8081)
            public class OrderModule {}
        """)
        writeJava("com/example/shared/PaymentModule.java", """
            package com.example.shared;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "payment-service", port = 8082)
            public class PaymentModule {}
        """)

        when:
        analyzer.analyzeProject(sourceRoot)

        then:
        thrown(IllegalStateException)
    }

    def "two @DecomposableModule classes in distinct packages succeed"() {
        given:
        writeJava("com/example/order/OrderModule.java", """
            package com.example.order;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "order-service", port = 8081)
            public class OrderModule {}
        """)
        writeJava("com/example/payment/PaymentModule.java", """
            package com.example.payment;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "payment-service", port = 8082)
            public class PaymentModule {}
        """)

        when:
        List<FractalModule> modules = analyzer.analyzeProject(sourceRoot)

        then:
        noExceptionThrown()
        modules.size() == 2
    }
}
