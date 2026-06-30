package org.fractalx.core.verifier

import org.fractalx.core.model.FractalModule
import spock.lang.Specification

class ServiceGraphAnalyzerSpec extends Specification {

    ServiceGraphAnalyzer analyzer = new ServiceGraphAnalyzer()

    private FractalModule mod(String name, String cls, List<String> deps = []) {
        FractalModule.builder()
            .serviceName(name)
            .className(cls)
            .packageName("com.example")
            .port(8081)
            .dependencies(deps)
            .build()
    }

    def "empty module list produces empty report"() {
        when:
        def report = analyzer.analyse([])

        then:
        report.findings().isEmpty()
        report.cycles().isEmpty()
        !report.hasCycles()
    }

    def "single module with no deps produces no findings"() {
        given:
        def m = mod("order-service", "com.example.OrderModule")

        when:
        def report = analyzer.analyse([m])

        then:
        report.findings().isEmpty()
        !report.hasCycles()
    }

    def "linear A→B dependency: no cycle, fan-out 1 for A, fan-in 1 for B"() {
        given:
        // className must match dep string as simple name: "PaymentService" ← "com.example.payment.PaymentService"
        def a = mod("order-service",   "com.example.order.OrderModule",     ["PaymentService"])
        def b = mod("payment-service", "com.example.payment.PaymentService")

        when:
        def report = analyzer.analyse([a, b])

        then:
        !report.hasCycles()
        report.fanOut()["order-service"]   == 1
        report.fanIn()["payment-service"]  == 1
        report.fanOut()["payment-service"] == 0
    }

    def "detects cycle: A depends on B, B depends on A"() {
        given:
        def a = mod("order-service",   "com.example.OrderModule",   ["PaymentService"])
        def b = mod("payment-service", "com.example.PaymentService", ["OrderModule"])

        when:
        def report = analyzer.analyse([a, b])

        then:
        report.hasCycles()
        !report.cycles().isEmpty()
    }

    def "cycle finding is marked as critical"() {
        given:
        def a = mod("order-service",   "com.example.OrderModule",   ["PaymentService"])
        def b = mod("payment-service", "com.example.PaymentService", ["OrderModule"])

        when:
        def report = analyzer.analyse([a, b])

        then:
        report.findings().any { it.isCritical() }
    }

    def "orphan detected when module has no deps and no callers in a multi-module graph"() {
        given:
        // 'standalone-service' is an island in a 3-module system
        def a = mod("order-service",      "com.example.OrderModule",   ["PaymentService"])
        def b = mod("payment-service",    "com.example.PaymentService")
        def c = mod("standalone-service", "com.example.StandaloneModule")

        when:
        def report = analyzer.analyse([a, b, c])

        then:
        report.findings().any {
            it.kind() == ServiceGraphAnalyzer.FindingKind.ORPHAN &&
            it.service().contains("standalone-service")
        }
    }

    def "no orphan finding for single-module system"() {
        given:
        def a = mod("order-service", "com.example.OrderModule")

        when:
        def report = analyzer.analyse([a])

        then:
        report.findings().every { it.kind() != ServiceGraphAnalyzer.FindingKind.ORPHAN }
    }

    def "high fan-out detected when module calls more than 5 peers"() {
        given:
        def caller = FractalModule.builder()
            .serviceName("hub-service")
            .className("com.example.HubModule")
            .packageName("com.example")
            .port(8081)
            .dependencies(["AService", "BService", "CService", "DService", "EService", "FService"])
            .build()
        def peers = ["a","b","c","d","e","f"].collect { letter ->
            mod("${letter}-service", "com.example.${letter.capitalize()}Service")
        }

        when:
        def report = analyzer.analyse([caller] + peers)

        then:
        report.findings().any { it.kind() == ServiceGraphAnalyzer.FindingKind.HIGH_FAN_OUT }
    }

    def "fanOut and fanIn maps are populated for all modules"() {
        given:
        def a = mod("order-service",   "com.example.OrderModule",   ["PaymentService"])
        def b = mod("payment-service", "com.example.PaymentService")

        when:
        def report = analyzer.analyse([a, b])

        then:
        report.fanOut().containsKey("order-service")
        report.fanOut().containsKey("payment-service")
        report.fanIn().containsKey("order-service")
        report.fanIn().containsKey("payment-service")
    }
}
