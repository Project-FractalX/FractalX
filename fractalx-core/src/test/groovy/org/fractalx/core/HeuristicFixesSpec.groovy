package org.fractalx.core

import org.fractalx.core.naming.NameResolver
import org.fractalx.core.naming.NamingConventions
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Cross-cutting tests for heuristic fixes that span multiple classes.
 * Each test maps to a specific issue from #40.
 */
class HeuristicFixesSpec extends Specification {

    // ── Issue #51: import filter must NOT block user code under io.* ─────────

    @Unroll
    def "isWellKnownType('#type') == #expected"() {
        given:
        def method = ReflectiveModuleAnalyzer.getDeclaredMethod("isWellKnownType", String)
        method.setAccessible(true)

        expect:
        method.invoke(null, type) == expected

        where:
        type                                      || expected
        "String"                                  || true
        "java.util.List"                          || true
        "jakarta.persistence.Entity"              || true
        "org.springframework.web.bind.annotation" || true
        "com.fasterxml.jackson.databind.JsonNode" || true
        // Specific io.* frameworks — should be filtered
        "io.micrometer.core.instrument.Counter"   || true
        "io.grpc.ManagedChannel"                  || true
        "io.netty.channel.Channel"                || true
        "io.projectreactor.core.publisher.Mono"   || true
        "io.opentelemetry.api.trace.Span"         || true
        // User code under io.* — must NOT be filtered
        "io.mycompany.payments.PaymentService"    || false
        "io.fractalx.custom.MyService"            || false
        "io.acme.orders.OrderService"             || false
    }

    // ── Issue #44: isDependencyType uses configurable NamingConventions ──────

    def "isDependencyType default accepts Service and Client suffixes"() {
        given:
        def resolver = NameResolver.defaults()

        expect:
        resolver.isDependencyType("OrderService")
        resolver.isDependencyType("PaymentClient")

        and: "non-dep types are rejected"
        !resolver.isDependencyType("Customer")
        !resolver.isDependencyType("OrderRepository")
        !resolver.isDependencyType("OrderEntity")
    }

    def "isDependencyType can be extended to accept Gateway, Bus, Processor"() {
        given:
        def customs = new NamingConventions(
            NamingConventions.defaults().compensationPrefixes(),
            NamingConventions.defaults().infrastructureSuffixes(),
            List.of("Service", "Client", "Gateway", "Bus", "Processor"),
            NamingConventions.defaults().aggregateClassSuffixes(),
            NamingConventions.defaults().irregularPlurals(),
            NamingConventions.defaults().eventPublisherMethodNames(),
            NamingConventions.defaults().caseInsensitiveServiceNames()
        )
        def resolver = new NameResolver(customs)

        expect:
        resolver.isDependencyType("PaymentGateway")
        resolver.isDependencyType("EventBus")
        resolver.isDependencyType("DataProcessor")
    }
}
