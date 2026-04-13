package org.fractalx.core.util

import spock.lang.Specification
import spock.lang.Unroll

class NamingUtilsSpec extends Specification {

    // ── Issue #46: decapitalize must be null/empty safe ───────────────────────

    @Unroll
    def "decapitalize('#input') == '#expected'"() {
        expect:
        NamingUtils.decapitalize(input) == expected

        where:
        input            || expected
        "OrderService"   || "orderService"
        "A"              || "a"
        "a"              || "a"
        "ABC"            || "aBC"
        ""               || ""
        null             || null
        "paymentClient"  || "paymentClient"
        "X"              || "x"
    }

    @Unroll
    def "capitalize('#input') == '#expected'"() {
        expect:
        NamingUtils.capitalize(input) == expected

        where:
        input            || expected
        "orderService"   || "OrderService"
        "a"              || "A"
        "A"              || "A"
        ""               || ""
        null             || null
        "x"              || "X"
    }
}
