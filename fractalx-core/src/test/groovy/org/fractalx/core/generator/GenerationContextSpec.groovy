package org.fractalx.core.generator

import org.fractalx.core.config.FractalxConfig
import org.fractalx.core.gateway.SecurityProfile
import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

class GenerationContextSpec extends Specification {

    @TempDir Path root

    FractalModule module = FractalModule.builder()
        .serviceName("order-service")
        .packageName("com.example.order")
        .port(8081)
        .build()

    def "minimal constructor sets all basic fields"() {
        given:
        def ctx = new GenerationContext(module, root, root, [module], FractalxConfig.defaults())

        expect:
        ctx.getModule()          == module
        ctx.getSourceRoot()      == root
        ctx.getServiceRoot()     == root
        ctx.getAllModules()       == [module]
        ctx.getFractalxConfig()  == FractalxConfig.defaults()
        ctx.getSecurityProfile() == null
        ctx.getDependencyGraph() == null
    }

    def "isSecurityEnabled() returns false when securityProfile is null"() {
        given:
        def ctx = new GenerationContext(module, root, root, [module], FractalxConfig.defaults())

        expect:
        !ctx.isSecurityEnabled()
    }

    def "isSecurityEnabled() returns false for NONE auth type"() {
        given:
        def profile = new SecurityProfile(
            SecurityProfile.AuthType.NONE,
            Set.of(),
            false, null, null, null, null, null, [], [])
        def ctx = new GenerationContext(module, root, root, [module],
            FractalxConfig.defaults(), profile)

        expect:
        !ctx.isSecurityEnabled()
    }

    def "isSecurityEnabled() returns true for BEARER_JWT auth type"() {
        given:
        def profile = new SecurityProfile(
            SecurityProfile.AuthType.BEARER_JWT,
            Set.of(SecurityProfile.AuthType.BEARER_JWT),
            true, null, null, "secret", null, null, [], [])
        def ctx = new GenerationContext(module, root, root, [module],
            FractalxConfig.defaults(), profile)

        expect:
        ctx.isSecurityEnabled()
    }

    def "getSrcMainJava() resolves to src/main/java under serviceRoot"() {
        given:
        def ctx = new GenerationContext(module, root, root, [module], FractalxConfig.defaults())

        expect:
        ctx.getSrcMainJava() == root.resolve("src/main/java")
    }

    def "getSrcMainResources() resolves to src/main/resources under serviceRoot"() {
        given:
        def ctx = new GenerationContext(module, root, root, [module], FractalxConfig.defaults())

        expect:
        ctx.getSrcMainResources() == root.resolve("src/main/resources")
    }

    def "basePackage() falls back to 'generated' when config has no base package"() {
        given:
        def ctx = new GenerationContext(module, root, root, [module], FractalxConfig.defaults())

        expect:
        ctx.basePackage() == "generated"
    }

    def "basePackage() returns configured base package"() {
        given:
        def cfg = FractalxConfig.defaults().withBasePackage("com.acme.generated")
        def ctx = new GenerationContext(module, root, root, [module], cfg)

        expect:
        ctx.basePackage() == "com.acme.generated"
    }

    def "servicePackage() removes hyphens and appends service name to base package"() {
        given:
        def cfg = FractalxConfig.defaults().withBasePackage("com.acme.generated")
        def ctx = new GenerationContext(module, root, root, [module], cfg)

        expect:
        ctx.servicePackage() == "com.acme.generated.orderservice"
    }

    def "allModules list is immutable"() {
        given:
        def ctx = new GenerationContext(module, root, root, [module], FractalxConfig.defaults())

        when:
        ctx.getAllModules().add(module)

        then:
        thrown(UnsupportedOperationException)
    }

    def "nameResolver is auto-created from config when not supplied"() {
        given:
        def ctx = new GenerationContext(module, root, root, [module], FractalxConfig.defaults())

        expect:
        ctx.getNameResolver() != null
    }
}
