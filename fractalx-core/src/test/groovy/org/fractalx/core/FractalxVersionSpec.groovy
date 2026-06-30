package org.fractalx.core

import spock.lang.Specification

class FractalxVersionSpec extends Specification {

    def "get() returns non-null version string"() {
        expect:
        FractalxVersion.get() != null
    }

    def "release() strips -SNAPSHOT suffix"() {
        when:
        def v = FractalxVersion.release()

        then:
        !v.contains("-SNAPSHOT")
    }

    def "get() and release() agree on non-SNAPSHOT base"() {
        given:
        def full = FractalxVersion.get()
        def rel  = FractalxVersion.release()

        expect:
        rel == full.replace("-SNAPSHOT", "")
    }
}
