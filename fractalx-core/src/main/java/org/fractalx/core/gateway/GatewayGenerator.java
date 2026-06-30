package org.fractalx.core.gateway;

import org.fractalx.core.auth.AuthPattern;
import org.fractalx.core.config.FractalxConfig;
import org.fractalx.core.model.FractalModule;
import org.fractalx.core.util.SpringBootVersionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// New generators wired in below

/**
 * Main generator for API Gateway project
 */
public class GatewayGenerator {
    private static final Logger log = LoggerFactory.getLogger(GatewayGenerator.class);

    private final Path sourceRoot;
    private final Path outputRoot;
    private final FractalxConfig fractalxConfig;

    public GatewayGenerator(Path sourceRoot, Path outputRoot) {
        this(sourceRoot, outputRoot, FractalxConfig.defaults());
    }

    public GatewayGenerator(Path sourceRoot, Path outputRoot, FractalxConfig fractalxConfig) {
        this.sourceRoot = sourceRoot;
        this.outputRoot = outputRoot;
        this.fractalxConfig = fractalxConfig;
    }

    /**
     * Generate API Gateway for all services
     */
    public void generateGateway(List<FractalModule> modules) throws IOException {
        generateGateway(modules, null);
    }

    /**
     * Generate API Gateway for all services, with optional auth-service integration.
     *
     * @param authPattern when detected, prepends the auth-service route and uses its JWT secret
     */
    public void generateGateway(List<FractalModule> modules, AuthPattern authPattern) throws IOException {
        log.info("Generating API Gateway for {} services", modules.size());

        // Create gateway directory
        Path gatewayRoot = outputRoot.resolve("fractalx-gateway");
        Path srcMainJava = gatewayRoot.resolve("src/main/java");
        Path srcMainResources = gatewayRoot.resolve("src/main/resources");

        Files.createDirectories(srcMainJava);
        Files.createDirectories(srcMainResources);

        // Step 1: Collect basic routes from all services
        List<RouteDefinition> allRoutes = new ArrayList<>();
        GatewayAnalyzer analyzer = new GatewayAnalyzer();

        for (FractalModule module : modules) {
            Path serviceSrcDir = sourceRoot.resolve(module.getPackageName().replace('.', '/'));
            if (Files.exists(serviceSrcDir)) {
                List<RouteDefinition> routes = analyzer.analyzeServiceRoutes(serviceSrcDir, module);
                allRoutes.addAll(routes);
                log.info("Found {} routes in {}", routes.size(), module.getServiceName());
            }
        }

        log.info("Total routes discovered: {}", allRoutes.size());

        // Step 2: Generate POM
        GatewayPomGenerator pomGen = new GatewayPomGenerator();
        pomGen.generatePom(gatewayRoot, modules, fractalxConfig);

        // Step 3: Generate Application class
        GatewayApplicationGenerator appGen = new GatewayApplicationGenerator();
        appGen.generateApplicationClass(srcMainJava);

        // Step 4: Detect monolith security configuration
        SecurityProfile securityProfile = new SecurityAnalyzer().analyze(
                sourceRoot, sourceRoot.resolve("src/main/resources"));
        log.info("Monolith security profile — authType={} rules={}",
                securityProfile.authType(), securityProfile.routeRules().size());

        // Step 5: Generate configuration (security profile drives YAML defaults + resilience4j)
        // Pass sourceRoot so routes are derived from actual controller paths — this is how
        // cross-resource endpoints (e.g. GET /api/customers/{id}/orders owned by order-service)
        // get correctly routed instead of being shadowed by the customer-service wildcard.
        GatewayConfigGenerator configGen = new GatewayConfigGenerator(sourceRoot);
        configGen.generateConfig(srcMainResources, modules, allRoutes, fractalxConfig,
                                  securityProfile, authPattern, sourceRoot);

        // Step 6: Generate documentation
        ApiDocumentationGenerator docGen = new ApiDocumentationGenerator();
        docGen.generateDocumentation(gatewayRoot, modules, allRoutes);

        // Step 7: Dynamic route locator (registry-backed with static fallback)
        // Pass sourceRoot so the generated buildStaticRoutes() includes cross-resource
        // Java-bean routes matching the YAML config; otherwise the name-heuristic static
        // fallback shadows cross-resource YAML routes (e.g. /api/customers/* /orders → 404).
        new GatewayRouteLocatorGenerator().generate(srcMainJava, modules, sourceRoot);

        // Step 8: Multi-mechanism security — mirrors monolith auth type and route rules
        new GatewaySecurityGenerator().generate(srcMainJava, modules, securityProfile);

        // Step 9: In-memory rate limiter
        new GatewayRateLimiterGenerator().generate(srcMainJava, modules);

        // Step 10: Global CORS filter
        new GatewayCorsGenerator().generate(srcMainJava);

        // Step 13: OpenAPI 3.0.3 spec + Postman Collection v2.1 (with inline tests)
        // Passing sourceRoot enables controller-scanning: the Postman collection and OpenAPI
        // spec reflect actual controller paths (including cross-resource endpoints such as
        // GET /api/customers/{id}/orders in the order-service folder).
        new GatewayOpenApiGenerator().generate(gatewayRoot, modules, authPattern, sourceRoot);

        // Step 14: Boot 4.x compatibility shims — intentionally skipped.
        // The gateway is pinned to Spring Boot 3.4.x (Spring Cloud 2024.0.x / Gateway 4.2.x)
        // even when services target Boot 4.x, to avoid HttpHeaders.containsKey(Object) NoSuchMethodError
        // in Spring Cloud Gateway 4.3.x vs Spring Framework 7.0.3+. No shims needed for Boot 3.x.

        log.info("✓ API Gateway generated at: {}", gatewayRoot.toAbsolutePath());
    }

    /**
     * Generates Spring Boot 4.x compatibility shim classes needed by spring-cloud-gateway 4.3.x.
     *
     * <p>Spring Cloud Gateway 4.3.x references two classes that moved in Spring Boot 4.x:
     * <ul>
     *   <li>{@code org.springframework.boot.autoconfigure.web.ServerProperties} →
     *       {@code org.springframework.boot.web.server.autoconfigure.ServerProperties}</li>
     *   <li>{@code org.springframework.boot.autoconfigure.web.embedded.NettyWebServerFactoryCustomizer} →
     *       {@code org.springframework.boot.reactor.netty.autoconfigure.NettyReactiveWebServerFactoryCustomizer}</li>
     * </ul>
     * Without these shims, Spring fails with {@code ClassNotFoundException} during condition evaluation
     * even before any @Conditional annotation is checked.
     */
    private void generateBoot4GatewayCompatibility(Path srcMainJava) throws IOException {
        // Shim 1: ServerProperties at the old package path — standalone (does NOT extend
        // the new Boot 4.x ServerProperties to avoid a duplicate-bean conflict with
        // ReactiveWebServerConfiguration which expects exactly one bean of the new type).
        // Only the methods used by spring-cloud-gateway 4.3.x (getPort, getHttp2) are needed.
        Path serverPropsDir = srcMainJava
                .resolve("org/springframework/boot/autoconfigure/web");
        Files.createDirectories(serverPropsDir);
        Files.writeString(serverPropsDir.resolve("ServerProperties.java"), """
                package org.springframework.boot.autoconfigure.web;

                /**
                 * Spring Boot 4.x compatibility shim.
                 * ServerProperties moved to org.springframework.boot.web.server.autoconfigure.ServerProperties.
                 * spring-cloud-gateway 4.3.x still references the old path.
                 *
                 * <p>This class is intentionally NOT a subclass of the Boot 4.x ServerProperties so that
                 * there is no bean-type conflict in ReactiveWebServerConfiguration (which needs exactly one
                 * bean of org.springframework.boot.web.server.autoconfigure.ServerProperties).
                 * Only the two methods actually invoked by SCG's HttpClientFactory are provided.
                 */
                public class ServerProperties {

                    private Integer port;
                    private org.springframework.boot.web.server.Http2 http2 =
                            new org.springframework.boot.web.server.Http2();

                    public Integer getPort() { return port; }
                    public void setPort(Integer port) { this.port = port; }

                    public org.springframework.boot.web.server.Http2 getHttp2() { return http2; }
                    public void setHttp2(org.springframework.boot.web.server.Http2 http2) {
                        this.http2 = http2;
                    }
                }
                """);

        // Shim 2: NettyWebServerFactoryCustomizer at the old package path
        Path nettyDir = srcMainJava
                .resolve("org/springframework/boot/autoconfigure/web/embedded");
        Files.createDirectories(nettyDir);
        Files.writeString(nettyDir.resolve("NettyWebServerFactoryCustomizer.java"), """
                package org.springframework.boot.autoconfigure.web.embedded;

                import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory;
                import org.springframework.boot.web.server.WebServerFactoryCustomizer;

                /**
                 * Spring Boot 4.x compatibility shim.
                 * NettyWebServerFactoryCustomizer moved to
                 * org.springframework.boot.reactor.netty.autoconfigure.NettyReactiveWebServerFactoryCustomizer.
                 * spring-cloud-gateway 4.3.x still references the old path.
                 */
                public class NettyWebServerFactoryCustomizer
                        implements WebServerFactoryCustomizer<NettyReactiveWebServerFactory> {

                    @Override
                    public void customize(NettyReactiveWebServerFactory factory) {
                        // no-op: Boot 4.x Netty customization is handled by the framework
                    }
                }
                """);

        // Shim 3: NettyServerCustomizer interface at the old package path
        Path nettyEmbeddedDir = srcMainJava
                .resolve("org/springframework/boot/web/embedded/netty");
        Files.createDirectories(nettyEmbeddedDir);
        Files.writeString(nettyEmbeddedDir.resolve("NettyServerCustomizer.java"), """
                package org.springframework.boot.web.embedded.netty;

                /**
                 * Spring Boot 4.x compatibility shim.
                 * NettyServerCustomizer moved to org.springframework.boot.reactor.netty.NettyServerCustomizer.
                 * spring-cloud-gateway 4.3.x still references the old path.
                 */
                public interface NettyServerCustomizer
                        extends org.springframework.boot.reactor.netty.NettyServerCustomizer {
                }
                """);

        // Shim 4: NettyReactiveWebServerFactory at the old package path
        Files.writeString(nettyEmbeddedDir.resolve("NettyReactiveWebServerFactory.java"), """
                package org.springframework.boot.web.embedded.netty;

                /**
                 * Spring Boot 4.x compatibility shim.
                 * NettyReactiveWebServerFactory moved to org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory.
                 * spring-cloud-gateway 4.3.x still references the old path.
                 */
                public class NettyReactiveWebServerFactory
                        extends org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory {
                }
                """);

        // Shim 5: WebServerApplicationContext interface at old package path.
        // RouteRefreshListener calls WebServerApplicationContext.hasServerNamespace() (old path).
        // The interface moved to org.springframework.boot.web.server.context in Boot 4.x.
        Path webContextDir = srcMainJava.resolve("org/springframework/boot/web/context");
        Files.createDirectories(webContextDir);
        Files.writeString(webContextDir.resolve("WebServerApplicationContext.java"), """
                package org.springframework.boot.web.context;

                /**
                 * Spring Boot 4.x compatibility shim.
                 * WebServerApplicationContext moved to org.springframework.boot.web.server.context.
                 * spring-cloud-gateway 4.3.x RouteRefreshListener still calls the static
                 * hasServerNamespace() method at the old path.
                 */
                public interface WebServerApplicationContext extends org.springframework.context.ApplicationContext {

                    org.springframework.boot.web.server.WebServer getWebServer();

                    static boolean hasServerNamespace(org.springframework.context.ApplicationContext context,
                                                      String namespace) {
                        return org.springframework.boot.web.server.context.WebServerApplicationContext
                                .hasServerNamespace(context, namespace);
                    }
                }
                """);

        // Shim 6: HttpHandlerAutoConfiguration placeholder at old package path
        // GatewayAutoConfiguration has @AutoConfigureAfter({HttpHandlerAutoConfiguration.class, WebFluxAutoConfiguration.class})
        // Spring resolves these class names during annotation processing - they just need to exist.
        Path reactiveWebDir = srcMainJava
                .resolve("org/springframework/boot/autoconfigure/web/reactive");
        Files.createDirectories(reactiveWebDir);
        Files.writeString(reactiveWebDir.resolve("HttpHandlerAutoConfiguration.java"), """
                package org.springframework.boot.autoconfigure.web.reactive;

                /**
                 * Spring Boot 4.x compatibility shim.
                 * HttpHandlerAutoConfiguration moved to org.springframework.boot.webflux.autoconfigure.
                 * spring-cloud-gateway 4.3.x @AutoConfigureAfter still references the old path.
                 * The new class is final so this is a standalone placeholder — Spring only needs
                 * the class to be resolvable for @AutoConfigureAfter ordering, not instantiable.
                 */
                public class HttpHandlerAutoConfiguration {
                }
                """);

        // Shim 6b: WebFluxAutoConfiguration placeholder at old package path
        Files.writeString(reactiveWebDir.resolve("WebFluxAutoConfiguration.java"), """
                package org.springframework.boot.autoconfigure.web.reactive;

                /**
                 * Spring Boot 4.x compatibility shim.
                 * WebFluxAutoConfiguration moved to org.springframework.boot.webflux.autoconfigure.
                 * spring-cloud-gateway 4.3.x @AutoConfigureAfter still references the old path.
                 * The new class is final so this is a standalone placeholder — Spring only needs
                 * the class to be resolvable for @AutoConfigureAfter ordering, not instantiable.
                 */
                public class WebFluxAutoConfiguration {
                }
                """);

        // Shim 7: WebFluxProperties at old package path — used by PathRoutePredicateFactory.
        // Intentionally NOT a subclass of Boot 4.x WebFluxProperties to avoid a duplicate-bean
        // conflict (same issue as ServerProperties above). Only getBasePath() is called by SCG.
        Files.writeString(reactiveWebDir.resolve("WebFluxProperties.java"), """
                package org.springframework.boot.autoconfigure.web.reactive;

                /**
                 * Spring Boot 4.x compatibility shim.
                 * WebFluxProperties moved to org.springframework.boot.webflux.autoconfigure.WebFluxProperties.
                 * spring-cloud-gateway 4.3.x PathRoutePredicateFactory still references the old path.
                 * Standalone (not a subclass) to avoid duplicate-bean conflict with Boot 4.x WebFluxProperties.
                 */
                public class WebFluxProperties {

                    private String basePath;

                    public String getBasePath() { return basePath; }
                    public void setBasePath(String basePath) { this.basePath = basePath; }
                }
                """);

        // Shim 8: PathPatternParser replacement at the Spring Web package path.
        //
        // Spring Framework 7 removed PathPatternParser.setMatchOptionalTrailingSeparator(boolean).
        // Spring Cloud Gateway 4.3.x (compiled against Spring Framework 6.x) still calls this
        // method in PathRoutePredicateFactory.apply() via an `invokevirtual` bytecode instruction.
        //
        // JVM `invokevirtual` resolves methods against the DECLARED class (PathPatternParser), NOT
        // the runtime type. So even if the field holds a subclass with the method, resolution fails
        // with NoSuchMethodError because Spring Framework 7's PathPatternParser doesn't have it.
        //
        // Fix: provide a complete replacement PathPatternParser in BOOT-INF/classes/ that includes
        // the removed method as no-op. Spring Boot's child-first classloader loads BOOT-INF/classes/
        // BEFORE BOOT-INF/lib/spring-web.jar, so our replacement is loaded first.
        // Actual parsing is delegated to InternalPathPatternParser (same package, package-private)
        // from spring-web.jar, which only calls getPathOptions() and isCaseSensitive() — both present.
        Path springPatternDir = srcMainJava.resolve("org/springframework/web/util/pattern");
        Files.createDirectories(springPatternDir);
        Files.writeString(springPatternDir.resolve("PathPatternParser.java"), """
                package org.springframework.web.util.pattern;

                import org.springframework.http.server.PathContainer;

                /**
                 * Spring Boot 4.x / Spring Cloud Gateway 4.3.x compatibility shim.
                 *
                 * <p>Spring Framework 7 removed {@code setMatchOptionalTrailingSeparator(boolean)}.
                 * Spring Cloud Gateway 4.3.x {@code PathRoutePredicateFactory.apply()} calls it via
                 * {@code invokevirtual}, which resolves against the DECLARED class — meaning a subclass
                 * fix cannot work. This replacement class is loaded first by Spring Boot's child-first
                 * classloader ({@code BOOT-INF/classes/} before {@code BOOT-INF/lib/spring-web.jar}).
                 *
                 * <p>Actual pattern parsing is delegated to {@code InternalPathPatternParser} (same
                 * package, package-private) from {@code spring-web.jar}, which only uses
                 * {@link #getPathOptions()} and {@link #isCaseSensitive()} — both provided here.
                 */
                public class PathPatternParser {

                    public static final PathPatternParser defaultInstance = new PathPatternParser();

                    private boolean caseSensitive = true;
                    private PathContainer.Options pathOptions = PathContainer.Options.HTTP_PATH;

                    public void setCaseSensitive(boolean caseSensitive) {
                        this.caseSensitive = caseSensitive;
                    }

                    public boolean isCaseSensitive() {
                        return this.caseSensitive;
                    }

                    public void setPathOptions(PathContainer.Options pathOptions) {
                        this.pathOptions = pathOptions;
                    }

                    public PathContainer.Options getPathOptions() {
                        return this.pathOptions;
                    }

                    /**
                     * No-op compatibility shim: removed in Spring Framework 7.
                     * SCG 4.3.x {@code PathRoutePredicateFactory} still calls this method.
                     */
                    public void setMatchOptionalTrailingSeparator(boolean matchOptionalTrailingSeparator) {
                        // No-op: method removed in Spring Framework 7.x
                    }

                    public PathPattern parse(String patternString) throws PatternParseException {
                        return new InternalPathPatternParser(this).parse(patternString);
                    }

                    public String initFullPathPattern(String pattern) {
                        if (org.springframework.util.StringUtils.hasLength(pattern) && !pattern.startsWith("/")) {
                            return "/" + pattern;
                        }
                        return (pattern != null) ? pattern : "";
                    }
                }
                """);

        // Shim 9: @Configuration that registers the old-path ServerProperties and WebFluxProperties beans
        // so spring-cloud-gateway can autowire them into GatewayAutoConfiguration.NettyConfiguration.
        Path gatewayPkg = srcMainJava.resolve("org/fractalx/gateway");
        Files.createDirectories(gatewayPkg);
        Files.writeString(gatewayPkg.resolve("Boot4CompatibilityConfig.java"), """
                package org.fractalx.gateway;

                import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                /**
                 * Registers compatibility beans so spring-cloud-gateway 4.3.x can work with Spring Boot 4.x.
                 *
                 * <p>Handles the following Boot 4.x breaking changes:
                 * <ol>
                 *   <li>{@code org.springframework.boot.autoconfigure.web.ServerProperties} moved to
                 *       {@code org.springframework.boot.web.server.autoconfigure.ServerProperties}</li>
                 *   <li>{@code org.springframework.boot.autoconfigure.web.reactive.WebFluxProperties} moved to
                 *       {@code org.springframework.boot.webflux.autoconfigure.WebFluxProperties}</li>
                 *   <li>{@code PathPatternParser.setMatchOptionalTrailingSeparator(boolean)} removed in
                 *       Spring Framework 7 — fixed via source-level replacement shim in Shim 8
                 *       ({@code org/springframework/web/util/pattern/PathPatternParser.java}),
                 *       loaded first by Spring Boot's child-first classloader.</li>
                 * </ol>
                 */
                @Configuration(proxyBeanMethods = false)
                class Boot4CompatibilityConfig {

                    @Bean
                    @ConditionalOnMissingBean(org.springframework.boot.autoconfigure.web.ServerProperties.class)
                    org.springframework.boot.autoconfigure.web.ServerProperties legacyServerProperties(
                            org.springframework.boot.web.server.autoconfigure.ServerProperties real) {
                        org.springframework.boot.autoconfigure.web.ServerProperties legacy =
                                new org.springframework.boot.autoconfigure.web.ServerProperties();
                        legacy.setPort(real.getPort());
                        legacy.setHttp2(real.getHttp2());
                        return legacy;
                    }

                    @Bean
                    @ConditionalOnMissingBean(org.springframework.boot.autoconfigure.web.reactive.WebFluxProperties.class)
                    org.springframework.boot.autoconfigure.web.reactive.WebFluxProperties legacyWebFluxProperties(
                            org.springframework.boot.webflux.autoconfigure.WebFluxProperties real) {
                        org.springframework.boot.autoconfigure.web.reactive.WebFluxProperties legacy =
                                new org.springframework.boot.autoconfigure.web.reactive.WebFluxProperties();
                        legacy.setBasePath(real.getBasePath());
                        return legacy;
                    }
                }
                """);

        log.info("✓ Generated Spring Boot 4.x gateway compatibility shims");
    }
}