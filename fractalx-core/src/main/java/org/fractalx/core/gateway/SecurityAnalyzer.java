package org.fractalx.core.gateway;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import org.fractalx.core.gateway.SecurityProfile.AuthType;
import org.fractalx.core.gateway.SecurityProfile.RouteSecurityRule;
import org.fractalx.core.graph.DependencyGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans the monolith's source tree and resource files to detect its security
 * configuration, then produces a {@link SecurityProfile} that the gateway
 * generators use to replicate the same auth mechanism and route rules.
 *
 * <p>Detection strategy (in priority order):
 * <ol>
 *   <li>YAML/properties: {@code spring.security.oauth2.resourceserver.jwt.*}</li>
 *   <li>AST: {@code oauth2ResourceServer()} call in a {@code SecurityFilterChain} bean</li>
 *   <li>YAML/properties: {@code spring.security.user.*} (Basic Auth)</li>
 *   <li>AST: {@code httpBasic()} call in a {@code SecurityFilterChain} bean</li>
 *   <li>AST: {@code OncePerRequestFilter} subclass with "Bearer " string literal</li>
 *   <li>AST: {@code @EnableWebSecurity} class present (generic JWT assumed)</li>
 * </ol>
 *
 * <p>Route rules are extracted from:
 * <ul>
 *   <li>{@code requestMatchers(...).hasRole(...)} / {@code hasAnyRole(...)} / {@code permitAll()} chains</li>
 *   <li>{@code @PreAuthorize("hasRole('X')")} annotations on controller methods</li>
 * </ul>
 */
public class SecurityAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SecurityAnalyzer.class);

    private static final Pattern ROLE_IN_PRE_AUTHORIZE =
            Pattern.compile("hasRole\\('(?:ROLE_)?([^']+)'\\)|hasAnyRole\\(([^)]+)\\)");
    private static final Pattern ROLE_STRIP_PREFIX =
            Pattern.compile("(?:ROLE_)?(.+)");

    private final JavaParser javaParser = new JavaParser();

    /** Optional graph for structural queries — set via {@link #analyze(Path, Path, DependencyGraph)}. */
    private DependencyGraph graph;

    /**
     * Analyzes the monolith project and returns a {@link SecurityProfile}.
     *
     * @param sourceRoot     the monolith's source root — tried as both project-root
     *                       and as {@code src/main/java} directly
     * @param resourcesRoot  the monolith's {@code src/main/resources} directory
     */
    public SecurityProfile analyze(Path sourceRoot, Path resourcesRoot) {
        return analyze(sourceRoot, resourcesRoot, null);
    }

    /**
     * Graph-aware overload — uses the {@link DependencyGraph} for structural queries
     * (extends, implements, annotations) instead of re-walking the AST for class-level checks.
     */
    public SecurityProfile analyze(Path sourceRoot, Path resourcesRoot, DependencyGraph dependencyGraph) {
        this.graph = dependencyGraph;
        return doAnalyze(sourceRoot, resourcesRoot);
    }

    private SecurityProfile doAnalyze(Path sourceRoot, Path resourcesRoot) {
        log.info("[SecurityAnalyzer] Scanning monolith security configuration...");

        // Resolve the java source directory — handle both project-root and src/main/java inputs
        Path javaRoot = resolveJavaRoot(sourceRoot);
        Path resRoot  = resolveResourcesRoot(sourceRoot, resourcesRoot);

        // Phase 1: read spring.security.* from YAML / properties
        YamlSecurityConfig yaml = readYamlSecurity(resRoot);
        log.debug("[SecurityAnalyzer] YAML config: jwkSetUri={} issuer={} basicUser={}",
                yaml.jwkSetUri, yaml.issuerUri, yaml.basicUsername);

        // Phase 2: parse all Java files
        List<CompilationUnit> cus = parseAll(javaRoot);
        log.debug("[SecurityAnalyzer] Parsed {} Java files from {}", cus.size(), javaRoot);

        // Phase 3: detect ALL auth types (monolith may configure more than one mechanism)
        Set<AuthType> authTypes = detectAllAuthTypes(cus, yaml);
        AuthType primaryAuthType = authTypes.isEmpty() ? AuthType.NONE : authTypes.iterator().next();
        log.info("[SecurityAnalyzer] Detected auth types: {}", authTypes);
        if (authTypes.size() > 1) {
            log.info("[SecurityAnalyzer] Multiple auth types detected — gateway will enable all: {}", authTypes);
        }

        // Phase 4: extract requestMatchers route rules from SecurityFilterChain
        List<RouteSecurityRule> rules = new ArrayList<>(extractRequestMatcherRules(cus));

        // Phase 5: always extract @PreAuthorize rules from controllers (merged with SecurityFilterChain rules)
        // Previously this was only done when SecurityFilterChain rules were empty — this was wrong
        // because monoliths can have BOTH framework-level and method-level security.
        rules.addAll(extractPreAuthorizeRules(cus));

        // Phase 6: collect explicit public paths
        List<String> publicPaths = collectPublicPaths(rules);

        boolean securityEnabled = primaryAuthType != AuthType.NONE;

        SecurityProfile profile = new SecurityProfile(
                primaryAuthType, authTypes, securityEnabled,
                yaml.jwkSetUri, yaml.issuerUri, yaml.jwtSecret,
                yaml.basicUsername, yaml.basicPassword,
                rules, publicPaths);

        log.info("[SecurityAnalyzer] Result: authTypes={} enabled={} routeRules={} publicPaths={}",
                authTypes, securityEnabled, rules.size(), publicPaths.size());
        return profile;
    }

    // ── Path resolution ────────────────────────────────────────────────────────

    private Path resolveJavaRoot(Path sourceRoot) {
        Path candidate = sourceRoot.resolve("src/main/java");
        return Files.isDirectory(candidate) ? candidate : sourceRoot;
    }

    private Path resolveResourcesRoot(Path sourceRoot, Path resourcesRoot) {
        if (resourcesRoot != null && Files.isDirectory(resourcesRoot)) return resourcesRoot;
        Path candidate = sourceRoot.resolve("src/main/resources");
        if (Files.isDirectory(candidate)) return candidate;
        // sourceRoot might already be src/main/java — try sibling
        Path sibling = sourceRoot.getParent() != null
                ? sourceRoot.getParent().resolve("resources") : sourceRoot;
        return Files.isDirectory(sibling) ? sibling : sourceRoot;
    }

    // ── YAML / properties reading ─────────────────────────────────────────────

    private YamlSecurityConfig readYamlSecurity(Path resourcesRoot) {
        Map<String, Object> appYaml = loadYaml(resourcesRoot.resolve("application.yml"));
        Properties appProps         = loadProperties(resourcesRoot.resolve("application.properties"));

        String jwkSetUri    = firstOf(
                leaf(appYaml, "spring", "security", "oauth2", "resourceserver", "jwt", "jwk-set-uri"),
                appProps.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri"));

        String issuerUri    = firstOf(
                leaf(appYaml, "spring", "security", "oauth2", "resourceserver", "jwt", "issuer-uri"),
                appProps.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"));

        String jwtSecret    = firstOf(
                leaf(appYaml, "spring", "security", "oauth2", "resourceserver", "jwt", "secret"),
                appProps.getProperty("spring.security.oauth2.resourceserver.jwt.secret"),
                leaf(appYaml, "app", "jwt", "secret"),
                appProps.getProperty("app.jwt.secret"));

        String basicUsername = firstOf(
                leaf(appYaml, "spring", "security", "user", "name"),
                appProps.getProperty("spring.security.user.name"));

        String basicPassword = firstOf(
                leaf(appYaml, "spring", "security", "user", "password"),
                appProps.getProperty("spring.security.user.password"));

        return new YamlSecurityConfig(jwkSetUri, issuerUri, jwtSecret, basicUsername, basicPassword);
    }

    // ── Java parsing ──────────────────────────────────────────────────────────

    private List<CompilationUnit> parseAll(Path javaRoot) {
        List<CompilationUnit> cus = new ArrayList<>();
        if (!Files.isDirectory(javaRoot)) return cus;
        try {
            Files.walk(javaRoot)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            javaParser.parse(p).getResult().ifPresent(cus::add);
                        } catch (IOException e) {
                            log.debug("Could not parse {}: {}", p.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("[SecurityAnalyzer] Could not walk source root {}: {}", javaRoot, e.getMessage());
        }
        return cus;
    }

    // ── Auth type detection ───────────────────────────────────────────────────

    /**
     * Detects ALL authentication mechanisms configured in the monolith.
     * Returns an ordered set so the first element is the primary/highest-priority type.
     * This replaces the old single-value detection that stopped at the first match —
     * monoliths may legitimately configure e.g. both OAuth2 and Basic Auth.
     */
    private Set<AuthType> detectAllAuthTypes(List<CompilationUnit> cus, YamlSecurityConfig yaml) {
        Set<AuthType> detected = new LinkedHashSet<>();

        // OAuth2 (highest priority)
        if (yaml.jwkSetUri != null || yaml.issuerUri != null || hasMethodCall("oauth2ResourceServer", cus)) {
            detected.add(AuthType.OAUTH2);
        }

        // Basic Auth
        if (yaml.basicUsername != null || hasMethodCall("httpBasic", cus)) {
            detected.add(AuthType.BASIC);
        }

        // Bearer JWT (custom OncePerRequestFilter with "Bearer " literal)
        if (hasBearerJwtFilter(cus)) {
            detected.add(AuthType.BEARER_JWT);
        }

        // @EnableWebSecurity present but no specific type detected — assume Bearer JWT
        if (detected.isEmpty()) {
            boolean hasWebSecurity;
            if (graph != null) {
                // Graph-based: structural annotation query
                hasWebSecurity = !graph.nodesWithAnnotation("EnableWebSecurity").isEmpty();
            } else {
                // AST fallback
                hasWebSecurity = cus.stream().anyMatch(cu ->
                        cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                                .anyMatch(c -> c.getAnnotationByName("EnableWebSecurity").isPresent()));
            }
            if (hasWebSecurity) {
                log.info("[SecurityAnalyzer] @EnableWebSecurity detected but auth type unclear — assuming BEARER_JWT");
                detected.add(AuthType.BEARER_JWT);
            }
        }

        return detected;
    }

    /** Checks whether any method body contains a call to the given method name. */
    private boolean hasMethodCall(String methodName, List<CompilationUnit> cus) {
        if (graph != null) {
            return !graph.nodesWithMethodCall(methodName).isEmpty();
        }
        // AST fallback
        return cus.stream().anyMatch(cu ->
                cu.findAll(MethodCallExpr.class).stream()
                        .anyMatch(m -> m.getNameAsString().equals(methodName)));
    }

    /**
     * Detects a Bearer JWT filter pattern: a class that extends OncePerRequestFilter
     * and contains "Bearer " or "Authorization" string literals in its methods.
     * Uses graph method-level data (Phase 2) when available.
     */
    private boolean hasBearerJwtFilter(List<CompilationUnit> cus) {
        if (graph != null) {
            // Fully graph-based: superclass check + string literal check
            return graph.nodesMatching(n ->
                    "OncePerRequestFilter".equals(n.superclass())
                    && n.methods().stream().anyMatch(m ->
                            m.stringLiterals().stream().anyMatch(s ->
                                    s.toLowerCase().contains("bearer ")
                                    || s.equalsIgnoreCase("authorization")))
            ).stream().findAny().isPresent();
        }
        // AST fallback
        return cus.stream().anyMatch(cu ->
                cu.findAll(ClassOrInterfaceDeclaration.class).stream().anyMatch(cls -> {
                    boolean extendsFilter = cls.getExtendedTypes().stream()
                            .anyMatch(t -> t.getNameAsString().equals("OncePerRequestFilter"));
                    if (!extendsFilter) return false;
                    return cu.findAll(StringLiteralExpr.class).stream()
                            .anyMatch(s -> s.asString().toLowerCase().contains("bearer ")
                                       || s.asString().equalsIgnoreCase("authorization"));
                }));
    }

    // ── requestMatchers rule extraction ───────────────────────────────────────

    private List<RouteSecurityRule> extractRequestMatcherRules(List<CompilationUnit> cus) {
        List<RouteSecurityRule> rules = new ArrayList<>();

        for (CompilationUnit cu : cus) {
            // Only scan SecurityFilterChain bean methods
            if (!hasSecurityFilterChainBean(cu)) continue;

            cu.findAll(MethodCallExpr.class).forEach(mce -> {
                String name = mce.getNameAsString();

                if ((name.equals("hasRole") || name.equals("hasAnyRole")) && hasScopeNamed(mce, "requestMatchers")) {
                    extractHasRoleRule(mce, rules);
                } else if (name.equals("permitAll") && hasScopeNamed(mce, "requestMatchers")) {
                    extractPermitAllRule(mce, rules);
                }
            });
        }

        return rules;
    }

    private boolean hasSecurityFilterChainBean(CompilationUnit cu) {
        return cu.findAll(MethodDeclaration.class).stream().anyMatch(m ->
                m.getAnnotationByName("Bean").isPresent() &&
                m.getType().asString().contains("SecurityFilterChain"));
    }

    private boolean hasScopeNamed(MethodCallExpr mce, String scopeName) {
        return mce.getScope()
                .filter(s -> s instanceof MethodCallExpr)
                .map(s -> ((MethodCallExpr) s).getNameAsString().equals(scopeName))
                .orElse(false);
    }

    private void extractHasRoleRule(MethodCallExpr mce, List<RouteSecurityRule> rules) {
        // scope is requestMatchers(path) — get path argument
        mce.getScope().ifPresent(scope -> {
            if (!(scope instanceof MethodCallExpr rmScope)) return;
            List<String> paths = extractStringArgs(rmScope);
            List<String> roles = extractRolesFromHasRole(mce);
            for (String path : paths) {
                if (!path.isBlank()) {
                    rules.add(new RouteSecurityRule(path, roles, false));
                    log.debug("[SecurityAnalyzer] Rule: {} → roles={}", path, roles);
                }
            }
        });
    }

    private void extractPermitAllRule(MethodCallExpr mce, List<RouteSecurityRule> rules) {
        mce.getScope().ifPresent(scope -> {
            if (!(scope instanceof MethodCallExpr rmScope)) return;
            List<String> paths = extractStringArgs(rmScope);
            for (String path : paths) {
                if (!path.isBlank()) {
                    rules.add(new RouteSecurityRule(path, List.of(), true));
                    log.debug("[SecurityAnalyzer] Public path: {}", path);
                }
            }
        });
    }

    private List<String> extractStringArgs(MethodCallExpr mce) {
        List<String> result = new ArrayList<>();
        mce.getArguments().forEach(arg -> {
            if (arg instanceof StringLiteralExpr s) {
                result.add(s.asString());
            }
        });
        return result;
    }

    private List<String> extractRolesFromHasRole(MethodCallExpr mce) {
        List<String> roles = new ArrayList<>();
        mce.getArguments().forEach(arg -> {
            if (arg instanceof StringLiteralExpr s) {
                // Strip ROLE_ prefix if present
                Matcher m = ROLE_STRIP_PREFIX.matcher(s.asString().replaceFirst("^ROLE_", ""));
                if (m.matches()) roles.add(m.group(1));
            }
        });
        return roles;
    }

    // ── @PreAuthorize rule extraction ─────────────────────────────────────────

    private List<RouteSecurityRule> extractPreAuthorizeRules(List<CompilationUnit> cus) {
        List<RouteSecurityRule> rules = new ArrayList<>();

        for (CompilationUnit cu : cus) {
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                boolean isController;
                if (graph != null) {
                    // Graph-based: structural annotation query
                    String fqcn = cu.getPackageDeclaration()
                            .map(pd -> pd.getNameAsString() + "." + cls.getNameAsString())
                            .orElse(cls.getNameAsString());
                    var node = graph.node(fqcn);
                    isController = node.isPresent() && (node.get().annotations().contains("RestController")
                            || node.get().annotations().contains("Controller"));
                } else {
                    isController = cls.getAnnotationByName("RestController").isPresent()
                            || cls.getAnnotationByName("Controller").isPresent();
                }
                if (!isController) return;

                String basePath = extractMappingPath(cls);

                cls.getMethods().forEach(method -> {
                    method.getAnnotationByName("PreAuthorize").ifPresent(preAuth -> {
                        String expression = annotationValue(preAuth);
                        if (expression == null || expression.contains("#")) {
                            // Skip SpEL expressions like #id == principal.id
                            log.debug("[SecurityAnalyzer] Skipping complex @PreAuthorize: {}", expression);
                            return;
                        }
                        List<String> roles = extractRolesFromExpression(expression);
                        if (roles.isEmpty()) return;

                        String methodPath = extractMethodMappingPath(method);
                        String fullPath   = normalizePath(basePath + methodPath);

                        rules.add(new RouteSecurityRule(fullPath, roles, false));
                        log.debug("[SecurityAnalyzer] @PreAuthorize rule: {} → {}", fullPath, roles);
                    });
                });
            });
        }

        return rules;
    }

    private String extractMappingPath(ClassOrInterfaceDeclaration cls) {
        for (String ann : List.of("RequestMapping", "GetMapping", "PostMapping", "PutMapping",
                "DeleteMapping", "PatchMapping")) {
            String path = extractPathFromAnnotation(cls.getAnnotationByName(ann).orElse(null));
            if (path != null) return path;
        }
        return "";
    }

    private String extractMethodMappingPath(MethodDeclaration method) {
        for (String ann : List.of("GetMapping", "PostMapping", "PutMapping",
                "DeleteMapping", "PatchMapping", "RequestMapping")) {
            String path = extractPathFromAnnotation(method.getAnnotationByName(ann).orElse(null));
            if (path != null) return path;
        }
        return "";
    }

    private String extractPathFromAnnotation(AnnotationExpr ann) {
        if (ann == null) return null;
        // @GetMapping("/path") — single string value
        if (ann.isSingleMemberAnnotationExpr()) {
            String val = ann.asSingleMemberAnnotationExpr().getMemberValue().toString();
            return val.replace("\"", "");
        }
        // @RequestMapping(value = "/path") or @RequestMapping(path = "/path")
        if (ann.isNormalAnnotationExpr()) {
            return ann.asNormalAnnotationExpr().getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("value") || p.getNameAsString().equals("path"))
                    .findFirst()
                    .map(p -> p.getValue().toString().replace("\"", ""))
                    .orElse(null);
        }
        return null;
    }

    private String annotationValue(AnnotationExpr ann) {
        if (ann.isSingleMemberAnnotationExpr()) {
            return ann.asSingleMemberAnnotationExpr().getMemberValue().toString()
                    .replace("\"", "");
        }
        if (ann.isNormalAnnotationExpr()) {
            return ann.asNormalAnnotationExpr().getPairs().stream()
                    .findFirst()
                    .map(p -> p.getValue().toString().replace("\"", ""))
                    .orElse(null);
        }
        return null;
    }

    private List<String> extractRolesFromExpression(String expression) {
        List<String> roles = new ArrayList<>();
        Matcher m = ROLE_IN_PRE_AUTHORIZE.matcher(expression);
        while (m.find()) {
            if (m.group(1) != null) {
                roles.add(m.group(1));
            } else if (m.group(2) != null) {
                // hasAnyRole('ROLE_ADMIN', 'USER') — parse comma-separated
                Arrays.stream(m.group(2).split(","))
                        .map(s -> s.trim().replace("'", "").replaceFirst("^ROLE_", ""))
                        .filter(s -> !s.isBlank())
                        .forEach(roles::add);
            }
        }
        return roles;
    }

    // ── Public path collection ────────────────────────────────────────────────

    private List<String> collectPublicPaths(List<RouteSecurityRule> rules) {
        return rules.stream()
                .filter(RouteSecurityRule::permitAll)
                .map(RouteSecurityRule::pathPattern)
                .toList();
    }

    // ── YAML helpers ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYaml(Path path) {
        if (!Files.exists(path)) return Map.of();
        try (InputStream is = Files.newInputStream(path)) {
            Object loaded = new Yaml().load(is);
            if (loaded instanceof Map<?, ?> m) return (Map<String, Object>) m;
        } catch (Exception e) {
            log.debug("Could not parse {}: {}", path.getFileName(), e.getMessage());
        }
        return Map.of();
    }

    private Properties loadProperties(Path path) {
        Properties p = new Properties();
        if (!Files.exists(path)) return p;
        try (InputStream is = Files.newInputStream(path)) {
            p.load(is);
        } catch (IOException e) {
            log.debug("Could not read {}: {}", path.getFileName(), e.getMessage());
        }
        return p;
    }

    @SuppressWarnings("unchecked")
    private String leaf(Map<String, Object> map, String... keys) {
        if (map == null || map.isEmpty()) return null;
        Map<String, Object> cur = map;
        for (int i = 0; i < keys.length - 1; i++) {
            Object next = cur.get(keys[i]);
            if (!(next instanceof Map)) return null;
            cur = (Map<String, Object>) next;
        }
        Object val = cur.get(keys[keys.length - 1]);
        return val != null ? val.toString() : null;
    }

    private String firstOf(String... candidates) {
        for (String c : candidates) if (c != null && !c.isBlank()) return c;
        return null;
    }

    private String normalizePath(String path) {
        String normalized = path.replace("//", "/");
        if (!normalized.startsWith("/")) normalized = "/" + normalized;
        return normalized;
    }

    // ── Internal config holder ────────────────────────────────────────────────

    private record YamlSecurityConfig(
            String jwkSetUri,
            String issuerUri,
            String jwtSecret,
            String basicUsername,
            String basicPassword) {}
}
