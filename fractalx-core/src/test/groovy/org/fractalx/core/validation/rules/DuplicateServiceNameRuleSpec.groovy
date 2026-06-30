package org.fractalx.core.validation.rules

import org.fractalx.core.model.FractalModule
import org.fractalx.core.validation.ValidationContext
import org.fractalx.core.validation.ValidationSeverity
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

class DuplicateServiceNameRuleSpec extends Specification {

    @TempDir Path tmp

    private static final def RULE = new DuplicateServiceNameRule()

    private FractalModule mod(String name, String cls) {
        FractalModule.builder()
            .serviceName(name)
            .className(cls)
            .packageName("com.example")
            .port(8081)
            .build()
    }

    private ValidationContext ctx(List<FractalModule> mods) {
        new ValidationContext(mods, tmp, null)
    }

    def "no issues when all service names are unique"() {
        given:
        def mods = [
            mod("order-service",   "com.example.OrderModule"),
            mod("payment-service", "com.example.PaymentModule")
        ]

        expect:
        RULE.validate(ctx(mods)).isEmpty()
    }

    def "single module produces no issues"() {
        expect:
        RULE.validate(ctx([mod("order-service", "com.example.OrderModule")])).isEmpty()
    }

    def "empty module list produces no issues"() {
        expect:
        RULE.validate(ctx([])).isEmpty()
    }

    def "duplicate service name produces ERROR with DUP_SERVICE_NAME"() {
        given:
        def mods = [
            mod("order-service", "com.example.OrderModuleA"),
            mod("order-service", "com.example.OrderModuleB")
        ]

        when:
        def issues = RULE.validate(ctx(mods))

        then:
        issues.size() == 1
        issues[0].severity() == ValidationSeverity.ERROR
        issues[0].ruleId() == "DUP_SERVICE_NAME"
        issues[0].moduleName() == "order-service"
        issues[0].message().contains("order-service")
        issues[0].message().contains("2")
    }

    def "duplicate includes all involved class names in message"() {
        given:
        def mods = [
            mod("order-service", "com.example.OrderModuleA"),
            mod("order-service", "com.example.OrderModuleB")
        ]

        when:
        def issues = RULE.validate(ctx(mods))

        then:
        issues[0].message().contains("OrderModuleA") || issues[0].message().contains("com.example.OrderModuleA")
    }

    def "three modules with same name produces one ERROR"() {
        given:
        def mods = [
            mod("order-service", "com.example.A"),
            mod("order-service", "com.example.B"),
            mod("order-service", "com.example.C")
        ]

        when:
        def issues = RULE.validate(ctx(mods))

        then:
        issues.size() == 1
        issues[0].message().contains("3")
    }

    def "two sets of duplicates produce two ERRORs"() {
        given:
        def mods = [
            mod("order-service",   "com.example.A"),
            mod("order-service",   "com.example.B"),
            mod("payment-service", "com.example.C"),
            mod("payment-service", "com.example.D")
        ]

        when:
        def issues = RULE.validate(ctx(mods))

        then:
        issues.size() == 2
    }

    def "fix message mentions @DecomposableModule"() {
        given:
        def mods = [
            mod("svc", "com.example.A"),
            mod("svc", "com.example.B")
        ]

        when:
        def issues = RULE.validate(ctx(mods))

        then:
        issues[0].fix().contains("@DecomposableModule")
    }
}
