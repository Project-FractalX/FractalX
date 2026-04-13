package org.fractalx.core

import spock.lang.Specification
import spock.lang.TempDir

import javax.tools.ToolProvider
import java.nio.file.Files
import java.nio.file.Path

class ReflectiveModuleAnalyzerSpec extends Specification {

    @TempDir Path tempDir
    ReflectiveModuleAnalyzer analyzer = new ReflectiveModuleAnalyzer()

    // ── Annotation attribute extraction ──────────────────────────────────────

    def "extracts serviceName and port from annotation"() {
        given:
        compile("com/example/order/OrderModule.java", """
            package com.example.order;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "order-service", port = 8081)
            public class OrderModule {}
        """)

        when:
        def modules = analyzer.analyzeProject(tempDir, Path.of("/nonexistent"), cl())

        then:
        modules.size() == 1
        with(modules[0]) {
            serviceName == "order-service"
            port == 8081
            className == "com.example.order.OrderModule"
            packageName == "com.example.order"
            independentDeployment == true
        }
    }

    def "reads independentDeployment = false and ownedSchemas"() {
        given:
        compile("com/example/pay/PaymentModule.java", """
            package com.example.pay;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "payment-service", port = 8082,
                                independentDeployment = false, ownedSchemas = {"payment_db"})
            public class PaymentModule {}
        """)

        when:
        def modules = analyzer.analyzeProject(tempDir, Path.of("/nonexistent"), cl())

        then:
        modules[0].independentDeployment == false
        modules[0].ownedSchemas == ["payment_db"]
    }

    // ── Dependency detection ──────────────────────────────────────────────────

    def "reads explicit dependencies from annotation"() {
        given:
        compile("com/example/shared/PaymentService.java", """
            package com.example.shared;
            public class PaymentService {}
        """)
        compile("com/example/order/OrderModule.java", """
            package com.example.order;
            import org.fractalx.annotations.DecomposableModule;
            import com.example.shared.PaymentService;
            @DecomposableModule(serviceName = "order-service", port = 8081,
                                dependencies = {PaymentService.class})
            public class OrderModule {}
        """)

        when:
        def modules = analyzer.analyzeProject(tempDir, Path.of("/nonexistent"), cl())

        then:
        def order = modules.find { it.serviceName == "order-service" }
        order.dependencies == ["PaymentService"]
    }

    def "infers dependencies from largest constructor — Lombok-expanded"() {
        given:
        compile("com/example/shared/PaymentService.java", """
            package com.example.shared;
            public class PaymentService {}
        """)
        compile("com/example/order/OrderModule.java", """
            package com.example.order;
            import org.fractalx.annotations.DecomposableModule;
            import com.example.shared.PaymentService;
            @DecomposableModule(serviceName = "order-service", port = 8081)
            public class OrderModule {
                private final PaymentService paymentService;
                public OrderModule(PaymentService paymentService) {
                    this.paymentService = paymentService;
                }
            }
        """)

        when:
        def modules = analyzer.analyzeProject(tempDir, Path.of("/nonexistent"), cl())

        then:
        def order = modules.find { it.serviceName == "order-service" }
        order.dependencies == ["PaymentService"]
    }

    def "infers dependencies from sibling Service fields when module class is a bare marker"() {
        given: "Module class has no fields — deps live in sibling @Service class"
        // Compile a stub @Service annotation so reflection can check it
        compile("org/springframework/stereotype/Service.java", """
            package org.springframework.stereotype;
            import java.lang.annotation.*;
            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Service {}
        """)
        compile("com/example/shared/CustomerService.java", """
            package com.example.shared;
            @org.springframework.stereotype.Service
            public class CustomerService {}
        """)
        compile("com/example/notification/NotificationService.java", """
            package com.example.notification;
            import com.example.shared.CustomerService;
            public class NotificationService {
                private CustomerService customerService;
            }
        """)
        compile("com/example/notification/NotificationModule.java", """
            package com.example.notification;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "notification-service", port = 8085)
            public class NotificationModule {}
        """)

        when:
        def modules = analyzer.analyzeProject(tempDir, Path.of("/nonexistent"), cl())

        then:
        def notif = modules.find { it.serviceName == "notification-service" }
        notif.dependencies == ["CustomerService"]
    }

    def "excludes local types from inferred dependencies"() {
        given: "PaymentService defined in the SAME package as the module"
        compile("com/example/order/PaymentService.java", """
            package com.example.order;
            public class PaymentService {}
        """)
        compile("com/example/order/OrderModule.java", """
            package com.example.order;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "order-service", port = 8081)
            public class OrderModule {
                private final PaymentService paymentService;
                public OrderModule(PaymentService paymentService) {
                    this.paymentService = paymentService;
                }
            }
        """)

        when:
        def modules = analyzer.analyzeProject(tempDir, Path.of("/nonexistent"), cl())

        then:
        def order = modules.find { it.serviceName == "order-service" }
        order.dependencies.isEmpty()   // PaymentService is local — not a cross-module dep
    }

    // ── Class filtering ───────────────────────────────────────────────────────

    def "skips inner and anonymous classes (those containing \$)"() {
        given:
        compile("com/example/order/OrderModule.java", """
            package com.example.order;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "order-service", port = 8081)
            public class OrderModule {
                private static class Inner {}
            }
        """)

        when:
        def modules = analyzer.analyzeProject(tempDir, Path.of("/nonexistent"), cl())

        then:
        modules.size() == 1   // Inner.class (OrderModule\$Inner.class) skipped
    }

    def "skips classes without @DecomposableModule"() {
        given:
        compile("com/example/order/OrderService.java", """
            package com.example.order;
            public class OrderService {}
        """)

        when:
        def modules = analyzer.analyzeProject(tempDir, Path.of("/nonexistent"), cl())

        then:
        modules.isEmpty()
    }

    // ── Package conflict validation ───────────────────────────────────────────

    def "throws IllegalStateException when two modules share the same package"() {
        given:
        compile("com/example/order/ModuleA.java", """
            package com.example.order;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "order-a", port = 8081)
            public class ModuleA {}
        """)
        compile("com/example/order/ModuleB.java", """
            package com.example.order;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "order-b", port = 8082)
            public class ModuleB {}
        """)

        when:
        analyzer.analyzeProject(tempDir, Path.of("/nonexistent"), cl())

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("com.example.order")
    }

    // ── Empty directory ───────────────────────────────────────────────────────

    def "returns empty list when classesDir has no annotated classes"() {
        when:
        def modules = analyzer.analyzeProject(tempDir, Path.of("/nonexistent"), cl())

        then:
        modules.isEmpty()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Compiles source into tempDir, mirroring package structure. */
    private void compile(String relPath, String source) {
        def sourceFile = tempDir.resolve(relPath)
        Files.createDirectories(sourceFile.parent)
        Files.writeString(sourceFile, source.stripIndent())

        def compiler = ToolProvider.getSystemJavaCompiler()
        assert compiler != null : "No Java compiler available (run with a JDK, not JRE)"

        // Include tempDir so previously compiled classes in the same test are resolvable
        def cp = System.getProperty("java.class.path") + File.pathSeparator + tempDir.toString()
        int result = compiler.run(null, null, null,
                "-classpath", cp,
                "-d", tempDir.toString(),
                sourceFile.toString())
        assert result == 0 : "Compilation failed for $relPath"
    }

    /** Returns the current test classloader (has fractalx-annotations on the classpath). */
    private ClassLoader cl() { getClass().getClassLoader() }
}
