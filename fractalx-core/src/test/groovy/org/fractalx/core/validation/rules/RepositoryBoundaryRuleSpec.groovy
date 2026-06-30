package org.fractalx.core.validation.rules

import org.fractalx.core.model.FractalModule
import org.fractalx.core.validation.ValidationContext
import org.fractalx.core.validation.ValidationSeverity
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class RepositoryBoundaryRuleSpec extends Specification {

    @TempDir Path sourceRoot

    private static final def RULE = new RepositoryBoundaryRule()

    private FractalModule mod(String name, String pkg) {
        FractalModule.builder()
            .serviceName(name)
            .className("${pkg}.Module")
            .packageName(pkg)
            .port(8081)
            .build()
    }

    private ValidationContext ctx(List<FractalModule> mods) {
        new ValidationContext(mods, sourceRoot, null)
    }

    private void writeSource(String path, String content) {
        Path f = sourceRoot.resolve(path)
        Files.createDirectories(f.parent)
        Files.writeString(f, content)
    }

    def "no issues when there are no cross-module repository injections"() {
        given:
        def orderMod = mod("order-service", "com.example.order")
        writeSource("com/example/order/OrderService.java", """
            package com.example.order;
            import org.springframework.stereotype.Service;
            @Service
            public class OrderService {
                private final OrderRepository orderRepo;
                public OrderService(OrderRepository repo) { this.orderRepo = repo; }
            }
        """)

        expect:
        RULE.validate(ctx([orderMod])).isEmpty()
    }

    def "no issues when source directory is empty"() {
        given:
        def m = mod("order-service", "com.example.order")
        Files.createDirectories(sourceRoot.resolve("com/example/order"))

        expect:
        RULE.validate(ctx([m])).isEmpty()
    }

    def "ERROR when order-service injects PaymentRepository owned by payment-service"() {
        given:
        def orderMod   = mod("order-service",   "com.example.order")
        def paymentMod = mod("payment-service",  "com.example.payment")

        // payment-service owns PaymentRepository
        writeSource("com/example/payment/PaymentRepository.java", """
            package com.example.payment;
            import org.springframework.data.jpa.repository.JpaRepository;
            public interface PaymentRepository extends JpaRepository<Payment, Long> {}
        """)

        // order-service injects it — cross-boundary violation
        writeSource("com/example/order/OrderService.java", """
            package com.example.order;
            import com.example.payment.PaymentRepository;
            import org.springframework.stereotype.Service;
            @Service
            public class OrderService {
                private final PaymentRepository paymentRepo;
                public OrderService(PaymentRepository paymentRepo) {
                    this.paymentRepo = paymentRepo;
                }
            }
        """)

        when:
        def issues = RULE.validate(ctx([orderMod, paymentMod]))

        then:
        issues.size() >= 1
        issues.any {
            it.severity() == ValidationSeverity.ERROR
            && it.ruleId() == "REPO_BOUNDARY"
        }
    }
}
