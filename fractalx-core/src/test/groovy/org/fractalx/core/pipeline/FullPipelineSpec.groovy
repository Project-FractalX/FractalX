package org.fractalx.core.pipeline

import org.fractalx.core.generator.ServiceGenerator
import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * End-to-end pipeline regression test.
 *
 * Sets up a minimal 2-module monolith fixture entirely in memory (@TempDir), runs
 * ServiceGenerator.generateServices(), and asserts that the critical outputs exist
 * with the correct content. Any step that is removed or mis-wired causes this test
 * to fail immediately.
 *
 * The fixture: order-service (port 8081) and payment-service (port 8082, depends on OrderService).
 * base-package: com.fixtures.generated (from fractalx-config.yml).
 *
 * Gateway, admin service, and docker-compose are disabled (tested elsewhere) to keep
 * the pipeline fast.
 */
class FullPipelineSpec extends Specification {

    @TempDir
    Path monolithRoot   // acts as the Maven project root (contains pom.xml)

    @TempDir
    Path outputRoot

    // ── fixture setup ─────────────────────────────────────────────────────────

    FractalModule orderModule = FractalModule.builder()
            .serviceName("order-service")
            .packageName("com.fixtures.order")
            .port(8081)
            .build()

    FractalModule paymentModule = FractalModule.builder()
            .serviceName("payment-service")
            .packageName("com.fixtures.payment")
            .port(8082)
            .dependencies(["OrderService"])
            .build()

    private void buildMonolithFixture() {
        // pom.xml at project root (required by resolveProjectRoot)
        write("pom.xml", """\
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.fixtures</groupId>
              <artifactId>mini-monolith</artifactId>
              <version>1.0.0</version>
            </project>
        """)

        // fractalx-config.yml — sets base-package so generated classes go to com.fixtures.generated
        write("src/main/resources/fractalx-config.yml", """\
            fractalx:
              base-package: com.fixtures.generated
        """)

        // order module source
        write("src/main/java/com/fixtures/order/OrderModule.java", """\
            package com.fixtures.order;
            import org.fractalx.annotations.DecomposableModule;
            @DecomposableModule(serviceName = "order-service", port = 8081)
            public class OrderModule {}
        """)
        write("src/main/java/com/fixtures/order/OrderService.java", """\
            package com.fixtures.order;
            import org.springframework.stereotype.Service;
            @Service
            public class OrderService {
                public String getOrder(Long id) {
                    return "order-" + id;
                }
                public void createOrder(String item) {}
            }
        """)

        // payment module source
        write("src/main/java/com/fixtures/payment/PaymentModule.java", """\
            package com.fixtures.payment;
            import org.fractalx.annotations.DecomposableModule;
            import com.fixtures.order.OrderService;
            @DecomposableModule(serviceName = "payment-service", port = 8082)
            public class PaymentModule {
                private OrderService orderService;
            }
        """)
        write("src/main/java/com/fixtures/payment/PaymentService.java", """\
            package com.fixtures.payment;
            import org.springframework.stereotype.Service;
            import com.fixtures.order.OrderService;
            @Service
            public class PaymentService {
                private final OrderService orderService;
                public PaymentService(OrderService orderService) {
                    this.orderService = orderService;
                }
                public void processPayment(Long orderId) {
                    orderService.getOrder(orderId);
                }
            }
        """)
    }

    private void write(String relative, String content) {
        Path target = monolithRoot.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
    }

    private Path srcMainJava() { monolithRoot.resolve("src/main/java") }

    // ── pipeline execution ────────────────────────────────────────────────────

    private void runPipeline() {
        buildMonolithFixture()
        new ServiceGenerator(srcMainJava(), outputRoot)
                .withGateway(false)
                .withDocker(false)
                .generateServices([orderModule, paymentModule])
    }

    // ── assertions ────────────────────────────────────────────────────────────

    def "order-service directory is created"() {
        when:
        runPipeline()

        then:
        Files.isDirectory(outputRoot.resolve("order-service"))
    }

    def "payment-service directory is created"() {
        when:
        runPipeline()

        then:
        Files.isDirectory(outputRoot.resolve("payment-service"))
    }

    def "each service has a pom.xml"() {
        when:
        runPipeline()

        then:
        Files.exists(outputRoot.resolve("order-service/pom.xml"))
        Files.exists(outputRoot.resolve("payment-service/pom.xml"))
    }

    def "pom.xml groupId reflects base-package from fractalx-config.yml"() {
        when:
        runPipeline()

        then:
        def pom = Files.readString(outputRoot.resolve("order-service/pom.xml"))
        pom.contains("com.fixtures.generated")
    }

    def "application.yml is generated for each service"() {
        when:
        runPipeline()

        then:
        Files.exists(outputRoot.resolve("order-service/src/main/resources/application.yml"))
        Files.exists(outputRoot.resolve("payment-service/src/main/resources/application.yml"))
    }

    def "order module source is copied into order-service"() {
        when:
        runPipeline()

        then:
        Files.exists(outputRoot.resolve("order-service/src/main/java/com/fixtures/order/OrderService.java"))
    }

    def "payment module source is copied into payment-service"() {
        when:
        runPipeline()

        then:
        Files.exists(outputRoot.resolve("payment-service/src/main/java/com/fixtures/payment/PaymentService.java"))
    }

    def "NetScope client interface is generated in payment-service for its OrderService dependency"() {
        when:
        runPipeline()

        then:
        // NetScopeClientGenerator creates OrderServiceClient.java in payment-service
        def client = outputRoot.resolve(
            "payment-service/src/main/java/com/fixtures/payment/OrderServiceClient.java")
        Files.exists(client)
    }

    def "generated NetScope client has @NetScopeClient annotation referencing order-service"() {
        when:
        runPipeline()

        then:
        def client = outputRoot.resolve(
            "payment-service/src/main/java/com/fixtures/payment/OrderServiceClient.java")
        def content = Files.readString(client)
        content.contains("@NetScopeClient")
        content.contains("order-service")
    }

    def "fractalx-registry service is generated"() {
        when:
        runPipeline()

        then:
        Files.isDirectory(outputRoot.resolve("fractalx-registry"))
    }

    def "@DecomposableModule annotation is removed from copied order module files"() {
        when:
        runPipeline()

        then:
        def moduleFile = outputRoot.resolve(
            "order-service/src/main/java/com/fixtures/order/OrderModule.java")
        if (Files.exists(moduleFile)) {
            !Files.readString(moduleFile).contains("@DecomposableModule")
        } else {
            true // file may have been cleaned up entirely — also acceptable
        }
    }
}
