package org.fractalx.core.generator.transformation

import org.fractalx.core.config.FractalxConfig
import org.fractalx.core.gateway.SecurityProfile
import org.fractalx.core.generator.GenerationContext
import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class ServiceSecurityStepSpec extends Specification {

    @TempDir Path serviceRoot

    ServiceSecurityStep step = new ServiceSecurityStep()

    FractalModule module = FractalModule.builder()
        .serviceName("order-service")
        .packageName("com.example.order")
        .port(8081)
        .build()

    FractalxConfig cfg = FractalxConfig.defaults().withBasePackage("com.example.generated")

    private GenerationContext securedCtx() {
        def profile = new SecurityProfile(
            SecurityProfile.AuthType.BEARER_JWT,
            Set.of(SecurityProfile.AuthType.BEARER_JWT),
            true, null, null, "secret", null, null, [], [])
        new GenerationContext(module, serviceRoot, serviceRoot, [module], cfg, profile)
    }

    private GenerationContext unsecuredCtx() {
        new GenerationContext(module, serviceRoot, serviceRoot, [module], cfg)
    }

    def "is a no-op when security is not enabled"() {
        given:
        def ctx = unsecuredCtx()

        when:
        step.generate(ctx)

        then:
        noExceptionThrown()
        // No security files generated
        !Files.exists(serviceRoot.resolve(
            "src/main/java/com/example/generated/orderservice/GatewayAuthHeaderFilter.java"))
    }

    def "generates GatewayAuthHeaderFilter.java when security is enabled"() {
        given:
        def ctx = securedCtx()

        when:
        step.generate(ctx)

        then:
        Files.exists(serviceRoot.resolve(
            "src/main/java/com/example/generated/orderservice/GatewayAuthHeaderFilter.java"))
    }

    def "generates ServiceSecurityConfig.java when security is enabled"() {
        given:
        def ctx = securedCtx()

        when:
        step.generate(ctx)

        then:
        Files.exists(serviceRoot.resolve(
            "src/main/java/com/example/generated/orderservice/ServiceSecurityConfig.java"))
    }

    def "GatewayAuthHeaderFilter.java contains expected class declaration"() {
        given:
        def ctx = securedCtx()

        when:
        step.generate(ctx)

        then:
        def content = Files.readString(serviceRoot.resolve(
            "src/main/java/com/example/generated/orderservice/GatewayAuthHeaderFilter.java"))
        content.contains("class GatewayAuthHeaderFilter")
        content.contains("OncePerRequestFilter")
    }

    def "ServiceSecurityConfig.java contains @EnableMethodSecurity"() {
        given:
        def ctx = securedCtx()

        when:
        step.generate(ctx)

        then:
        def content = Files.readString(serviceRoot.resolve(
            "src/main/java/com/example/generated/orderservice/ServiceSecurityConfig.java"))
        content.contains("@EnableMethodSecurity")
    }

    def "injects JJWT dependencies into pom.xml when present"() {
        given:
        def pomFile = serviceRoot.resolve("pom.xml")
        Files.writeString(pomFile, """
            <project>
              <dependencies>
              </dependencies>
            </project>
        """)
        def ctx = securedCtx()

        when:
        step.generate(ctx)

        then:
        def pom = Files.readString(pomFile)
        pom.contains("jjwt-api") || pom.contains("spring-boot-starter-security")
    }

    def "appends security YAML block to application-dev.yml when present"() {
        given:
        def devYml = serviceRoot.resolve("src/main/resources/application-dev.yml")
        Files.createDirectories(devYml.parent)
        Files.writeString(devYml, "server:\n  port: 8081\n")
        def ctx = securedCtx()

        when:
        step.generate(ctx)

        then:
        def content = Files.readString(devYml)
        content.contains("fractalx")
        content.contains("internal-jwt-secret")
    }
}
