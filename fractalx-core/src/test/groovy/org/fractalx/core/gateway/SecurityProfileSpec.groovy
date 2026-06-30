package org.fractalx.core.gateway

import spock.lang.Specification

class SecurityProfileSpec extends Specification {

    def "SecurityProfile.none() returns NONE auth type with security disabled"() {
        when:
        def p = SecurityProfile.none()

        then:
        p.authType() == SecurityProfile.AuthType.NONE
        !p.securityEnabled()
        p.jwtSecret() == null
        p.routeRules().isEmpty()
        p.publicPaths().isEmpty()
    }

    def "hasRouteRules() returns false for none profile"() {
        expect:
        !SecurityProfile.none().hasRouteRules()
    }

    def "hasRouteRules() returns true when route rules list is non-empty"() {
        given:
        def rule = new SecurityProfile.RouteSecurityRule("/api/**", ["ROLE_USER"], false)
        def p = new SecurityProfile(
            SecurityProfile.AuthType.BEARER_JWT,
            Set.of(SecurityProfile.AuthType.BEARER_JWT),
            true, null, null, "secret", null, null,
            [rule], []
        )

        expect:
        p.hasRouteRules()
    }

    def "isAnyAuthDetected() returns false for NONE type with empty authTypes"() {
        expect:
        !SecurityProfile.none().isAnyAuthDetected()
    }

    def "isAnyAuthDetected() returns true when authType is not NONE"() {
        given:
        def p = new SecurityProfile(
            SecurityProfile.AuthType.BASIC,
            Set.of(), true, null, null, null, "user", "pass",
            [], []
        )

        expect:
        p.isAnyAuthDetected()
    }

    def "AuthType enum has OAUTH2, BEARER_JWT, BASIC, NONE"() {
        expect:
        SecurityProfile.AuthType.values().toList().containsAll(
            [SecurityProfile.AuthType.OAUTH2, SecurityProfile.AuthType.BEARER_JWT,
             SecurityProfile.AuthType.BASIC,  SecurityProfile.AuthType.NONE]
        )
    }

    def "RouteSecurityRule fields are accessible"() {
        given:
        def rule = new SecurityProfile.RouteSecurityRule("/admin/**", ["ROLE_ADMIN"], false)

        expect:
        rule.pathPattern() == "/admin/**"
        rule.roles() == ["ROLE_ADMIN"]
        !rule.permitAll()
    }

    def "RouteSecurityRule with permitAll"() {
        given:
        def rule = new SecurityProfile.RouteSecurityRule("/health", [], true)

        expect:
        rule.permitAll()
        rule.roles().isEmpty()
    }
}
