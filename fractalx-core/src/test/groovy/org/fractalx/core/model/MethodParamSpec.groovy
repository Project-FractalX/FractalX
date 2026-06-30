package org.fractalx.core.model

import spock.lang.Specification

class MethodParamSpec extends Specification {

    def "constructor stores type and name"() {
        given:
        def p = new MethodParam("String", "customerId")

        expect:
        p.getType() == "String"
        p.getName() == "customerId"
    }

    def "toString returns 'type name'"() {
        expect:
        new MethodParam("Long", "orderId").toString() == "Long orderId"
    }
}
