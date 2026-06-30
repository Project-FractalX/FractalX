package org.fractalx.core.verifier

import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class TransactionBoundaryAnalyzerSpec extends Specification {

    @TempDir Path outputDir

    TransactionBoundaryAnalyzer analyzer = new TransactionBoundaryAnalyzer()

    private FractalModule mod(String name) {
        FractalModule.builder()
            .serviceName(name)
            .packageName("com.example")
            .port(8081)
            .build()
    }

    private void writeJava(String service, String pkg, String cls, String code) {
        Path dir = outputDir.resolve("${service}/src/main/java").resolve(pkg.replace('.', '/'))
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("${cls}.java"), code)
    }

    def "no violations when service directory does not exist"() {
        given:
        def modules = [mod("order-service")]

        expect:
        analyzer.analyse(outputDir, modules).isEmpty()
    }

    def "no violations when there are no @Transactional methods"() {
        given:
        def modules = [mod("order-service")]
        writeJava("order-service", "com.example.order", "OrderService", """
            package com.example.order;
            public class OrderService {
                public void process() { }
            }
        """)

        expect:
        analyzer.analyse(outputDir, modules).isEmpty()
    }

    def "detects @Transactional method calling a @NetScopeClient field"() {
        given:
        def modules = [mod("order-service")]
        // First write a NetScopeClient interface
        writeJava("order-service", "com.example.order", "PaymentServiceClient", """
            package com.example.order;
            import org.fractalx.netscope.client.annotation.NetScopeClient;
            @NetScopeClient(serviceName = "payment-service", port = 18082)
            public interface PaymentServiceClient {
                String processPayment(double amount);
            }
        """)
        // Then write a @Transactional service that calls it
        writeJava("order-service", "com.example.order", "OrderService", """
            package com.example.order;
            import org.springframework.transaction.annotation.Transactional;
            public class OrderService {
                private PaymentServiceClient paymentClient;
                @Transactional
                public void createOrder(double amount) {
                    paymentClient.processPayment(amount);
                }
            }
        """)

        when:
        def violations = analyzer.analyse(outputDir, modules)

        then:
        !violations.isEmpty()
        violations[0].serviceName() == "order-service"
        violations[0].methodName() == "createOrder"
    }

    def "no violations when @Transactional method does not call any NetScope client"() {
        given:
        def modules = [mod("order-service")]
        writeJava("order-service", "com.example.order", "OrderService", """
            package com.example.order;
            import org.springframework.transaction.annotation.Transactional;
            public class OrderService {
                private OrderRepository repo;
                @Transactional
                public void save(Object entity) {
                    repo.save(entity);
                }
            }
        """)

        when:
        def violations = analyzer.analyse(outputDir, modules)

        then:
        violations.isEmpty()
    }

    def "TransactionViolation.toString() contains service name and method name"() {
        given:
        def v = new TransactionBoundaryAnalyzer.TransactionViolation(
            "order-service", Path.of("OrderService.java"), "createOrder", "paymentClient")

        expect:
        v.toString().contains("order-service")
        v.toString().contains("createOrder")
    }
}
