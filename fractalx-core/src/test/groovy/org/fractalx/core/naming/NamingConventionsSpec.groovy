package org.fractalx.core.naming

import spock.lang.Specification

class NamingConventionsSpec extends Specification {

    def "defaults() returns non-null instance"() {
        expect:
        NamingConventions.defaults() != null
    }

    def "compensationPrefixes includes expected defaults"() {
        given:
        def nc = NamingConventions.defaults()

        expect:
        nc.compensationPrefixes().containsAll(["cancel", "rollback", "undo", "revert", "release", "refund"])
    }

    def "infrastructureSuffixes includes expected defaults"() {
        given:
        def nc = NamingConventions.defaults()

        expect:
        nc.infrastructureSuffixes().containsAll(["Service", "Repository", "Controller"])
    }

    def "dependencyTypeSuffixes includes Service and Client"() {
        given:
        def nc = NamingConventions.defaults()

        expect:
        nc.dependencyTypeSuffixes().containsAll(["Service", "Client"])
    }

    def "aggregateClassSuffixes includes Service and Module"() {
        given:
        def nc = NamingConventions.defaults()

        expect:
        nc.aggregateClassSuffixes().containsAll(["Service", "Module"])
    }

    def "irregularPlurals contains expected mappings"() {
        given:
        def nc = NamingConventions.defaults()

        expect:
        nc.irregularPlurals()["children"] == "child"
        nc.irregularPlurals()["people"]   == "person"
        nc.irregularPlurals()["data"]     == "datum"
    }

    def "eventPublisherMethodNames contains publishEvent"() {
        expect:
        NamingConventions.defaults().eventPublisherMethodNames().contains("publishEvent")
    }

    def "caseInsensitiveServiceNames defaults to true"() {
        expect:
        NamingConventions.defaults().caseInsensitiveServiceNames()
    }

    def "can construct custom NamingConventions"() {
        given:
        def custom = new NamingConventions(
            ["cancel"],
            ["Service"],
            ["Service"],
            ["Service"],
            [:],
            ["publishEvent"],
            false
        )

        expect:
        custom.compensationPrefixes() == ["cancel"]
        !custom.caseInsensitiveServiceNames()
    }
}
