package org.fractalx.core.graph

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class GraphBridgeSpec extends Specification {

    @TempDir
    Path sourceRoot

    def "classifies entry points — classes with HTTP mapping annotations"() {
        given:
        writeJavaFile("com/example", "OrderController.java", """
            package com.example;
            @RestController
            @RequestMapping("/api/orders")
            public class OrderController {
                private OrderService orderService;
            }
        """)
        writeJavaFile("com/example", "OrderService.java", """
            package com.example;
            @Service
            public class OrderService {}
        """)
        def graph = new GraphBuilder().build(sourceRoot)

        when:
        def entryPoints = GraphBridge.entryPoints(graph)

        then:
        entryPoints.size() == 1
        entryPoints[0].fqcn() == "com.example.OrderController"
    }

    def "classifies data accessors — classes implementing repository interfaces"() {
        given:
        writeJavaFile("com/example", "OrderRepository.java", """
            package com.example;
            public interface OrderRepository extends JpaRepository {}
        """)
        writeJavaFile("com/example", "OrderService.java", """
            package com.example;
            public class OrderService {}
        """)
        def graph = new GraphBuilder().build(sourceRoot)

        when:
        def repos = GraphBridge.dataAccessors(graph)

        then:
        repos.size() == 1
        repos[0].fqcn() == "com.example.OrderRepository"
    }

    def "identifies cross-boundary dependencies between packages"() {
        given:
        writeJavaFile("com/example/order", "OrderService.java", """
            package com.example.order;
            import com.example.payment.PaymentService;
            public class OrderService {
                private PaymentService paymentService;
            }
        """)
        writeJavaFile("com/example/payment", "PaymentService.java", """
            package com.example.payment;
            public class PaymentService {}
        """)
        def graph = new GraphBuilder().build(sourceRoot)

        when:
        def crossBoundary = GraphBridge.crossPackageEdges(graph)

        then:
        crossBoundary.size() == 1
        crossBoundary[0].sourceNode() == "com.example.order.OrderService"
        crossBoundary[0].targetNode() == "com.example.payment.PaymentService"
    }

    def "identifies pipeline classes — filters/interceptors in request chain"() {
        given:
        writeJavaFile("com/example", "AuthFilter.java", """
            package com.example;
            public class AuthFilter extends OncePerRequestFilter {
                protected void doFilterInternal() {}
            }
        """)
        writeJavaFile("com/example", "OrderService.java", """
            package com.example;
            public class OrderService {}
        """)
        def graph = new GraphBuilder().build(sourceRoot)

        when:
        def pipeline = GraphBridge.pipelineClasses(graph)

        then:
        pipeline.size() == 1
        pipeline[0].fqcn() == "com.example.AuthFilter"
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void writeJavaFile(String packagePath, String fileName, String content) {
        def dir = sourceRoot.resolve(packagePath)
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(fileName), content.stripIndent().trim())
    }
}
