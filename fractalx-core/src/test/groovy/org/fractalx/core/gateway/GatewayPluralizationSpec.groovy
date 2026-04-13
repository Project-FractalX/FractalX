package org.fractalx.core.gateway

import org.fractalx.core.naming.EnglishPluralizer
import org.fractalx.core.naming.NamingConventions
import spock.lang.Specification
import spock.lang.Unroll

class GatewayPluralizationSpec extends Specification {

    // ── Issue #54: route path pluralization must handle irregular plurals ─────

    def pluralizer = new EnglishPluralizer(NamingConventions.defaults().irregularPlurals())

    @Unroll
    def "pluralizePathPrefix('/api/#segment') produces correct plural"() {
        given:
        def prefix = "/api/" + segment

        when:
        int lastSlash = prefix.lastIndexOf('/')
        def base = prefix.substring(0, lastSlash + 1)
        def seg = prefix.substring(lastSlash + 1)
        def result = base + pluralizer.pluralize(seg)

        then:
        result == "/api/" + expected

        where:
        segment     || expected
        "order"     || "orders"
        "category"  || "categories"     // NOT "categorys"
        "address"   || "addresses"      // NOT "addresss"
        "status"    || "statuses"       // NOT "statuss"
        "country"   || "countries"      // NOT "countrys"
        "child"     || "children"       // irregular
        "person"    || "people"         // irregular
        "user"      || "users"
    }
}
