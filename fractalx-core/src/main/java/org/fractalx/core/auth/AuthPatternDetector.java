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
import com.github.javaparser.ast.expr.ObjectCreationExpr;
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

    private final Path projectRoot;

    public AuthPatternDetector(Path projectRoot) {
        this.projectRoot = projectRoot;
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
                    if (hasAnnotation(cls, "RestController")) {
                        isAuthClass = cls.getAnnotations().stream()
                                .anyMatch(this::isRequestMappingToAuth)
                                || cls.getMethods().stream()
                                   .anyMatch(m -> m.getAnnotations().stream()
                                                  .anyMatch(this::isMappingToAuthLogin));
                        // Also check method parameters for Spring Security types
                        if (!isAuthClass) {
                            isAuthClass = cls.getMethods().stream().anyMatch(m ->
                                    m.getParameters().stream().anyMatch(p -> {
                                        String pType = p.getType().asString();
                                        return pType.equals("Authentication")
                                            || pType.equals("Principal")
                                            || p.getAnnotations().stream()
                                                .anyMatch(a -> a.getNameAsString().equals("AuthenticationPrincipal"));
                                    }));
                        }
                    }

                    // 2. @EnableWebSecurity configuration class
                    if (!isAuthClass) {
                        isAuthClass = hasAnnotation(cls, "EnableWebSecurity");
                    }

                    // 3. Class implements UserDetailsService
                    if (!isAuthClass) {
                        isAuthClass = cls.getImplementedTypes().stream()
                                .anyMatch(t -> t.getNameAsString().equals("UserDetailsService"));
                    }

                    // 4. Class has a method returning SecurityFilterChain
                    if (!isAuthClass) {
                        isAuthClass = cls.getMethods().stream()
                                .anyMatch(m -> m.getType().asString().equals("SecurityFilterChain"));
                    }

                    if (isAuthClass) {
                        authPkg.set(pkg);
                        log.debug("Auth controller found in package {}", pkg);
                    }
                }

                // ── Detect UserDetails entity ───────────────────────────────
                if (userDetailsPkg.get() == null) {
                    boolean implementsUD = cls.getImplementedTypes().stream()
                            .anyMatch(t -> t.getNameAsString().equals("UserDetails"));
                    if (implementsUD && hasAnnotation(cls, "Entity")) {
                        userDetailsPkg.set(pkg);
                        // Collect domain-specific fields that should propagate into JWT claims
                        Map<String, String> fields = new HashMap<>();
                        for (FieldDeclaration f : cls.getFields()) {
                            if (f.getVariables().isEmpty()) continue;
                            String fieldName = f.getVariables().get(0).getNameAsString();
                            if (STANDARD_USER_FIELDS.contains(fieldName)) continue;
                            boolean hasRelationship = f.getAnnotations().stream()
                                    .anyMatch(a -> RELATIONSHIP_ANNOTATIONS.contains(a.getNameAsString()));
                            if (hasRelationship) continue;
                            fields.put(fieldName, f.getElementType().asString());
                        }
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
        String authPkgPath = authPkg.replace('.', '/');
        Path authDir = srcMain.resolve(authPkgPath);
        if (!Files.isDirectory(authDir)) return null;

        try (Stream<Path> files = Files.walk(authDir)) {
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

                                // Constructor parameters
                                cls.getConstructors().forEach(ctor ->
                                        ctor.getParameters().forEach(param -> {
                                            String type = param.getTypeAsString();
                                            if (type.endsWith("Service"))
                                                injectedServices.put(param.getNameAsString(), type);
                                        }));
                                // Field injections
                                cls.getFields().forEach(field ->
                                        field.getVariables().forEach(v -> {
                                            String type = field.getElementType().asString();
                                            if (type.endsWith("Service"))
                                                injectedServices.put(v.getNameAsString(), type);
                                        }));

                                // Find the register method
                                for (MethodDeclaration method : cls.getMethods()) {
                                    if (!method.getNameAsString().toLowerCase().contains("register")) continue;

                                    // Look for: injectedService.createXxx(new EntityType(...)) calls
                                    for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                                        if (call.getScope().isEmpty()) continue;
                                        if (!(call.getScope().get() instanceof NameExpr scope)) continue;

                                        String varName   = scope.getNameAsString();
                                        String serviceType = injectedServices.get(varName);
                                        if (serviceType == null) continue;

                                        String methodName = call.getNameAsString();
                                        if (!methodName.startsWith("create")) continue;

                                        // Derive the entity type from the method name: createCustomer → Customer
                                        String entityType = Character.toUpperCase(methodName.charAt(6))
                                                + methodName.substring(7);
                                        if (entityType.isBlank()) continue;

                                        // Check this service is from a different package (cross-module)
                                        String serviceTypePkg = resolveServicePackage(cu, serviceType);
                                        if (serviceTypePkg != null && serviceTypePkg.equals(authPkg)) continue;
                                        if (serviceTypePkg != null && serviceTypePkg.equals(userPkg)) continue;

                                        // Derive service name: CustomerService → customer-service
                                        String baseTypeName = serviceType.replace("Service", "");
                                        String serviceName  = toKebabCase(baseTypeName) + "-service";

                                        // Derive API path: Customer → /api/customers
                                        String entityLower  = Character.toLowerCase(entityType.charAt(0))
                                                + entityType.substring(1);
                                        String apiPath      = "/api/" + entityLower + "s";

                                        // Derive the id field name: customerId
                                        String idField = entityLower + "Id";

                                        log.debug("Detected register cross-module call: {}.{}() → {} {}",
                                                varName, methodName, serviceName, apiPath);
                                        String[] found = {serviceName, apiPath, idField};
                                        return Stream.<String[]>of(found);
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
