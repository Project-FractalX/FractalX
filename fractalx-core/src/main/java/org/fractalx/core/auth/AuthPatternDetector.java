package org.fractalx.core.auth;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.fractalx.core.graph.DependencyGraph;
import org.fractalx.core.graph.GraphNode;
import org.fractalx.core.naming.EnglishPluralizer;
import org.fractalx.core.naming.NamingConventions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Scans the monolith source tree to detect whether it has a self-contained JWT auth pattern:
 * <ul>
 *   <li>A {@code @RestController} mapped to {@code /api/auth} (login + register endpoints)</li>
 *   <li>An entity implementing {@code UserDetails} (JPA user store)</li>
 *   <li>A {@code UserDetailsService} implementation</li>
 *   <li>{@code jwt.secret} and {@code jwt.expiration-ms} properties</li>
 * </ul>
 *
 * <p>When detected, {@link AuthServiceGenerator} is called to produce a standalone
 * {@code auth-service} that closes the auth gap left by decomposition.
 */
public class AuthPatternDetector {

    private static final Logger log = LoggerFactory.getLogger(AuthPatternDetector.class);

    /** Fields present in every Spring Security UserDetails entity — not domain-specific. */
    private static final Set<String> STANDARD_USER_FIELDS = Set.of(
            "id", "username", "password", "role", "roles", "enabled",
            "authorities", "version", "createdAt", "updatedAt", "createdDate", "lastModifiedDate");

    /** JPA relationship annotations — fields with these are not copied as JWT claims. */
    private static final Set<String> RELATIONSHIP_ANNOTATIONS = Set.of(
            "OneToMany", "ManyToOne", "OneToOne", "ManyToMany", "Embedded", "EmbeddedId");

    /** Path fragments that identify account-creation (registration) endpoints. */
    private static final Set<String> REGISTRATION_PATHS = Set.of(
            "/register", "/signup", "/sign-up", "/enroll", "/create-account");

    private static final EnglishPluralizer PLURALIZER =
            new EnglishPluralizer(NamingConventions.defaults().irregularPlurals());

    private final Path projectRoot;
    private final DependencyGraph graph;

    public AuthPatternDetector(Path projectRoot) {
        this(projectRoot, null);
    }

    /** Graph-aware constructor — uses structural queries for class-level checks. */
    public AuthPatternDetector(Path projectRoot, DependencyGraph graph) {
        this.projectRoot = projectRoot;
        this.graph = graph;
    }

    /**
     * Runs detection and returns an {@link AuthPattern}. Never throws — returns
     * {@link AuthPattern#none()} on any error so the pipeline can continue safely.
     */
    public AuthPattern detect() {
        try {
            return doDetect();
        } catch (Exception e) {
            log.debug("AuthPatternDetector failed — treating as no-auth-pattern: {}", e.getMessage());
            return AuthPattern.none();
        }
    }

    private AuthPattern doDetect() throws IOException {
        // ── 1. Load JWT properties ──────────────────────────────────────────
        Map<String, String> props = loadProperties();
        String jwtSecret    = props.getOrDefault("jwt.secret",
                             props.get("jwt-secret"));
        String expStr       = props.getOrDefault("jwt.expiration-ms",
                             props.get("jwt.expiration"));
        long expirationMs   = 86_400_000L;
        if (expStr != null) {
            try { expirationMs = Long.parseLong(expStr.trim()); } catch (NumberFormatException ignored) {}
        }

        // ── 2. Walk source files ────────────────────────────────────────────
        Path srcMain = projectRoot.resolve("src/main/java");
        if (!Files.isDirectory(srcMain)) return AuthPattern.none();

        AtomicReference<String> authPkg        = new AtomicReference<>();
        AtomicReference<String> userDetailsPkg = new AtomicReference<>();
        AtomicReference<Map<String, String>> domainFieldsRef = new AtomicReference<>(new HashMap<>());

        JavaParser parser = new JavaParser();

        try (Stream<Path> paths = Files.walk(srcMain)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                 .forEach(p -> scan(p, parser, authPkg, userDetailsPkg, domainFieldsRef));
        }

        boolean detected = jwtSecret != null
                && authPkg.get() != null
                && userDetailsPkg.get() != null;

        if (detected) {
            log.info("Auth pattern detected — authPkg={} userPkg={} domainFields={}",
                    authPkg.get(), userDetailsPkg.get(), domainFieldsRef.get().keySet());
        } else {
            log.debug("No auth pattern detected (jwtSecret={} authPkg={} userPkg={})",
                    jwtSecret != null, authPkg.get(), userDetailsPkg.get());
        }

        // Detect cross-module service call in AuthController.register() for linked entity creation
        String[] linkedService = detected
                ? detectRegisterLinkedService(srcMain, authPkg.get(), userDetailsPkg.get(), parser)
                : null;

        return new AuthPattern(detected, jwtSecret, expirationMs, userDetailsPkg.get(), authPkg.get(),
                               Map.copyOf(domainFieldsRef.get()),
                               linkedService != null ? linkedService[0] : null,
                               linkedService != null ? linkedService[1] : null,
                               linkedService != null ? linkedService[2] : null);
    }

    private void scan(Path javaFile, JavaParser parser,
                      AtomicReference<String> authPkg,
                      AtomicReference<String> userDetailsPkg,
                      AtomicReference<Map<String, String>> domainFieldsRef) {
        try {
            CompilationUnit cu = parser.parse(javaFile).getResult().orElse(null);
            if (cu == null) return;

            String pkg = cu.getPackageDeclaration()
                           .map(pd -> pd.getNameAsString())
                           .orElse("");

            for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {

                // ── Detect auth controller ──────────────────────────────────
                if (authPkg.get() == null) {
                    boolean isAuthClass = false;

                    // 1. @RestController with auth-related request mappings
                    // Use graph for annotation check when available
                    if (hasAnnotationStructural(cls, pkg, "RestController")) {
                        String fqcn = pkg.isEmpty() ? cls.getNameAsString() : pkg + "." + cls.getNameAsString();
                        isAuthClass = hasAuthMappings(cls, fqcn);
                        // Check method parameters for Spring Security types (structural via graph)
                        if (!isAuthClass) {
                            isAuthClass = hasSecurityMethodParams(cls, fqcn);
                        }
                    }

                    // 2. @EnableWebSecurity configuration class
                    if (!isAuthClass) {
                        isAuthClass = hasAnnotationStructural(cls, pkg, "EnableWebSecurity");
                    }

                    // 3. Class implements UserDetailsService (structural check via graph or AST)
                    if (!isAuthClass) {
                        isAuthClass = implementsInterface(cls, pkg, "UserDetailsService");
                    }

                    // 4. Class has a method returning SecurityFilterChain (structural via graph)
                    if (!isAuthClass) {
                        isAuthClass = hasMethodReturningFilterChain(cls, pkg);
                    }

                    if (isAuthClass) {
                        authPkg.set(pkg);
                        log.debug("Auth controller found in package {}", pkg);
                    }
                }

                // ── Detect UserDetails entity ───────────────────────────────
                if (userDetailsPkg.get() == null) {
                    boolean implementsUD = implementsInterface(cls, pkg, "UserDetails");
                    if (implementsUD && hasAnnotationStructural(cls, pkg, "Entity")) {
                        userDetailsPkg.set(pkg);
                        // Collect domain-specific fields that should propagate into JWT claims.
                        // Structural approach: read .claim("X", ...) calls from the monolith's
                        // token-generation code to know exactly which claims are set.
                        Map<String, String> fields = extractDomainFields(cls);
                        domainFieldsRef.set(fields);
                        log.debug("UserDetails entity found in package {}, domainFields={}", pkg, fields.keySet());
                    }
                }

                // ── Detect UserDetailsService impl (tracked via userDetailsPkg) ──
            }
        } catch (Exception e) {
            log.trace("Could not parse {} for auth pattern: {}", javaFile.getFileName(), e.getMessage());
        }
    }

    private boolean hasAnnotation(ClassOrInterfaceDeclaration cls, String name) {
        return cls.getAnnotations().stream()
                  .anyMatch(a -> {
                      String n = a.getNameAsString();
                      // Match simple name or fully-qualified (e.g. @jakarta.persistence.Entity)
                      return n.equals(name) || n.endsWith("." + name);
                  });
    }

    /**
     * Checks for an annotation using the DependencyGraph (structural) when available,
     * falling back to AST-based name matching.
     */
    private boolean hasAnnotationStructural(ClassOrInterfaceDeclaration cls, String pkg, String annotationName) {
        if (graph != null) {
            String fqcn = pkg.isEmpty() ? cls.getNameAsString() : pkg + "." + cls.getNameAsString();
            var node = graph.node(fqcn);
            if (node.isPresent()) return node.get().annotations().contains(annotationName);
        }
        return hasAnnotation(cls, annotationName);
    }

    /**
     * Checks if a class implements a given interface using the DependencyGraph (structural)
     * when available, falling back to AST-based name matching.
     */
    private boolean implementsInterface(ClassOrInterfaceDeclaration cls, String pkg, String interfaceName) {
        if (graph != null) {
            String fqcn = pkg.isEmpty() ? cls.getNameAsString() : pkg + "." + cls.getNameAsString();
            var node = graph.node(fqcn);
            if (node.isPresent()) return node.get().implementedInterfaces().contains(interfaceName);
        }
        return cls.getImplementedTypes().stream()
                .anyMatch(t -> t.getNameAsString().equals(interfaceName));
    }

    /** Checks for auth-related request mapping annotations — via graph method-level data or AST. */
    private boolean hasAuthMappings(ClassOrInterfaceDeclaration cls, String fqcn) {
        if (graph != null) {
            var node = graph.node(fqcn);
            if (node.isPresent()) {
                return node.get().methods().stream().anyMatch(m ->
                        m.annotations().stream().anyMatch(a ->
                                isHttpMappingAnnotation(a) && m.stringLiterals().stream()
                                        .anyMatch(s -> AUTH_PATH_FRAGMENTS.stream()
                                                .anyMatch(frag -> s.toLowerCase().contains(frag)))));
            }
        }
        // AST fallback
        return cls.getAnnotations().stream().anyMatch(this::isRequestMappingToAuth)
                || cls.getMethods().stream()
                   .anyMatch(m -> m.getAnnotations().stream()
                                  .anyMatch(this::isMappingToAuthLogin));
    }

    private static boolean isHttpMappingAnnotation(String name) {
        return Set.of("RequestMapping", "GetMapping", "PostMapping",
                "PutMapping", "DeleteMapping", "PatchMapping").contains(name);
    }

    /** Checks for Spring Security parameter types (Authentication, Principal, @AuthenticationPrincipal). */
    private boolean hasSecurityMethodParams(ClassOrInterfaceDeclaration cls, String fqcn) {
        if (graph != null) {
            var node = graph.node(fqcn);
            if (node.isPresent()) {
                return node.get().methods().stream().anyMatch(m ->
                        m.parameterTypes().stream().anyMatch(pt ->
                                pt.equals("Authentication") || pt.equals("Principal"))
                        || m.annotations().contains("AuthenticationPrincipal"));
            }
        }
        // AST fallback
        return cls.getMethods().stream().anyMatch(m ->
                m.getParameters().stream().anyMatch(p -> {
                    String pType = p.getType().asString();
                    return pType.equals("Authentication")
                        || pType.equals("Principal")
                        || p.getAnnotations().stream()
                            .anyMatch(a -> a.getNameAsString().equals("AuthenticationPrincipal"));
                }));
    }

    /** Checks for a method returning SecurityFilterChain — via graph or AST. */
    private boolean hasMethodReturningFilterChain(ClassOrInterfaceDeclaration cls, String pkg) {
        if (graph != null) {
            String fqcn = pkg.isEmpty() ? cls.getNameAsString() : pkg + "." + cls.getNameAsString();
            var node = graph.node(fqcn);
            if (node.isPresent()) {
                return node.get().methods().stream()
                        .anyMatch(m -> "SecurityFilterChain".equals(m.returnType()));
            }
        }
        // AST fallback
        return cls.getMethods().stream()
                .anyMatch(m -> m.getType().asString().equals("SecurityFilterChain"));
    }

    private static final Set<String> AUTH_PATH_FRAGMENTS = Set.of(
            "/api/auth", "/auth", "/login", "/logout", "/register", "/signup",
            "/signin", "/oauth", "/token", "/refresh-token", "/forgot-password",
            "/sso", "/identity", "/session"
    );

    private boolean isRequestMappingToAuth(AnnotationExpr ann) {
        String name = ann.getNameAsString();
        if (!name.equals("RequestMapping")) return false;
        String str = ann.toString().toLowerCase();
        return AUTH_PATH_FRAGMENTS.stream().anyMatch(str::contains);
    }

    private boolean isMappingToAuthLogin(AnnotationExpr ann) {
        String name = ann.getNameAsString();
        if (!name.equals("PostMapping") && !name.equals("GetMapping")
                && !name.equals("RequestMapping")) return false;
        String str = ann.toString().toLowerCase();
        return AUTH_PATH_FRAGMENTS.stream().anyMatch(str::contains);
    }

    /**
     * Scans the monolith's auth controller (in {@code authPkg}) for a cross-module service
     * injection whose "create" method is called inside the register endpoint. Returns a
     * 3-element array: [generatedServiceName, apiPath, idFieldName], or {@code null} if
     * no such pattern is found.
     *
     * <p>Example: if {@code AuthController.register()} calls
     * {@code customerService.createCustomer(customer)} and {@code CustomerService} lives
     * in a package different from {@code authPkg}, this returns
     * {@code ["customer-service", "/api/customers", "customerId"]}.
     */
    private String[] detectRegisterLinkedService(Path srcMain, String authPkg, String userPkg,
                                                  JavaParser parser) {
        if (authPkg == null) return null;

        // Walk the entire source tree — the auth controller may live in a different
        // package than the security configuration that triggered authPkg detection.
        try (Stream<Path> files = Files.walk(srcMain)) {
            Optional<String[]> result = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .flatMap(p -> {
                        try {
                            CompilationUnit cu = parser.parse(p).getResult().orElse(null);
                            if (cu == null) return Stream.empty();

                            // Build a map: field/param simple type → declared variable name
                            // for services injected into the auth controller
                            Map<String, String> injectedServices = new HashMap<>();
                            for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                                if (!hasAnnotation(cls, "RestController")) continue;

                                // Constructor parameters — identify service types structurally
                                cls.getConstructors().forEach(ctor ->
                                        ctor.getParameters().forEach(param -> {
                                            String type = param.getTypeAsString();
                                            if (isServiceType(type))
                                                injectedServices.put(param.getNameAsString(), type);
                                        }));
                                // Field injections — identify service types structurally
                                cls.getFields().forEach(field ->
                                        field.getVariables().forEach(v -> {
                                            String type = field.getElementType().asString();
                                            if (isServiceType(type))
                                                injectedServices.put(v.getNameAsString(), type);
                                        }));

                                // Structural: find POST endpoint mapped to a registration path
                                for (MethodDeclaration method : cls.getMethods()) {
                                    if (!hasRegistrationMapping(method)) continue;

                                    // Structural (data-flow): derive the id field from the user entity
                                    // setter — e.g. user.setCustomerId(...) → idField = "customerId".
                                    // This replaces the heuristic startsWith("create") on the service
                                    // method name, because the *effect* (setting an id on the user) is
                                    // the structural signal, not what the service method is called.
                                    String idField = findIdSetterOnUser(method, injectedServices);
                                    if (idField == null) continue;

                                    // Find the cross-module service call in this registration endpoint
                                    for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                                        if (call.getScope().isEmpty()) continue;
                                        if (!(call.getScope().get() instanceof NameExpr scope)) continue;

                                        String varName    = scope.getNameAsString();
                                        String serviceType = injectedServices.get(varName);
                                        if (serviceType == null) continue;

                                        // Check this service is from a different package (cross-module)
                                        String serviceTypePkg = resolveServicePackage(cu, serviceType);
                                        if (serviceTypePkg != null && serviceTypePkg.equals(authPkg)) continue;
                                        if (serviceTypePkg != null && serviceTypePkg.equals(userPkg)) continue;

                                        // Derive service name from type: CustomerService → customer-service
                                        String baseTypeName = serviceType.replace("Service", "");
                                        String serviceName  = toKebabCase(baseTypeName) + "-service";

                                        // Derive API path from idField: "customerId" → "customer" → pluralize
                                        String entityLower = idField.endsWith("Id")
                                                ? idField.substring(0, idField.length() - 2) : idField;
                                        String apiPath = "/api/" + PLURALIZER.pluralize(entityLower);

                                        log.debug("Detected register cross-module call: {}.{}() → {} {} idField={}",
                                                varName, call.getNameAsString(), serviceName, apiPath, idField);
                                        return Stream.<String[]>of(new String[]{serviceName, apiPath, idField});
                                    }
                                }
                            }
                            return Stream.empty();
                        } catch (Exception e) {
                            return Stream.empty();
                        }
                    })
                    .findFirst();
            return result.orElse(null);
        } catch (IOException e) {
            log.debug("detectRegisterLinkedService: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Determines if a type is a service class. Uses the graph to check for @Service/@Component
     * annotations (structural) when available; falls back to name suffix matching.
     */
    private boolean isServiceType(String simpleTypeName) {
        if (graph == null) return false;
        // Structural: check if any node with this simple name has a @Service or @Component annotation
        return graph.nodesMatching(n -> n.simpleName().equals(simpleTypeName)
                && (n.annotations().contains("Service") || n.annotations().contains("Component")))
                .stream().findAny().isPresent();
    }

    /**
     * Returns true if {@code method} is a POST endpoint mapped to a registration path
     * (structural check via {@code @PostMapping}/@{@code RequestMapping} annotation value).
     */
    private static boolean hasRegistrationMapping(MethodDeclaration method) {
        for (AnnotationExpr ann : method.getAnnotations()) {
            String name = ann.getNameAsString();
            if (!name.equals("PostMapping") && !name.equals("RequestMapping")) continue;
            String val = ann.toString().toLowerCase();
            if (REGISTRATION_PATHS.stream().anyMatch(val::contains)) return true;
        }
        return false;
    }

    /**
     * Scans {@code method} for a {@code setXxxId(...)} call whose receiver is NOT itself an
     * injected service — meaning it's called on the user entity being constructed. Returns
     * the id field name (e.g. {@code "customerId"}) or {@code null} if none found.
     *
     * <p>This is the structural replacement for the heuristic {@code startsWith("create")}:
     * instead of guessing what service method creates the entity, we observe the *effect* —
     * which id field is written back onto the user object.
     */
    private static String findIdSetterOnUser(MethodDeclaration method,
                                              Map<String, String> injectedServices) {
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            if (call.getScope().isEmpty()) continue;
            if (!(call.getScope().get() instanceof NameExpr scope)) continue;
            String callName  = call.getNameAsString();
            String scopeName = scope.getNameAsString();
            // setXxxId on a variable that is NOT an injected service → it's on the user entity
            if (callName.startsWith("set") && callName.endsWith("Id") && callName.length() > 5
                    && !injectedServices.containsKey(scopeName)) {
                return Character.toLowerCase(callName.charAt(3)) + callName.substring(4);
            }
        }
        return null;
    }

    /** Returns the package of the first import matching the given simple type name, or null. */
    private static String resolveServicePackage(CompilationUnit cu, String simpleType) {
        return cu.getImports().stream()
                .filter(i -> !i.isStatic() && !i.isAsterisk())
                .filter(i -> i.getNameAsString().endsWith("." + simpleType))
                .map(i -> {
                    String fqn = i.getNameAsString();
                    int dot = fqn.lastIndexOf('.');
                    return dot > 0 ? fqn.substring(0, dot) : "";
                })
                .findFirst()
                .orElse(null);
    }

    /**
     * Extracts domain-specific fields from a UserDetails entity that should propagate as JWT claims.
     *
     * <p><b>Structural approach (graph available):</b> Queries the dependency graph for all
     * {@code .claim("X", ...)} call-site arguments across classes that generate tokens. The
     * returned fields are those entity fields whose names match actual claim names found in
     * the monolith's token-generation code — no heuristic exclusion list needed.
     *
     * <p><b>Fallback (no graph):</b> Excludes {@link #STANDARD_USER_FIELDS} and JPA relationship
     * fields, returning everything else as a domain field.
     */
    private Map<String, String> extractDomainFields(ClassOrInterfaceDeclaration cls) {
        // Collect all declared fields on the entity (name → type)
        Map<String, String> allFields = new LinkedHashMap<>();
        for (FieldDeclaration f : cls.getFields()) {
            if (f.getVariables().isEmpty()) continue;
            String fieldName = f.getVariables().get(0).getNameAsString();
            boolean hasRelationship = f.getAnnotations().stream()
                    .anyMatch(a -> RELATIONSHIP_ANNOTATIONS.contains(a.getNameAsString()));
            if (hasRelationship) continue;
            allFields.put(fieldName, f.getElementType().asString());
        }

        if (graph != null) {
            // Structural: find all .claim("X", ...) arguments from token-generation code
            Set<String> claimNames = graph.callArgumentsFor(
                    n -> n.methods().stream().anyMatch(m -> m.bodyMethodCalls().contains("claim")),
                    "claim");

            if (!claimNames.isEmpty()) {
                // Return only fields whose names match actual claim names
                Map<String, String> result = new LinkedHashMap<>();
                for (Map.Entry<String, String> entry : allFields.entrySet()) {
                    if (claimNames.contains(entry.getKey())) {
                        result.put(entry.getKey(), entry.getValue());
                    }
                }
                log.debug("Structural claim extraction: claimNames={}, matched fields={}", claimNames, result.keySet());
                return result;
            }
            // If no .claim() calls found in the graph, fall through to heuristic fallback
            log.debug("No .claim() call sites found in graph — falling back to field exclusion");
        }

        // Fallback: exclude standard UserDetails fields
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : allFields.entrySet()) {
            if (!STANDARD_USER_FIELDS.contains(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /** Converts CamelCase to kebab-case: {@code CustomerAddress} → {@code customer-address}. */
    private static String toKebabCase(String camel) {
        return camel.replaceAll("([A-Z])", "-$1").toLowerCase().replaceFirst("^-", "");
    }

    // ── Property loading ────────────────────────────────────────────────────

    private Map<String, String> loadProperties() {
        Map<String, String> result = new LinkedHashMap<>();

        // application.properties (flat key=value)
        Path propsFile = projectRoot.resolve("src/main/resources/application.properties");
        if (Files.exists(propsFile)) {
            try {
                for (String line : Files.readAllLines(propsFile)) {
                    line = line.strip();
                    if (line.startsWith("#") || !line.contains("=")) continue;
                    int eq = line.indexOf('=');
                    result.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
                }
            } catch (IOException e) {
                log.debug("Could not read application.properties: {}", e.getMessage());
            }
        }

        // application.yml — flatten nested keys
        for (String name : new String[]{"application.yml", "application.yaml"}) {
            Path yml = projectRoot.resolve("src/main/resources/" + name);
            if (Files.exists(yml)) {
                try (InputStream is = Files.newInputStream(yml)) {
                    Object loaded = new Yaml().load(is);
                    if (loaded instanceof Map<?, ?> map) {
                        flattenMap(castMap(map), "", result);
                    }
                } catch (Exception e) {
                    log.debug("Could not parse {}: {}", name, e.getMessage());
                }
            }
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    private static void flattenMap(Map<String, Object> map, String prefix,
                                    Map<String, String> out) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            if (e.getValue() instanceof Map<?, ?> nested) {
                flattenMap(castMap(nested), key, out);
            } else if (e.getValue() != null) {
                out.put(key, e.getValue().toString());
            }
        }
    }
}
