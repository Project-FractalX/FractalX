package org.fractalx.core.gateway

import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies that GatewayRouteLocatorGenerator creates DynamicRouteLocatorConfig
 * and RegistryRouteFetcher in the gateway routing package.
 */
class GatewayRouteLocatorGeneratorSpec extends Specification {

    @TempDir
    Path srcMainJava

    GatewayRouteLocatorGenerator generator = new GatewayRouteLocatorGenerator()

    FractalModule order = FractalModule.builder()
        .serviceName("order-service")
        .packageName("com.example.order")
        .port(8081)
        .build()

    FractalModule payment = FractalModule.builder()
        .serviceName("payment-service")
        .packageName("com.example.payment")
        .port(8082)
        .build()

    private Path routingPkg() {
        srcMainJava.resolve("org/fractalx/gateway/routing")
    }

    private String routeLocator() {
        Files.readString(routingPkg().resolve("DynamicRouteLocatorConfig.java"))
    }

    private String routeFetcher() {
        Files.readString(routingPkg().resolve("RegistryRouteFetcher.java"))
    }

    def "DynamicRouteLocatorConfig.java is created in the routing package"() {
        when:
        generator.generate(srcMainJava, [order])

        then:
        Files.exists(routingPkg().resolve("DynamicRouteLocatorConfig.java"))
    }

    def "RegistryRouteFetcher.java is created in the routing package"() {
        when:
        generator.generate(srcMainJava, [order])

        then:
        Files.exists(routingPkg().resolve("RegistryRouteFetcher.java"))
    }

    def "DynamicRouteLocatorConfig is a @Configuration in the correct package"() {
        when:
        generator.generate(srcMainJava, [order])

        then:
        def c = routeLocator()
        c.startsWith("package org.fractalx.gateway.routing;")
        c.contains("@Configuration")
    }

    def "DynamicRouteLocatorConfig declares a @Bean dynamicRouteLocator method"() {
        when:
        generator.generate(srcMainJava, [order])

        then:
        def c = routeLocator()
        c.contains("@Bean")
        c.contains("dynamicRouteLocator()")
        c.contains("RouteLocator")
    }

    def "DynamicRouteLocatorConfig includes static fallback routes for each module"() {
        when:
        generator.generate(srcMainJava, [order, payment])

        then:
        def c = routeLocator()
        c.contains("order-service-static")
        c.contains("payment-service-static")
        // URIs go through resolveUri(serviceName, port) so Docker overrides via <NAME>_HOST,
        // while local mode keeps the previous "http://localhost:<port>" behavior.
        c.contains('resolveUri("order-service", 8081)')
        c.contains('resolveUri("payment-service", 8082)')
        // The fallback default inside resolveUri must remain "localhost" so local-mode
        // generation is byte-for-byte compatible with the old behavior at runtime.
        c.contains('env.getProperty(envKey, "localhost")')
    }

    def "DynamicRouteLocatorConfig references RegistryRouteFetcher to fetch live routes"() {
        when:
        generator.generate(srcMainJava, [order])

        then:
        def c = routeLocator()
        c.contains("RegistryRouteFetcher")
        c.contains("fetchRoutes")
    }

    def "RegistryRouteFetcher is a @Component in the correct package"() {
        when:
        generator.generate(srcMainJava, [order])

        then:
        def c = routeFetcher()
        c.startsWith("package org.fractalx.gateway.routing;")
        c.contains("@Component")
    }

    def "RegistryRouteFetcher reads registry URL from fractalx.registry.url property"() {
        when:
        generator.generate(srcMainJava, [order])

        then:
        routeFetcher().contains("fractalx.registry.url")
    }

    def "RegistryRouteFetcher calls GET /services on the registry"() {
        when:
        generator.generate(srcMainJava, [order])

        then:
        routeFetcher().contains("/services")
    }

    def "DynamicRouteLocatorConfig includes both singular and plural path patterns"() {
        when:
        generator.generate(srcMainJava, [order])

        then:
        def c = routeLocator()
        c.contains("/api/order/**")
        c.contains("/api/orders/**")
    }

    def "RegistryRouteFetcher includes both singular and plural path patterns in live routes"() {
        when:
        generator.generate(srcMainJava, [order])

        then:
        def c = routeFetcher()
        c.contains("/api/\" + base + \"/**")
        c.contains("/api/\" + plural + \"/**")
    }

    // ── Controller-scanning mode (monolithSrc provided) ───────────────────────

    @TempDir
    Path monolithSrc

    private void writeController(String pkg, String className, String classPath,
                                 List<String[]> methods) {
        Path dir = monolithSrc.resolve(pkg.replace('.', '/'))
        Files.createDirectories(dir)
        def sb = new StringBuilder("package ${pkg};\n")
        sb.append("import org.springframework.web.bind.annotation.*;\n")
        sb.append("@RestController\n")
        sb.append("@RequestMapping(\"${classPath}\")\n")
        sb.append("public class ${className} {\n")
        methods.eachWithIndex { m, i ->
            sb.append("  @${m[0]}Mapping(\"${m[1]}\")\n")
            sb.append("  public Object method${i}() { return null; }\n")
        }
        sb.append("}\n")
        Files.writeString(dir.resolve("${className}.java"), sb.toString())
    }

    def "scanning mode emits cross-resource static route before general routes"() {
        given:
        FractalModule customer = FractalModule.builder()
            .serviceName("customer-service")
            .packageName("com.example.customer")
            .port(8083)
            .build()

        writeController("com.example.order", "OrderController", "/api",
            [["Get", "/orders"], ["Get", "/customers/{customerId}/orders"], ["Get", "/customers/{customerId}/summary"]])
        writeController("com.example.customer", "CustomerController", "/api/customers",
            [["Get", ""], ["Get", "/{id}"]])

        when:
        generator.generate(srcMainJava, [order, customer], monolithSrc)

        then:
        def c = routeLocator()
        // Cross-resource route for order-service is generated
        c.contains("order-service-cross-static")
        c.contains("/api/customers/*/orders")
        c.contains("/api/customers/*/summary")
        // Cross-resource route appears before general order-service-static in the source
        c.indexOf("order-service-cross-static") < c.indexOf("order-service-static")
    }

    def "scanning mode skipped when no monolithSrc provided (backward compatible)"() {
        when:
        generator.generate(srcMainJava, [order])

        then:
        def c = routeLocator()
        !c.contains("cross-static")
        c.contains("order-service-static")
    }
}
