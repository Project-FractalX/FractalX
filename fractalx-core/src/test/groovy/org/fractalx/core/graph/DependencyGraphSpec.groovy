package org.fractalx.core.graph

import spock.lang.Specification

class DependencyGraphSpec extends Specification {

    // ── Node management ────────────────────────────────────────────────────

    def "empty graph has no nodes"() {
        given:
        def graph = DependencyGraph.builder().build()

        expect:
        graph.allNodes().isEmpty()
        graph.allEdges().isEmpty()
    }

    def "can add and retrieve a node by FQCN"() {
        given:
        def node = new GraphNode("com.example.OrderService", "OrderService",
                NodeKind.CLASS, Set.of("RestController"), Set.of(), "java.lang.Object",
                "com.example", null)
        def graph = DependencyGraph.builder()
                .addNode(node)
                .build()

        expect:
        graph.node("com.example.OrderService").isPresent()
        graph.node("com.example.OrderService").get().simpleName() == "OrderService"
        graph.node("com.example.DoesNotExist").isEmpty()
    }

    def "allNodes returns all added nodes"() {
        given:
        def nodeA = new GraphNode("com.example.A", "A", NodeKind.CLASS,
                Set.of(), Set.of(), null, "com.example", null)
        def nodeB = new GraphNode("com.example.B", "B", NodeKind.INTERFACE,
                Set.of(), Set.of(), null, "com.example", null)
        def graph = DependencyGraph.builder()
                .addNode(nodeA)
                .addNode(nodeB)
                .build()

        expect:
        graph.allNodes().size() == 2
    }

    // ── Edge management ────────────────────────────────────────────────────

    def "can add and retrieve edges"() {
        given:
        def nodeA = new GraphNode("com.example.A", "A", NodeKind.CLASS,
                Set.of(), Set.of(), null, "com.example", null)
        def nodeB = new GraphNode("com.example.B", "B", NodeKind.CLASS,
                Set.of(), Set.of(), null, "com.example", null)
        def edge = new GraphEdge("com.example.A", "com.example.B",
                EdgeKind.FIELD_REFERENCE, "orderService")
        def graph = DependencyGraph.builder()
                .addNode(nodeA)
                .addNode(nodeB)
                .addEdge(edge)
                .build()

        expect:
        graph.allEdges().size() == 1
        graph.allEdges()[0].kind() == EdgeKind.FIELD_REFERENCE
    }

    // ── Query: edgesFrom / edgesTo ─────────────────────────────────────────

    def "edgesFrom returns outgoing edges for a node"() {
        given:
        def graph = threeNodeGraph()

        when:
        def outgoing = graph.edgesFrom("com.example.A")

        then:
        outgoing.size() == 2
        outgoing.every { it.sourceNode() == "com.example.A" }
    }

    def "edgesTo returns incoming edges for a node"() {
        given:
        def graph = threeNodeGraph()

        when:
        def incoming = graph.edgesTo("com.example.B")

        then:
        incoming.size() == 1
        incoming[0].sourceNode() == "com.example.A"
    }

    // ── Query: calleesOf / callersOf ───────────────────────────────────────

    def "calleesOf returns nodes called by a given node via METHOD_CALL edges"() {
        given:
        def graph = graphWithCalls()

        when:
        def callees = graph.calleesOf("com.example.Controller")

        then:
        callees.size() == 1
        callees[0].fqcn() == "com.example.Service"
    }

    def "callersOf returns nodes that call a given node"() {
        given:
        def graph = graphWithCalls()

        when:
        def callers = graph.callersOf("com.example.Service")

        then:
        callers.size() == 1
        callers[0].fqcn() == "com.example.Controller"
    }

    // ── Query: fieldDependenciesOf ─────────────────────────────────────────

    def "fieldDependenciesOf returns nodes referenced via FIELD_REFERENCE"() {
        given:
        def graph = threeNodeGraph()

        when:
        def deps = graph.fieldDependenciesOf("com.example.A")

        then:
        deps.size() == 1
        deps[0].fqcn() == "com.example.B"
    }

    // ── Query: implementorsOf ──────────────────────────────────────────────

    def "implementorsOf returns classes implementing an interface"() {
        given:
        def iface = new GraphNode("com.example.Repo", "Repo", NodeKind.INTERFACE,
                Set.of(), Set.of(), null, "com.example", null)
        def impl = new GraphNode("com.example.RepoImpl", "RepoImpl", NodeKind.CLASS,
                Set.of(), Set.of("com.example.Repo"), null, "com.example", null)
        def graph = DependencyGraph.builder()
                .addNode(iface)
                .addNode(impl)
                .addEdge(new GraphEdge("com.example.RepoImpl", "com.example.Repo",
                        EdgeKind.IMPLEMENTS, null))
                .build()

        when:
        def implementors = graph.implementorsOf("com.example.Repo")

        then:
        implementors.size() == 1
        implementors[0].fqcn() == "com.example.RepoImpl"
    }

    // ── Query: subclassesOf ────────────────────────────────────────────────

    def "subclassesOf returns classes extending a given class"() {
        given:
        def parent = new GraphNode("com.example.Base", "Base", NodeKind.CLASS,
                Set.of(), Set.of(), null, "com.example", null)
        def child = new GraphNode("com.example.Child", "Child", NodeKind.CLASS,
                Set.of(), Set.of(), "com.example.Base", "com.example", null)
        def graph = DependencyGraph.builder()
                .addNode(parent)
                .addNode(child)
                .addEdge(new GraphEdge("com.example.Child", "com.example.Base",
                        EdgeKind.EXTENDS, null))
                .build()

        when:
        def subs = graph.subclassesOf("com.example.Base")

        then:
        subs.size() == 1
        subs[0].fqcn() == "com.example.Child"
    }

    // ── Query: nodesMatching ───────────────────────────────────────────────

    def "nodesMatching filters by predicate"() {
        given:
        def graph = threeNodeGraph()

        when:
        def interfaces = graph.nodesMatching { it.kind() == NodeKind.INTERFACE }

        then:
        interfaces.isEmpty() // all CLASS in threeNodeGraph

        when:
        def all = graph.nodesMatching { it.packageName() == "com.example" }

        then:
        all.size() == 3
    }

    def "nodesWithAnnotation returns nodes annotated with a given annotation"() {
        given:
        def annotated = new GraphNode("com.example.Ctrl", "Ctrl", NodeKind.CLASS,
                Set.of("RestController", "RequestMapping"), Set.of(), null,
                "com.example", null)
        def plain = new GraphNode("com.example.Util", "Util", NodeKind.CLASS,
                Set.of(), Set.of(), null, "com.example", null)
        def graph = DependencyGraph.builder()
                .addNode(annotated)
                .addNode(plain)
                .build()

        when:
        def found = graph.nodesWithAnnotation("RestController")

        then:
        found.size() == 1
        found[0].fqcn() == "com.example.Ctrl"
    }

    // ── Query: nodesByPackage ──────────────────────────────────────────────

    def "nodesByPackage groups nodes by their package"() {
        given:
        def a = new GraphNode("com.example.order.OrderService", "OrderService",
                NodeKind.CLASS, Set.of(), Set.of(), null, "com.example.order", null)
        def b = new GraphNode("com.example.payment.PayService", "PayService",
                NodeKind.CLASS, Set.of(), Set.of(), null, "com.example.payment", null)
        def graph = DependencyGraph.builder()
                .addNode(a)
                .addNode(b)
                .build()

        when:
        def byPkg = graph.nodesByPackage()

        then:
        byPkg.size() == 2
        byPkg["com.example.order"].size() == 1
        byPkg["com.example.payment"].size() == 1
    }

    // ── Method-level queries (Phase 2) ──────────────────────────────────

    def "nodesWithMethodAnnotation finds nodes with annotated methods"() {
        given:
        def methods = [new MethodInfo("myBean", Set.of("Bean"), "Object", [], Set.of(), [])]
        def a = new GraphNode("com.example.Config", "Config", NodeKind.CLASS,
                Set.of(), Set.of(), null, "com.example", null, methods)
        def b = new GraphNode("com.example.Service", "Service", NodeKind.CLASS,
                Set.of(), Set.of(), null, "com.example", null, [])
        def graph = DependencyGraph.builder().addNode(a).addNode(b).build()

        expect:
        graph.nodesWithMethodAnnotation("Bean").size() == 1
        graph.nodesWithMethodAnnotation("Bean")[0].fqcn() == "com.example.Config"
        graph.nodesWithMethodAnnotation("NonExistent").isEmpty()
    }

    def "nodesContainingLiteral finds nodes with matching string literals in methods"() {
        given:
        def methods = [new MethodInfo("filter", Set.of(), "void", [], Set.of("Bearer ", "Authorization"), [])]
        def a = new GraphNode("com.example.JwtFilter", "JwtFilter", NodeKind.CLASS,
                Set.of(), Set.of(), null, "com.example", null, methods)
        def b = new GraphNode("com.example.Other", "Other", NodeKind.CLASS,
                Set.of(), Set.of(), null, "com.example", null, [])
        def graph = DependencyGraph.builder().addNode(a).addNode(b).build()

        expect:
        graph.nodesContainingLiteral(s -> s.contains("Bearer")).size() == 1
        graph.nodesContainingLiteral(s -> s.contains("Bearer"))[0].fqcn() == "com.example.JwtFilter"
        graph.nodesContainingLiteral(s -> s.contains("nope")).isEmpty()
    }

    def "nodesWithMethodCall finds nodes containing specific method calls"() {
        given:
        def methods = [new MethodInfo("configure", Set.of(), "void", [], Set.of(), ["oauth2ResourceServer", "httpBasic"])]
        def a = new GraphNode("com.example.SecurityConfig", "SecurityConfig", NodeKind.CLASS,
                Set.of(), Set.of(), null, "com.example", null, methods)
        def b = new GraphNode("com.example.Other", "Other", NodeKind.CLASS,
                Set.of(), Set.of(), null, "com.example", null, [])
        def graph = DependencyGraph.builder().addNode(a).addNode(b).build()

        expect:
        graph.nodesWithMethodCall("oauth2ResourceServer").size() == 1
        graph.nodesWithMethodCall("oauth2ResourceServer")[0].fqcn() == "com.example.SecurityConfig"
        graph.nodesWithMethodCall("httpBasic").size() == 1
        graph.nodesWithMethodCall("nonExistent").isEmpty()
    }

    def "nodesWithMethodReturning finds nodes with methods returning specific types"() {
        given:
        def methods = [new MethodInfo("chain", Set.of("Bean"), "SecurityFilterChain", [], Set.of(), [])]
        def a = new GraphNode("com.example.Config", "Config", NodeKind.CLASS,
                Set.of(), Set.of(), null, "com.example", null, methods)
        def b = new GraphNode("com.example.Service", "Service", NodeKind.CLASS,
                Set.of(), Set.of(), null, "com.example", null,
                [new MethodInfo("process", Set.of(), "String", [], Set.of(), [])])
        def graph = DependencyGraph.builder().addNode(a).addNode(b).build()

        expect:
        graph.nodesWithMethodReturning("SecurityFilterChain").size() == 1
        graph.nodesWithMethodReturning("SecurityFilterChain")[0].fqcn() == "com.example.Config"
        graph.nodesWithMethodReturning("void").isEmpty()
    }

    def "callArgumentsFor extracts string arguments for specific call sites"() {
        given:
        def methods = [new MethodInfo("generateToken", Set.of(), "String", ["User"], Set.of(),
                ["claim", "subject", "compact"],
                ["claim": Set.of("customerId", "roles"), "subject": Set.of()])]
        def a = new GraphNode("com.example.JwtUtil", "JwtUtil", NodeKind.CLASS,
                Set.of("Component"), Set.of(), null, "com.example", null, methods)
        def graph = DependencyGraph.builder().addNode(a).build()

        expect:
        graph.callArgumentsFor({ true }, "claim") == Set.of("customerId", "roles")
        graph.callArgumentsFor({ true }, "subject").isEmpty()
        graph.callArgumentsFor({ true }, "nonExistent").isEmpty()
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private DependencyGraph threeNodeGraph() {
        def nodeA = new GraphNode("com.example.A", "A", NodeKind.CLASS,
                Set.of(), Set.of(), null, "com.example", null)
        def nodeB = new GraphNode("com.example.B", "B", NodeKind.CLASS,
                Set.of(), Set.of(), null, "com.example", null)
        def nodeC = new GraphNode("com.example.C", "C", NodeKind.CLASS,
                Set.of(), Set.of(), null, "com.example", null)
        DependencyGraph.builder()
                .addNode(nodeA)
                .addNode(nodeB)
                .addNode(nodeC)
                .addEdge(new GraphEdge("com.example.A", "com.example.B",
                        EdgeKind.FIELD_REFERENCE, "bField"))
                .addEdge(new GraphEdge("com.example.A", "com.example.C",
                        EdgeKind.EXTENDS, null))
                .build()
    }

    private DependencyGraph graphWithCalls() {
        def ctrl = new GraphNode("com.example.Controller", "Controller", NodeKind.CLASS,
                Set.of("RestController"), Set.of(), null, "com.example", null)
        def svc = new GraphNode("com.example.Service", "Service", NodeKind.CLASS,
                Set.of("Service"), Set.of(), null, "com.example", null)
        def repo = new GraphNode("com.example.Repository", "Repository", NodeKind.INTERFACE,
                Set.of(), Set.of(), null, "com.example", null)
        DependencyGraph.builder()
                .addNode(ctrl)
                .addNode(svc)
                .addNode(repo)
                .addEdge(new GraphEdge("com.example.Controller", "com.example.Service",
                        EdgeKind.METHOD_CALL, "processOrder"))
                .addEdge(new GraphEdge("com.example.Service", "com.example.Repository",
                        EdgeKind.METHOD_CALL, "findById"))
                .build()
    }
}
