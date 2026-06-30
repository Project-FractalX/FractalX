package org.fractalx.core.naming

import org.fractalx.core.model.FractalModule
import spock.lang.Specification

class NamingValidatorSpec extends Specification {

    NameResolver resolver = new NameResolver(NamingConventions.defaults())
    NamingValidator validator = new NamingValidator(resolver)

    private FractalModule mod(String name, List<String> deps = []) {
        FractalModule.builder()
            .serviceName(name)
            .className("com.example.Module")
            .packageName("com.example")
            .port(8081)
            .dependencies(deps)
            .build()
    }

    def "validate does not throw when all deps are resolvable"() {
        given:
        def orderMod   = mod("order-service",   ["PaymentService"])
        def paymentMod = mod("payment-service")

        when:
        validator.validate([orderMod, paymentMod])

        then:
        noExceptionThrown()
    }

    def "validate does not throw when no dependencies"() {
        when:
        validator.validate([mod("order-service"), mod("payment-service")])

        then:
        noExceptionThrown()
    }

    def "validate does not throw on empty module list"() {
        when:
        validator.validate([])

        then:
        noExceptionThrown()
    }

    def "validate does not throw when dependency is unresolvable (logs warning only)"() {
        given:
        def m = mod("order-service", ["UnknownBillingEngine"])

        when:
        validator.validate([m])

        then:
        noExceptionThrown()
    }

    def "validate handles multiple modules with mixed resolvable and unresolvable deps"() {
        given:
        def a = mod("order-service",   ["PaymentService"])
        def b = mod("payment-service", ["GhostService"])  // unresolvable
        def c = mod("inventory-service")

        when:
        validator.validate([a, b, c])

        then:
        noExceptionThrown()
    }
}
