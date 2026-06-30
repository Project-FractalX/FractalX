package org.fractalx.core.naming

import spock.lang.Specification

class NameValidationExceptionSpec extends Specification {

    def "constructor stores message"() {
        given:
        def ex = new NameValidationException("dep 'FooBar' cannot be resolved")

        expect:
        ex.getMessage() == "dep 'FooBar' cannot be resolved"
    }

    def "is a RuntimeException"() {
        expect:
        new NameValidationException("x") instanceof RuntimeException
    }

    def "can be thrown and caught"() {
        when:
        throw new NameValidationException("test")

        then:
        def ex = thrown(NameValidationException)
        ex.getMessage() == "test"
    }
}
