package org.fractalx.core.util

import spock.lang.Specification
import spock.lang.Unroll

class SpringBootVersionUtilSpec extends Specification {

    // ── Issue #41: isBoot4Plus must parse multi-digit major versions ──────────

    @Unroll
    def "isBoot4Plus('#version') == #expected"() {
        expect:
        SpringBootVersionUtil.isBoot4Plus(version) == expected

        where:
        version          || expected
        "3.2.1"          || false
        "4.0.0"          || true
        "4.1.3"          || true
        "10.1.2"         || true       // two-digit major
        "3.10.0"         || false      // 3.10 is still major 3
        "4"              || true       // no dots
        "4.0.0-SNAPSHOT" || true       // snapshot qualifier
        "abc"            || false      // non-numeric
        ""               || false
        "  "             || false      // blank
        null             || false
        "0.9.0"          || false
        "5.0.0"          || true
    }
}
