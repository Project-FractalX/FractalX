package org.fractalx.core.model

import spock.lang.Specification

class CrossModuleCallSpec extends Specification {

    def "5-arg constructor defaults to empty thrown exceptions"() {
        given:
        def call = new CrossModuleCall("PaymentService", "payment-service", "processPayment", "boolean", ["String amount"])

        expect:
        call.getThrownExceptions().isEmpty()
        call.getTargetBeanType()    == "PaymentService"
        call.getTargetServiceName() == "payment-service"
        call.getMethodName()        == "processPayment"
        call.getReturnType()        == "boolean"
        call.getParameters()        == ["String amount"]
    }

    def "6-arg constructor stores thrown exceptions"() {
        given:
        def call = new CrossModuleCall(
            "InvService", "inventory-service", "reserve", "void",
            ["Long productId", "int qty"], ["IOException"]
        )

        expect:
        call.getThrownExceptions() == ["IOException"]
    }

    def "parameters list is immutable"() {
        given:
        def call = new CrossModuleCall("S", "s-service", "m", "void", ["String x"])

        when:
        call.getParameters().add("extra")

        then:
        thrown(UnsupportedOperationException)
    }

    def "toString contains service and method info"() {
        given:
        def call = new CrossModuleCall("PayService", "pay-service", "pay", "boolean", [])

        expect:
        call.toString().contains("pay-service")
        call.toString().contains("pay")
    }
}
