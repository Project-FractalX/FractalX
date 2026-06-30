package org.fractalx.core.graph

import spock.lang.Specification

class GraphModelSpec extends Specification {

    // ── EdgeKind ──────────────────────────────────────────────────────────────

    def "EdgeKind has expected values"() {
        expect:
        EdgeKind.METHOD_CALL      != null
        EdgeKind.FIELD_REFERENCE  != null
        EdgeKind.EXTENDS          != null
        EdgeKind.IMPLEMENTS       != null
        EdgeKind.CONSTRUCTOR_PARAM != null
        EdgeKind.ANNOTATION       != null
        EdgeKind.TYPE_REFERENCE   != null
        EdgeKind.values().length  == 7
    }

    // ── NodeKind ──────────────────────────────────────────────────────────────

    def "NodeKind has expected values"() {
        expect:
        NodeKind.CLASS      != null
        NodeKind.INTERFACE  != null
        NodeKind.ENUM       != null
        NodeKind.ANNOTATION != null
        NodeKind.RECORD     != null
        NodeKind.values().length == 5
    }

    // ── GraphEdge ────────────────────────────────────────────────────────────

    def "GraphEdge record fields are accessible"() {
        given:
        def edge = new GraphEdge("A", "B", EdgeKind.METHOD_CALL, "pay()")

        expect:
        edge.sourceNode() == "A"
        edge.targetNode() == "B"
        edge.kind()       == EdgeKind.METHOD_CALL
        edge.detail()     == "pay()"
    }

    // ── GraphNode ────────────────────────────────────────────────────────────

    def "GraphNode full constructor preserves all fields"() {
        given:
        def node = new GraphNode(
            "com.example.Foo", "Foo", NodeKind.CLASS,
            Set.of("Service"), Set.of("Runnable"),
            "Object", "com.example", null, []
        )

        expect:
        node.fqcn()                   == "com.example.Foo"
        node.simpleName()             == "Foo"
        node.kind()                   == NodeKind.CLASS
        node.annotations().contains("Service")
        node.implementedInterfaces().contains("Runnable")
        node.superclass()             == "Object"
        node.packageName()            == "com.example"
        node.methods().isEmpty()
    }

    def "GraphNode backward-compatible constructor without methods"() {
        given:
        def node = new GraphNode(
            "com.example.Bar", "Bar", NodeKind.INTERFACE,
            Set.of(), Set.of(), null, "com.example", null
        )

        expect:
        node.methods().isEmpty()
        node.kind() == NodeKind.INTERFACE
    }

    def "GraphNode annotations set is immutable"() {
        given:
        def node = new GraphNode(
            "Foo", "Foo", NodeKind.CLASS, Set.of("A"), Set.of(), null, "pkg", null, []
        )

        when:
        node.annotations().add("B")

        then:
        thrown(UnsupportedOperationException)
    }

    // ── MethodInfo ───────────────────────────────────────────────────────────

    def "MethodInfo full constructor captures all fields"() {
        given:
        def m = new MethodInfo(
            "processPayment",
            Set.of("Transactional"),
            "boolean",
            ["String", "Double"],
            Set.of("PAYMENT"),
            ["log.info", "payRepo.save"],
            ["claim": Set.of("customerId")]
        )

        expect:
        m.name()                          == "processPayment"
        m.annotations().contains("Transactional")
        m.returnType()                    == "boolean"
        m.parameterTypes()                == ["String", "Double"]
        m.stringLiterals().contains("PAYMENT")
        m.bodyMethodCalls().contains("log.info")
        m.callArgumentLiterals()["claim"].contains("customerId")
    }

    def "MethodInfo backward-compatible constructor without callArgumentLiterals"() {
        given:
        def m = new MethodInfo("doWork", Set.of(), "void", [], Set.of(), [])

        expect:
        m.callArgumentLiterals().isEmpty()
    }

    def "MethodInfo lists are immutable"() {
        given:
        def m = new MethodInfo("f", Set.of(), "void", ["String"], Set.of(), [])

        when:
        m.parameterTypes().add("extra")

        then:
        thrown(UnsupportedOperationException)
    }
}
