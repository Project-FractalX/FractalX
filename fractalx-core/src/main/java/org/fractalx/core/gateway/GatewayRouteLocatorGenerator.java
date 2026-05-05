package org.fractalx.core.gateway;

import org.fractalx.core.model.FractalModule;
import org.fractalx.core.naming.EnglishPluralizer;
import org.fractalx.core.naming.NamingConventions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates a dynamic RouteLocator that pulls live service locations from
 * fractalx-registry and falls back to static YAML routes if the registry
 * is unavailable.
 *
 * <p>When a monolith source root is provided, the generated static fallback
 * routes include cross-resource routes derived from actual controller paths
 * (e.g. {@code /api/customers/&#42;/orders} → order-service) — matching the
 * YAML gateway config so neither locator shadows the other.
 */
public class GatewayRouteLocatorGenerator {

    private static final Logger log = LoggerFactory.getLogger(GatewayRouteLocatorGenerator.class);

    private final ControllerPathScanner scanner = new ControllerPathScanner();
    private final EnglishPluralizer pluralizer =
            new EnglishPluralizer(NamingConventions.defaults().irregularPlurals());

    public void generate(Path srcMainJava, List<FractalModule> modules) throws IOException {
        generate(srcMainJava, modules, null);
    }

    /**
     * @param monolithSrc path to monolith {@code src/main/java}; when non-null,
     *                    cross-resource routes are scanned and emitted before general routes
     */
    public void generate(Path srcMainJava, List<FractalModule> modules, Path monolithSrc) throws IOException {
        Path pkg = createPkg(srcMainJava, "org/fractalx/gateway/routing");

        generateDynamicRouteLocator(pkg, modules, monolithSrc);
        generateRegistryRouteFetcher(pkg);

        log.info("Generated dynamic route locator");
    }

    private void generateDynamicRouteLocator(Path pkg, List<FractalModule> modules, Path monolithSrc) throws IOException {
        StringBuilder staticRoutesBuilder = new StringBuilder();

        // Step 1: scan controller paths per module (when source available)
        Map<FractalModule, Set<ControllerPathScanner.EndpointInfo>> scanned = new LinkedHashMap<>();
        if (monolithSrc != null) {
            for (FractalModule m : modules) {
                Set<ControllerPathScanner.EndpointInfo> eps = scanner.scan(monolithSrc, m);
                if (!eps.isEmpty()) scanned.put(m, eps);
            }
        }

        // Step 2: build primary prefix for each module (based on package name)
        Map<FractalModule, String> primaryPrefix = new LinkedHashMap<>();
        for (FractalModule m : modules) {
            String pkgName = m.getPackageName();
            String lastSegment = pkgName != null && pkgName.contains(".")
                    ? pkgName.substring(pkgName.lastIndexOf('.') + 1)
                    : (pkgName != null ? pkgName : m.getServiceName().replace("-service", ""));
            primaryPrefix.put(m, "/api/" + lastSegment);
        }

        // Step 3: emit cross-resource routes FIRST (so they take precedence over general wildcards)
        for (FractalModule m : modules) {
            Set<ControllerPathScanner.EndpointInfo> endpoints = scanned.get(m);
            if (endpoints == null || endpoints.isEmpty()) continue;

            String myPrefix    = primaryPrefix.get(m);
            String myPluralPfx = pluralizePathPrefix(myPrefix);

            Set<String> foreignPredicates = new LinkedHashSet<>();
            for (ControllerPathScanner.EndpointInfo ep : endpoints) {
                String gwPath = ep.gatewayPath();
                if (gwPath.startsWith(myPrefix) || gwPath.startsWith(myPluralPfx)) continue;
                // Check whether path belongs to another module's prefix (cross-resource)
                boolean foreign = modules.stream().filter(other -> other != m).anyMatch(other -> {
                    String op = primaryPrefix.get(other);
                    String opp = pluralizePathPrefix(op);
                    return gwPath.startsWith(op) || gwPath.startsWith(opp);
                });
                if (!foreign) continue;
                foreignPredicates.add(gwPath);
                if (!gwPath.endsWith("/**")) foreignPredicates.add(gwPath + "/**");
            }

            if (!foreignPredicates.isEmpty()) {
                staticRoutesBuilder.append("        // ").append(m.getServiceName()).append(" cross-resource routes\n");
                staticRoutesBuilder.append("        routeBuilder.route(\"").append(m.getServiceName()).append("-cross-static\",\n");
                staticRoutesBuilder.append("                r -> r.path(");
                boolean first = true;
                for (String p : foreignPredicates) {
                    if (!first) staticRoutesBuilder.append(", ");
                    staticRoutesBuilder.append('"').append(p).append('"');
                    first = false;
                }
                staticRoutesBuilder.append(")\n");
                staticRoutesBuilder.append("                        .filters(f -> f.circuitBreaker(c -> c.setName(\"")
                        .append(m.getServiceName()).append("\")\n");
                staticRoutesBuilder.append("                                .setFallbackUri(\"forward:/fallback/")
                        .append(m.getServiceName()).append("\")))\n");
                staticRoutesBuilder.append("                        .uri(resolveUri(\"")
                        .append(m.getServiceName()).append("\", ").append(m.getPort()).append(")));\n\n");
            }
        }

        // Step 4: emit general routes (pluralization via EnglishPluralizer)
        for (FractalModule m : modules) {
            String base = m.getServiceName().replace("-service", "");
            String plural = pluralizer.pluralize(base);

            staticRoutesBuilder.append("        // ").append(m.getServiceName()).append(" static route\n");
            staticRoutesBuilder.append("        routeBuilder.route(\"").append(m.getServiceName()).append("-static\",\n");
            staticRoutesBuilder.append("                r -> r.path(\"/api/").append(base).append("/**\", \"/api/").append(plural).append("/**\")\n");
            staticRoutesBuilder.append("                        .filters(f -> f.circuitBreaker(c -> c.setName(\"").append(m.getServiceName()).append("\")\n");
            staticRoutesBuilder.append("                                .setFallbackUri(\"forward:/fallback/").append(m.getServiceName()).append("\")))\n");
            staticRoutesBuilder.append("                        .uri(resolveUri(\"")
                    .append(m.getServiceName()).append("\", ").append(m.getPort()).append(")));\n\n");
        }

        String content = """
                package org.fractalx.gateway.routing;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.cloud.gateway.route.Route;
                import org.springframework.cloud.gateway.route.RouteLocator;
                import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.core.env.Environment;
                import reactor.core.publisher.Flux;

                import java.util.ArrayList;
                import java.util.List;

                @Configuration
                public class DynamicRouteLocatorConfig {

                    private static final Logger log = LoggerFactory.getLogger(DynamicRouteLocatorConfig.class);

                    private final RegistryRouteFetcher registryFetcher;
                    private final RouteLocatorBuilder builder;
                    private final Environment env;

                    public DynamicRouteLocatorConfig(RegistryRouteFetcher registryFetcher,
                                                     RouteLocatorBuilder builder,
                                                     Environment env) {
                        this.registryFetcher = registryFetcher;
                        this.builder = builder;
                        this.env = env;
                    }

                    @Bean
                    public RouteLocator dynamicRouteLocator() {
                        return () -> {
                            // Try to fetch live routes from registry first
                            try {
                                List<Route> liveRoutes = registryFetcher.fetchRoutes(builder);
                                if (!liveRoutes.isEmpty()) {
                                    log.info("Using {} live routes from fractalx-registry", liveRoutes.size());
                                    return Flux.fromIterable(liveRoutes);
                                }
                                log.warn("No live routes available from registry");
                            } catch (Exception e) {
                                log.warn("Could not fetch routes from registry: {}", e.getMessage());
                            }

                            // Fallback to static routes
                            log.info("Using static fallback routes");
                            return Flux.fromIterable(buildStaticRoutes());
                        };
                    }

                    private List<Route> buildStaticRoutes() {
                        List<Route> routes = new ArrayList<>();
                        RouteLocatorBuilder.Builder routeBuilder = builder.routes();
                %s
                        routeBuilder.build().getRoutes().subscribe(routes::add);
                        log.debug("Built {} static fallback routes", routes.size());
                        return routes;
                    }

                    /**
                     * Resolves the host portion of a static fallback URI from
                     * {@code <SERVICE_NAME>_HOST} (uppercase, dashes → underscores),
                     * defaulting to {@code localhost} when the variable is unset.
                     * In Docker compose the variable is provided per service so the
                     * fallback resolves to the container's DNS name; locally it
                     * stays {@code localhost}.
                     */
                    private String resolveUri(String serviceName, int port) {
                        String envKey = serviceName.toUpperCase().replace("-", "_") + "_HOST";
                        String host = env.getProperty(envKey, "localhost");
                        return "http://" + host + ":" + port;
                    }
                }
                """.formatted(staticRoutesBuilder.toString());

        Files.writeString(pkg.resolve("DynamicRouteLocatorConfig.java"), content);
    }

    private void generateRegistryRouteFetcher(Path pkg) throws IOException {
        String content = """
                package org.fractalx.gateway.routing;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.cloud.gateway.route.Route;
                import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
                import org.springframework.stereotype.Component;
                import org.springframework.web.client.ResourceAccessException;
                import org.springframework.web.client.HttpClientErrorException;
                import org.springframework.web.client.RestTemplate;

                import java.util.ArrayList;
                import java.util.List;
                import java.util.Map;

                @Component
                public class RegistryRouteFetcher {

                    private static final Logger log = LoggerFactory.getLogger(RegistryRouteFetcher.class);

                    @Value("${fractalx.registry.url:http://localhost:8761}")
                    private String registryUrl;

                    private final RestTemplate restTemplate = new RestTemplate();

                    @SuppressWarnings("unchecked")
                    public List<Route> fetchRoutes(RouteLocatorBuilder builder) {
                        List<Route> routes = new ArrayList<>();
                        try {
                            log.debug("Fetching routes from registry at {}", registryUrl);
                            
                            List<Map<String, Object>> services =
                                    restTemplate.getForObject(registryUrl + "/services", List.class);
                                    
                            if (services == null || services.isEmpty()) {
                                log.debug("No services found in registry");
                                return routes;
                            }
                            
                            RouteLocatorBuilder.Builder routeBuilder = builder.routes();
                            
                            for (Map<String, Object> svc : services) {

                                if (!"UP".equals(svc.get("status"))) {
                                    log.debug("Skipping service {} - status: {}",
                                        svc.get("name"), svc.get("status"));
                                    continue;
                                }
                                
                                String name = (String) svc.get("name");
                                String host = (String) svc.get("host");
                                int port = ((Number) svc.get("port")).intValue();
                                String base = toPathBase(name);
                                String plural = toPathPlural(name);
                                String uri = "http://" + host + ":" + port;

                                routeBuilder.route(name + "-live",
                                    r -> r.path("/api/" + base + "/**", "/api/" + plural + "/**")
                                        .filters(f -> f.circuitBreaker(c -> c.setName(name)
                                            .setFallbackUri("forward:/fallback/" + name)))
                                        .uri(uri));

                                log.info("Live routes added: /api/{}/** and /api/{}/** -> {}", base, plural, uri);
                            }
                            
                            routeBuilder.build().getRoutes().subscribe(routes::add);
                            
                        } catch (ResourceAccessException e) {
                            log.warn("Cannot connect to registry at {}: {}", registryUrl, e.getMessage());
                        } catch (HttpClientErrorException e) {
                            log.warn("Registry returned error: {}", e.getMessage());
                        } catch (Exception e) {
                            log.warn("Could not fetch routes from registry: {}", e.getMessage());
                        }
                        return routes;
                    }

                    private String toPathBase(String serviceName) {
                        return serviceName.replace("-service", "");
                    }

                    private String toPathPlural(String serviceName) {
                        String word = serviceName.replace("-service", "");
                        String lower = word.toLowerCase();
                        // Consonant + y → ies
                        if (lower.endsWith("y") && lower.length() > 1
                                && "aeiou".indexOf(lower.charAt(lower.length() - 2)) < 0) {
                            return lower.substring(0, lower.length() - 1) + "ies";
                        }
                        // -f → -ves
                        if (lower.endsWith("f") && lower.length() > 1) {
                            return lower.substring(0, lower.length() - 1) + "ves";
                        }
                        // Sibilants → -es
                        if (lower.endsWith("s") || lower.endsWith("x") || lower.endsWith("z")
                                || lower.endsWith("ch") || lower.endsWith("sh")) {
                            return lower + "es";
                        }
                        // Default -s
                        return lower + "s";
                    }
                }
                """;

        Files.writeString(pkg.resolve("RegistryRouteFetcher.java"), content);
    }

    private Path createPkg(Path base, String pkg) throws IOException {
        Path p = base;
        for (String part : pkg.split("/")) p = p.resolve(part);
        Files.createDirectories(p);
        return p;
    }

    /** Pluralizes the last segment of a path prefix, e.g. {@code /api/category} → {@code /api/categories}. */
    private String pluralizePathPrefix(String prefix) {
        int lastSlash = prefix.lastIndexOf('/');
        if (lastSlash < 0) return pluralizer.pluralize(prefix);
        String base = prefix.substring(0, lastSlash + 1);
        String segment = prefix.substring(lastSlash + 1);
        return base + pluralizer.pluralize(segment);
    }
}
