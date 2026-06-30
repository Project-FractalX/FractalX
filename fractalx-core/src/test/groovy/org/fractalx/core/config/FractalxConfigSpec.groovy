package org.fractalx.core.config

import org.fractalx.core.naming.NamingConventions
import spock.lang.Specification

class FractalxConfigSpec extends Specification {

    def "defaults() returns non-null config"() {
        expect:
        FractalxConfig.defaults() != null
    }

    def "defaults() has expected registry URL"() {
        expect:
        FractalxConfig.defaults().registryUrl() == "http://localhost:8761"
    }

    def "defaults() has expected gateway port"() {
        expect:
        FractalxConfig.defaults().gatewayPort() == 9999
    }

    def "defaults() java version is 21"() {
        expect:
        FractalxConfig.defaults().javaVersion() == "21"
    }

    def "defaults() spring boot version is 3.2.0"() {
        expect:
        FractalxConfig.defaults().springBootVersion() == "3.2.0"
    }

    def "effectiveBasePackage() returns 'generated' when basePackage is null"() {
        given:
        def cfg = FractalxConfig.defaults() // basePackage is null

        expect:
        cfg.effectiveBasePackage() == "generated"
    }

    def "effectiveBasePackage() returns configured value when set"() {
        given:
        def cfg = FractalxConfig.defaults().withBasePackage("com.acme.generated")

        expect:
        cfg.effectiveBasePackage() == "com.acme.generated"
    }

    def "effectiveBasePackage() returns 'generated' when basePackage is blank"() {
        given:
        def cfg = FractalxConfig.defaults().withBasePackage("   ")

        expect:
        cfg.effectiveBasePackage() == "generated"
    }

    def "withBasePackage() returns new config with updated package, all other fields preserved"() {
        given:
        def original = FractalxConfig.defaults()
        def updated  = original.withBasePackage("com.acme.gen")

        expect:
        updated.effectiveBasePackage() == "com.acme.gen"
        updated.gatewayPort()          == original.gatewayPort()
        updated.registryUrl()          == original.registryUrl()
        updated.javaVersion()          == original.javaVersion()
    }

    def "portFor() returns module's own port when no override"() {
        given:
        def cfg = FractalxConfig.defaults() // empty serviceOverrides

        expect:
        cfg.portFor("order-service", 8081) == 8081
    }

    def "isTracingEnabled() returns true by default (no override)"() {
        expect:
        FractalxConfig.defaults().isTracingEnabled("any-service")
    }

    // ── FeatureFlags ──────────────────────────────────────────────────────────

    def "FeatureFlags.defaults() has gateway enabled"() {
        expect:
        FractalxConfig.FeatureFlags.defaults().gateway()
    }

    def "FeatureFlags.defaults() has registry enabled"() {
        expect:
        FractalxConfig.FeatureFlags.defaults().registry()
    }

    def "FeatureFlags.defaults() has logger enabled"() {
        expect:
        FractalxConfig.FeatureFlags.defaults().logger()
    }

    def "FeatureFlags.defaults() has docker enabled"() {
        expect:
        FractalxConfig.FeatureFlags.defaults().docker()
    }

    def "FeatureFlags.defaults() has distributedData enabled"() {
        expect:
        FractalxConfig.FeatureFlags.defaults().distributedData()
    }

    def "FeatureFlags.defaults() has admin DISABLED (deferred)"() {
        expect:
        !FractalxConfig.FeatureFlags.defaults().admin()
    }

    def "FeatureFlags.defaults() has saga DISABLED (deferred)"() {
        expect:
        !FractalxConfig.FeatureFlags.defaults().saga()
    }

    def "FeatureFlags.defaults() has observability DISABLED (deferred)"() {
        expect:
        !FractalxConfig.FeatureFlags.defaults().observability()
    }

    def "FeatureFlags.defaults() has resilience DISABLED (deferred)"() {
        expect:
        !FractalxConfig.FeatureFlags.defaults().resilience()
    }

    // ── DockerImages ──────────────────────────────────────────────────────────

    def "DockerImages.defaults() uses java 21 images"() {
        given:
        def img = FractalxConfig.DockerImages.defaults()

        expect:
        img.mavenBuildImage().contains("21")
        img.jreRuntimeImage().contains("21")
    }

    def "DockerImages.defaults(String) parameterises java version"() {
        given:
        def img = FractalxConfig.DockerImages.defaults("17")

        expect:
        img.mavenBuildImage().contains("17")
        img.jreRuntimeImage().contains("17")
    }

    // ── ResilienceDefaults ────────────────────────────────────────────────────

    def "ResilienceDefaults.defaults() has sensible failure rate threshold"() {
        expect:
        FractalxConfig.ResilienceDefaults.defaults().failureRateThreshold() == 50
    }

    def "ResilienceDefaults.defaults() has retry max attempts"() {
        expect:
        FractalxConfig.ResilienceDefaults.defaults().retryMaxAttempts() == 3
    }

    // ── ServiceOverride ────────────────────────────────────────────────────────

    def "ServiceOverride.hasDatasource() returns false when url is null"() {
        given:
        def ov = new FractalxConfig.ServiceOverride(0, true, null, null, null, null, null)

        expect:
        !ov.hasDatasource()
    }

    def "ServiceOverride.hasDatasource() returns true when url is set"() {
        given:
        def ov = new FractalxConfig.ServiceOverride(0, true, "jdbc:mysql://localhost/db", null, null, null, null)

        expect:
        ov.hasDatasource()
    }

    def "ServiceOverride.isH2() returns true for H2 jdbc URL"() {
        given:
        def ov = new FractalxConfig.ServiceOverride(0, true, "jdbc:h2:mem:testdb", null, null, null, null)

        expect:
        ov.isH2()
    }

    def "ServiceOverride.isH2() returns false for MySQL URL"() {
        given:
        def ov = new FractalxConfig.ServiceOverride(0, true, "jdbc:mysql://localhost/db", null, null, null, null)

        expect:
        !ov.isH2()
    }

    def "ServiceOverride.effectiveFlyway() uses override when set"() {
        given:
        def ov = new FractalxConfig.ServiceOverride(0, true, null, null, null, null, false)

        expect:
        !ov.effectiveFlyway(true) // override is false
    }

    def "ServiceOverride.effectiveFlyway() falls back to project default when null"() {
        given:
        def ov = new FractalxConfig.ServiceOverride(0, true, null, null, null, null, null)

        expect:
        ov.effectiveFlyway(true)  // falls back to true
        !ov.effectiveFlyway(false) // falls back to false
    }
}
