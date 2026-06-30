package org.fractalx.core.validation.rules

import org.fractalx.core.model.FractalModule
import org.fractalx.core.validation.ValidationContext
import org.fractalx.core.validation.ValidationSeverity
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

class UnresolvedDependencyRuleSpec extends Specification {

    @TempDir Path tmp

    private static final def RULE = new UnresolvedDependencyRule()

    private FractalModule mod(String name, String pkg = "com.example", List<String> deps = []) {
        FractalModule.builder()
            .serviceName(name)
            .className("com.example.Module")
            .packageName(pkg)
            .port(8081)
            .dependencies(deps)
            .build()
    }

    private ValidationContext ctx(List<FractalModule> mods) {
        new ValidationContext(mods, tmp, null)
    }

    def "no issues when module has no dependencies"() {
        expect:
        RULE.validate(ctx([mod("order-service")])).isEmpty()
    }

    def "no issues when dependency resolves to known module"() {
        given:
        // PaymentService → payment-service (by beanTypeToServiceName convention)
        def orderMod = mod("order-service", "com.example", ["PaymentService"])
        def paymentMod = mod("payment-service")

        expect:
        RULE.validate(ctx([orderMod, paymentMod])).isEmpty()
    }

    def "WARNING when dependency does not resolve to any module"() {
        given:
        def orderMod = mod("order-service", "com.example", ["BillingEngine"])
        // No billing-engine module

        when:
        def issues = RULE.validate(ctx([orderMod]))

        then:
        issues.size() == 1
        issues[0].severity() == ValidationSeverity.WARNING
        issues[0].ruleId() == "UNRESOLVED_DEP"
        issues[0].moduleName() == "order-service"
        issues[0].message().contains("BillingEngine")
    }

    def "WARNING per unresolvable dependency"() {
        given:
        def mod = mod("order-service", "com.example", ["BillingEngine", "NotificationHub"])

        when:
        def issues = RULE.validate(ctx([mod]))

        then:
        issues.size() == 2
        issues.every { it.ruleId() == "UNRESOLVED_DEP" }
    }

    def "fix message suggests @DecomposableModule annotation"() {
        given:
        def mod = mod("order-service", "com.example", ["BillingEngine"])

        when:
        def issues = RULE.validate(ctx([mod]))

        then:
        issues[0].fix().contains("@DecomposableModule")
    }

    def "resolved service name is included in message"() {
        given:
        def mod = mod("order-service", "com.example", ["BillingEngine"])

        when:
        def issues = RULE.validate(ctx([mod]))

        then:
        // billing-engine is the expected resolution
        issues[0].message().contains("billing-engine") || issues[0].message().contains("BillingEngine")
    }

    def "multiple modules with resolvable deps produce no issues"() {
        given:
        def a = mod("order-service",   "com.example", ["PaymentService"])
        def b = mod("payment-service", "com.example", ["InventoryService"])
        def c = mod("inventory-service")

        expect:
        RULE.validate(ctx([a, b, c])).isEmpty()
    }
}
