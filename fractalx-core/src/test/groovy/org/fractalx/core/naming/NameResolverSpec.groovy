package org.fractalx.core.naming

import spock.lang.Specification
import spock.lang.Unroll

class NameResolverSpec extends Specification {

    def resolver = NameResolver.defaults()

    // ── Issue #44: isDependencyType uses configurable suffixes ────────────────

    @Unroll
    def "isDependencyType('#typeName') == #expected (default conventions)"() {
        expect:
        resolver.isDependencyType(typeName) == expected

        where:
        typeName              || expected
        "PaymentService"      || true
        "OrderClient"         || true
        "Customer"            || false     // no matching suffix
        "OrderRepository"     || false     // Repository is NOT a dep suffix
        null                  || false
    }

    def "isDependencyType can be extended via custom NamingConventions"() {
        given:
        def customs = new NamingConventions(
            NamingConventions.defaults().compensationPrefixes(),
            NamingConventions.defaults().infrastructureSuffixes(),
            List.of("Service", "Client", "Gateway", "Bus", "Processor"),  // extended
            NamingConventions.defaults().aggregateClassSuffixes(),
            NamingConventions.defaults().irregularPlurals(),
            NamingConventions.defaults().eventPublisherMethodNames(),
            NamingConventions.defaults().caseInsensitiveServiceNames()
        )
        def customResolver = new NameResolver(customs)

        expect:
        customResolver.isDependencyType("PaymentGateway")
        customResolver.isDependencyType("EventBus")
        customResolver.isDependencyType("DataProcessor")
        customResolver.isDependencyType("OrderService")
        customResolver.isDependencyType("PaymentClient")

        and: "non-dep types are still rejected"
        !customResolver.isDependencyType("Customer")
        !customResolver.isDependencyType("OrderRepository")
    }

    // ── Issue #54: pluralize delegates to EnglishPluralizer ──────────────────

    @Unroll
    def "pluralize('#word') == '#expected'"() {
        expect:
        resolver.pluralize(word) == expected

        where:
        word        || expected
        "category"  || "categories"
        "address"   || "addresses"
        "order"     || "orders"
        "child"     || "children"
    }
}
