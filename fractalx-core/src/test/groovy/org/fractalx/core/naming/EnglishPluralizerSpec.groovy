package org.fractalx.core.naming

import spock.lang.Specification
import spock.lang.Unroll

class EnglishPluralizerSpec extends Specification {

    // ── Issue #54: proper English pluralization instead of appending 's' ──────

    def pluralizer = new EnglishPluralizer(NamingConventions.defaults().irregularPlurals())

    @Unroll
    def "pluralize('#singular') == '#expected'"() {
        expect:
        pluralizer.pluralize(singular) == expected

        where:
        singular    || expected
        "order"     || "orders"
        "category"  || "categories"     // consonant + y → ies
        "address"   || "addresses"      // sibilant → es
        "status"    || "statuses"       // sibilant → es (ending in s)
        "tax"       || "taxes"          // ending in x → es
        "batch"     || "batches"        // ending in ch → es
        "wish"      || "wishes"         // ending in sh → es
        "buzz"      || "buzzes"         // ending in z → es
        "user"      || "users"
        "child"     || "children"       // irregular
        "person"    || "people"         // irregular
        null        || null
        ""          || ""
    }

    @Unroll
    def "singularize('#plural') == '#expected'"() {
        expect:
        pluralizer.singularize(plural) == expected

        where:
        plural       || expected
        "orders"     || "order"
        "categories" || "category"
        "addresses"  || "address"
        "statuses"   || "status"
        "children"   || "child"
        "people"     || "person"
    }
}
