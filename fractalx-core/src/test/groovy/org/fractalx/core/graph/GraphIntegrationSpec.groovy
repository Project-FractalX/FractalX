package org.fractalx.core.graph

import org.fractalx.core.config.FractalxConfig
import org.fractalx.core.generator.GenerationContext
import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class GraphIntegrationSpec extends Specification {

    @TempDir
    Path sourceRoot

    @TempDir
    Path serviceRoot

    def "DependencyGraph is accessible via GenerationContext"() {
        given:
        writeJavaFile("com/example", "OrderService.java", """
            package com.example;
            public class OrderService {
                private PaymentService paymentService;
            }
        """)
        writeJavaFile("com/example", "PaymentService.java", """
            package com.example;
            public class PaymentService {}
        """)

        def graph = new GraphBuilder().build(sourceRoot)
        def module = FractalModule.builder()
                .className("com.example.OrderService")
                .packageName("com.example")
                .serviceName("order-service")
                .port(8080)
                .build()
        def config = FractalxConfig.defaults()

        when:
        def ctx = new GenerationContext(
                module, sourceRoot, serviceRoot, [module], config,
                [], null, null, graph)

        then:
        ctx.dependencyGraph != null
        ctx.dependencyGraph.allNodes().size() == 2
        ctx.dependencyGraph.node("com.example.OrderService").isPresent()
        ctx.dependencyGraph.node("com.example.PaymentService").isPresent()
    }

    def "GenerationContext without graph stays backward compatible"() {
        given:
        def module = FractalModule.builder()
                .className("com.example.OrderService")
                .packageName("com.example")
                .serviceName("order-service")
                .port(8080)
                .build()
        def config = FractalxConfig.defaults()

        when:
        def ctx = new GenerationContext(
                module, sourceRoot, serviceRoot, [module], config, [])

        then:
        ctx.dependencyGraph == null
    }

    def "GraphBridge identifies structural roles from builder-produced graph"() {
        given:
        writeJavaFile("com/example/web", "OrderController.java", """
            package com.example.web;
            @RestController
            @RequestMapping("/api/orders")
            public class OrderController {}
        """)
        writeJavaFile("com/example/service", "OrderService.java", """
            package com.example.service;
            @Service
            public class OrderService {}
        """)
        writeJavaFile("com/example/repo", "OrderRepository.java", """
            package com.example.repo;
            public interface OrderRepository extends JpaRepository {}
        """)
        writeJavaFile("com/example/filter", "AuthFilter.java", """
            package com.example.filter;
            public class AuthFilter extends OncePerRequestFilter {
                protected void doFilterInternal() {}
            }
        """)

        when:
        def graph = new GraphBuilder().build(sourceRoot)

        then: "structural classification without name heuristics"
        GraphBridge.entryPoints(graph).size() == 1
        GraphBridge.entryPoints(graph)[0].simpleName() == "OrderController"

        and:
        GraphBridge.dataAccessors(graph).size() == 1
        GraphBridge.dataAccessors(graph)[0].simpleName() == "OrderRepository"

        and:
        GraphBridge.pipelineClasses(graph).size() == 1
        GraphBridge.pipelineClasses(graph)[0].simpleName() == "AuthFilter"

        and: "cross-package edges detected"
        GraphBridge.crossPackageEdges(graph).isEmpty() // no field refs across packages in this setup
    }

    def "cross-package edges detected when services depend on each other"() {
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

        when:
        def graph = new GraphBuilder().build(sourceRoot)
        def crossEdges = GraphBridge.crossPackageEdges(graph)

        then:
        crossEdges.size() == 1
        crossEdges[0].sourceNode() == "com.example.order.OrderService"
        crossEdges[0].targetNode() == "com.example.payment.PaymentService"
        crossEdges[0].kind() == EdgeKind.FIELD_REFERENCE
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void writeJavaFile(String packagePath, String fileName, String content) {
        def dir = sourceRoot.resolve(packagePath)
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(fileName), content.stripIndent().trim())
    }
}
