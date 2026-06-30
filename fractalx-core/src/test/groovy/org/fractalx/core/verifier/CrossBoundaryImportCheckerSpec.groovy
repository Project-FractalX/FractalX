package org.fractalx.core.verifier

import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class CrossBoundaryImportCheckerSpec extends Specification {

    @TempDir Path outputDir

    CrossBoundaryImportChecker checker = new CrossBoundaryImportChecker()

    private FractalModule mod(String name, String pkg) {
        FractalModule.builder()
            .serviceName(name)
            .className("${pkg}.Module")
            .packageName(pkg)
            .port(8081)
            .build()
    }

    private void writeJava(String service, String pkg, String cls, String code) {
        Path dir = outputDir.resolve("${service}/src/main/java").resolve(pkg.replace('.', '/'))
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("${cls}.java"), code)
    }

    def "no violations when all services have no cross-boundary imports"() {
        given:
        def modules = [
            mod("order-service",   "com.example.order"),
            mod("payment-service", "com.example.payment")
        ]
        writeJava("order-service", "com.example.order", "OrderService", """
            package com.example.order;
            import org.springframework.stereotype.Service;
            @Service public class OrderService {}
        """)

        expect:
        checker.check(outputDir, modules).isEmpty()
    }

    def "detects cross-boundary import of peer service package"() {
        given:
        def modules = [
            mod("order-service",   "com.example.order"),
            mod("payment-service", "com.example.payment")
        ]
        // order-service imports from payment-service package — violation!
        writeJava("order-service", "com.example.order", "OrderService", """
            package com.example.order;
            import com.example.payment.PaymentService;
            public class OrderService {
                private PaymentService paymentService;
            }
        """)

        when:
        def violations = checker.check(outputDir, modules)

        then:
        !violations.isEmpty()
        violations[0].violatingService() == "order-service"
        violations[0].importedPackage().contains("com.example.payment")
    }

    def "no violation for Spring framework imports"() {
        given:
        def modules = [
            mod("order-service",   "com.example.order"),
            mod("payment-service", "com.example.payment")
        ]
        writeJava("order-service", "com.example.order", "OrderService", """
            package com.example.order;
            import org.springframework.stereotype.Service;
            import org.springframework.web.bind.annotation.RestController;
            public class OrderService {}
        """)

        expect:
        checker.check(outputDir, modules).isEmpty()
    }

    def "no violation for java.* imports"() {
        given:
        def modules = [mod("order-service", "com.example.order")]
        writeJava("order-service", "com.example.order", "OrderService", """
            package com.example.order;
            import java.util.List;
            import java.io.IOException;
            public class OrderService {}
        """)

        expect:
        checker.check(outputDir, modules).isEmpty()
    }

    def "no violation for fractalx runtime imports"() {
        given:
        def modules = [mod("order-service", "com.example.order")]
        writeJava("order-service", "com.example.order", "OrderService", """
            package com.example.order;
            import org.fractalx.runtime.GatewayPrincipal;
            public class OrderService {}
        """)

        expect:
        checker.check(outputDir, modules).isEmpty()
    }

    def "Violation.toString() contains [FAIL] and service info"() {
        given:
        def v = new CrossBoundaryImportChecker.Violation(
            "order-service", "com.example.payment.PaymentService", "payment-service",
            Path.of("OrderService.java"), 3)

        expect:
        v.toString().contains("[FAIL]")
        v.toString().contains("order-service")
        v.toString().contains("payment-service")
    }

    def "empty module list produces no violations"() {
        expect:
        checker.check(outputDir, []).isEmpty()
    }
}
