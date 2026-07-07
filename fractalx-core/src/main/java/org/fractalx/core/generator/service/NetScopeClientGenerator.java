package org.fractalx.core.generator.service;

import org.fractalx.core.generator.GenerationContext;
import org.fractalx.core.generator.ServiceFileGenerator;
import org.fractalx.core.model.CrossModuleCall;
import org.fractalx.core.model.FractalModule;
import org.fractalx.core.naming.NameResolver;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generates a {@code @NetScopeClient} interface for each cross-module dependency
 * declared on the current module.
 *
 * <p>For each dependency type (e.g., {@code PaymentService}), this step:
 * <ol>
 *   <li>Locates the target bean's source file in the monolith source root.</li>
 *   <li>Extracts all public, non-static method signatures via AST parsing.</li>
 *   <li>Generates an annotated interface so the client service can invoke the
 *       remote bean as a plain Java method call.</li>
 * </ol>
 */
public class NetScopeClientGenerator implements ServiceFileGenerator {

    private static final Logger log = LoggerFactory.getLogger(NetScopeClientGenerator.class);

    @Override
    public void generate(GenerationContext context) throws IOException {
        FractalModule module = context.getModule();
        List<String> deps = module.getDependencies();

        if (deps.isEmpty()) {
            log.debug("No dependencies for {} – skipping NetScope client generation", module.getServiceName());
            return;
        }

        Path packagePath = toPackagePath(context.getSrcMainJava(), module.getPackageName());
        Files.createDirectories(packagePath);

        for (String beanType : deps) {
            generateClientInterface(beanType, packagePath, context);
        }
    }

    private void generateClientInterface(String beanType, Path packagePath, GenerationContext context) throws IOException {
        String targetServiceName = beanTypeToServiceName(beanType);
        FractalModule targetModule = context.getNameResolver().findModule(targetServiceName, context.getAllModules());

        if (targetModule == null) {
            log.warn("Target module '{}' not found for dependency type '{}' – skipping", targetServiceName, beanType);
            return;
        }

        // Guard: if the source file doesn't exist we cannot extract methods and would write
        // a zero-method interface that causes runtime UnsupportedOperationException on every call.
        Optional<Path> sourceFile = findSourceFile(context.getSourceRoot(), beanType, targetServiceName);
        if (sourceFile.isEmpty()) {
            log.warn("NetScopeClientGenerator: source file not found for '{}' (target service '{}') "
                    + "— skipping client interface generation. If the class lives in a sub-project, "
                    + "ensure it is on the source root passed to the plugin.",
                    beanType, targetServiceName);
            return;
        }

        int grpcPort = targetModule.grpcPort();
        Set<String> requiredImports = new LinkedHashSet<>();
        List<CrossModuleCall> methods = extractPublicMethods(beanType, context.getSourceRoot(), targetServiceName, requiredImports);

        copyRequiredModelClasses(requiredImports, context.getSourceRoot(), context.getSrcMainJava());

        String interfaceName = beanType + "Client";
        Files.writeString(
                packagePath.resolve(interfaceName + ".java"),
                buildClientInterface(context.getModule().getPackageName(), interfaceName,
                        beanType, targetServiceName, grpcPort, methods, requiredImports)
        );

        log.info("Generated NetScope client interface: {} → {} (gRPC :{})",
                interfaceName, targetServiceName, grpcPort);
    }

    // -------------------------------------------------------------------------
    // Source analysis
    // -------------------------------------------------------------------------

    /**
     * Walks the monolith source root to find {@code <beanType>.java}, parses it
     * with JavaParser, and returns a {@link CrossModuleCall} for every public
     * non-static method.
     */
    private List<CrossModuleCall> extractPublicMethods(String beanType, Path sourceRoot,
                                                       String targetServiceName,
                                                       Set<String> requiredImports) {
        List<CrossModuleCall> calls = new ArrayList<>();

        try {
            Optional<Path> sourceFile = findSourceFile(sourceRoot, beanType, targetServiceName);

            if (sourceFile.isEmpty()) {
                log.warn("Source file not found for bean type '{}' – client interface will be empty", beanType);
                return calls;
            }

            CompilationUnit cu = new JavaParser().parse(sourceFile.get()).getResult().orElse(null);
            if (cu == null) return calls;

            // Build simple-name → FQN map from the source file's own imports
            Map<String, String> importMap = new HashMap<>();
            cu.getImports().forEach(imp -> {
                if (!imp.isAsterisk() && !imp.isStatic()) {
                    String fqn = imp.getNameAsString();
                    String simpleName = fqn.substring(fqn.lastIndexOf('.') + 1);
                    importMap.put(simpleName, fqn);
                }
            });

            // Same-package types are never imported in their own source file.
            // Resolve them by scanning the source file's directory.
            Path sourceFileDir = sourceFile.get().getParent();
            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("");

            // Track seen method signatures to handle overloads by appending a numeric suffix
            Set<String> seenMethodNames = new HashSet<>();

            cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(c -> c.getNameAsString().equals(beanType))
                    .findFirst()
                    .ifPresent(classDecl ->
                            classDecl.getMethods().forEach(method -> {
                                if ((method.isPublic() || (classDecl.isInterface() && !method.isPrivate()))
                                        && !method.isStatic()) {
                                    List<String> params = method.getParameters().stream()
                                            // Use getTypeAsString() to preserve generic type parameters
                                            // e.g., List<Product> instead of raw List
                                            .map(p -> p.getTypeAsString() + " " + p.getNameAsString())
                                            .collect(Collectors.toList());

                                    // Preserve generic return type (e.g. List<Product> not just List)
                                    String returnType = method.getTypeAsString();

                                    collectTypeImports(returnType, importMap,
                                            requiredImports, sourceFileDir, packageName);
                                    method.getParameters().forEach(p ->
                                            collectTypeImports(p.getTypeAsString(), importMap,
                                                    requiredImports, sourceFileDir, packageName));

                                    // Extract throws clauses so the generated interface is source-compatible
                                    List<String> thrown = method.getThrownExceptions().stream()
                                            .map(t -> t.asString())
                                            .collect(Collectors.toList());

                                    // Deduplicate overloaded method names by appending numeric suffix
                                    String methodName = method.getNameAsString();
                                    if (!seenMethodNames.add(methodName)) {
                                        int suffix = 2;
                                        while (!seenMethodNames.add(methodName + suffix)) suffix++;
                                        log.debug("Overloaded method {}.{}() — generated as {}{}() in client interface",
                                                beanType, methodName, methodName, suffix);
                                        methodName = methodName + suffix;
                                    }

                                    calls.add(new CrossModuleCall(
                                            beanType,
                                            targetServiceName,
                                            methodName,
                                            returnType,
                                            params,
                                            thrown
                                    ));
                                }
                            })
                    );

        } catch (IOException e) {
            log.error("Failed to extract public methods from bean type '{}'", beanType, e);
        }

        return calls;
    }

    /**
     * Resolves type tokens (including generics like {@code List<Product>}) to their
     * FQNs and adds them to {@code requiredImports}.
     *
     * <p>First checks {@code importMap} (explicit imports in the source file).
     * Falls back to a same-directory lookup for types in the same package as the
     * source bean — those are never imported in their own file.
     */
    private void collectTypeImports(String typeStr, Map<String, String> importMap,
                                    Set<String> requiredImports,
                                    Path sourceFileDir, String packageName) {
        for (String token : typeStr.replaceAll("[<>,?\\[\\]]", " ").split("\\s+")) {
            token = token.trim();
            if (token.isEmpty()) continue;
            String fqn = importMap.get(token);
            if (fqn != null) {
                requiredImports.add(fqn);
            } else if (sourceFileDir != null && !packageName.isEmpty()) {
                // Same-package class — not imported, but lives next to the source file
                Path candidate = sourceFileDir.resolve(token + ".java");
                if (Files.exists(candidate)) {
                    requiredImports.add(packageName + "." + token);
                }
            }
        }
    }

    /**
     * Copies model class files (non-JDK, non-Spring, non-FractalX) referenced in
     * {@code requiredImports} from the monolith source root into the consuming
     * service's {@code src/main/java}, preserving the original package structure.
     *
     * <p>Performs a <em>transitive closure</em> copy: after copying each class, the method
     * also enqueues:
     * <ul>
     *   <li>Classes referenced via explicit imports in the copied file.</li>
     *   <li>Value-type "sibling" files in the same package directory (e.g. {@code @Embeddable}
     *       classes such as {@code CustomerAddress} that are used as fields inside an entity but
     *       never appear in import statements because they are in the same package).</li>
     * </ul>
     * Infrastructure-naming suffixes (Service, Repository, Controller, Module, Config,
     * Configuration) are excluded from the transitive expansion to avoid pulling in
     * entire module implementations.
     */
    private void copyRequiredModelClasses(Set<String> requiredImports, Path sourceRoot,
                                          Path destSrcMain) {
        Deque<String> queue   = new ArrayDeque<>(requiredImports);
        Set<String>   visited = new HashSet<>(requiredImports);

        while (!queue.isEmpty()) {
            String fqn = queue.poll();

            // Skip JDK, Jakarta EE, Spring, and FractalX framework packages.
            if (fqn.startsWith("java.")
                    || fqn.startsWith("javax.")
                    || fqn.startsWith("jakarta.")
                    || fqn.startsWith("org.springframework.")
                    || fqn.startsWith("org.fractalx.")) {
                continue;
            }
            // Skip infrastructure class names (service, repo, controller, etc.)
            String simpleName = fqn.substring(fqn.lastIndexOf('.') + 1);
            if (isInfrastructureClass(simpleName)) continue;

            // Derive relative path, e.g. com.acme.customer.Customer → .../Customer.java
            String relativePath = fqn.replace('.', '/') + ".java";
            Path src = sourceRoot.resolve(relativePath);
            if (!Files.exists(src)) continue;

            Path dest = destSrcMain.resolve(relativePath);
            try {
                Files.createDirectories(dest.getParent());
                if (!Files.exists(dest)) {
                    Files.copy(src, dest);
                    log.info("Copied model class {} → {}", fqn, dest);
                }

                // ── Transitive expansion ───────────────────────────────────────
                String packagePrefix = fqn.contains(".")
                        ? fqn.substring(0, fqn.lastIndexOf('.') + 1) : "";

                // 1. Explicit imports in the copied file
                new JavaParser().parse(src).getResult().ifPresent(cu ->
                    cu.getImports().forEach(imp -> {
                        if (!imp.isAsterisk() && !imp.isStatic()) {
                            String depFqn = imp.getNameAsString();
                            if (visited.add(depFqn)) queue.add(depFqn);
                        }
                    })
                );

                // 2. Same-package siblings — catches @Embeddable, enums, and inner value types
                //    that are used as field types without explicit imports.
                Path srcDir = src.getParent();
                if (Files.isDirectory(srcDir)) {
                    try (var siblings = Files.list(srcDir)) {
                        siblings.filter(f -> f.toString().endsWith(".java"))
                                .forEach(sibling -> {
                                    String sibName = sibling.getFileName().toString()
                                            .replace(".java", "");
                                    if (!isInfrastructureClass(sibName)) {
                                        String sibFqn = packagePrefix + sibName;
                                        if (visited.add(sibFqn)) queue.add(sibFqn);
                                    }
                                });
                    } catch (IOException e) {
                        log.debug("Could not list siblings of {}: {}", srcDir, e.getMessage());
                    }
                }

            } catch (IOException e) {
                log.warn("Could not copy model class {}: {}", fqn, e.getMessage());
            }
        }
    }

    /**
     * Returns {@code true} for class names that follow infrastructure-naming conventions
     * and should never be transitively copied into a consuming service.
     */
    private static boolean isInfrastructureClass(String simpleName) {
        return simpleName.endsWith("Service")
                || simpleName.endsWith("Repository")
                || simpleName.endsWith("Controller")
                || simpleName.endsWith("Module")
                || simpleName.endsWith("Config")
                || simpleName.endsWith("Configuration")
                || simpleName.endsWith("Application")
                || simpleName.endsWith("Listener")
                || simpleName.endsWith("Filter")
                || simpleName.endsWith("Interceptor")
                || simpleName.endsWith("Aspect")
                || simpleName.endsWith("Handler");
    }

    // -------------------------------------------------------------------------
    // Source generation
    // -------------------------------------------------------------------------

    private String buildClientInterface(String packageName,
                                        String interfaceName,
                                        String beanType,
                                        String targetServiceName,
                                        int grpcPort,
                                        List<CrossModuleCall> methods,
                                        Set<String> requiredImports) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import org.fractalx.netscope.client.annotation.NetScopeClient;\n");
        for (String imp : new java.util.TreeSet<>(requiredImports)) {
            sb.append("import ").append(imp).append(";\n");
        }
        sb.append("\n");
        sb.append("/**\n");
        sb.append(" * NetScope client interface for ").append(beanType).append(".\n");
        sb.append(" * Connects to ").append(targetServiceName).append(" on gRPC port ").append(grpcPort).append(".\n");
        sb.append(" * Generated by FractalX Framework.\n");
        sb.append(" */\n");
        sb.append("@NetScopeClient(server = \"").append(targetServiceName)
                .append("\", beanName = \"").append(beanType).append("\")\n");
        sb.append("public interface ").append(interfaceName).append(" {\n\n");

        for (CrossModuleCall method : methods) {
            String params = String.join(", ", method.getParameters());
            sb.append("    ").append(method.getReturnType())
                    .append(" ").append(method.getMethodName())
                    .append("(").append(params).append(")");
            if (!method.getThrownExceptions().isEmpty()) {
                sb.append(" throws ").append(String.join(", ", method.getThrownExceptions()));
            }
            sb.append(";\n\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /**
     * Derives the target service name from a bean type name using convention.
     * <p>Examples: {@code PaymentService → payment-service},
     * {@code OrderClient → order-service},
     * {@code LmsClientV2 → lms-service} (version suffix stripped first).
     */
    public static String beanTypeToServiceName(String beanType) {
        // Strip trailing version suffix (e.g. V2, V3) before stripping Service/Client
        // so LmsClientV2 → LmsClient → lms-service, not lms-client-v2-service
        String versionStripped = beanType.replaceAll("V\\d+$", "");
        String stripped = versionStripped.replaceAll("(Service|Client)$", "");
        return stripped.replaceAll("([A-Z])", "-$1").toLowerCase().replaceFirst("^-", "") + "-service";
    }

    /**
     * Finds the source file for {@code beanType} under {@code sourceRoot}.
     *
     * <p>When multiple files share the same simple name (different packages), the one
     * whose package declaration starts with {@code expectedServiceName} converted to a
     * package prefix is preferred. Falls back to the first match with a warning.
     *
     * @param sourceRoot          monolith {@code src/main/java}
     * @param beanType            simple class name, e.g., {@code PaymentService}
     * @param expectedServiceName logical service name, e.g., {@code payment-service}
     */
    private Optional<Path> findSourceFile(Path sourceRoot, String beanType, String expectedServiceName)
            throws IOException {
        List<Path> candidates;
        try (java.util.stream.Stream<Path> stream = Files.walk(sourceRoot)) {
            candidates = stream
                    .filter(p -> p.getFileName().toString().equals(beanType + ".java"))
                    .collect(java.util.stream.Collectors.toList());
        }

        if (candidates.isEmpty()) return Optional.empty();
        if (candidates.size() == 1) return Optional.of(candidates.get(0));

        // Multiple candidates — prefer the one whose package matches the target service
        JavaParser parser = new JavaParser();
        String pkgHint = expectedServiceName.replace("-", ".");
        for (Path candidate : candidates) {
            Optional<CompilationUnit> cu = parser.parse(candidate).getResult();
            if (cu.isPresent()) {
                String pkg = cu.get().getPackageDeclaration()
                        .map(pd -> pd.getNameAsString()).orElse("");
                if (pkg.contains(pkgHint)) return Optional.of(candidate);
            }
        }

        log.warn("Multiple files named {}.java found under {}; using first match. " +
                 "Declare explicit @DecomposableModule(dependencies={{...}}) to resolve ambiguity.",
                 beanType, sourceRoot);
        return Optional.of(candidates.get(0));
    }

    /**
     * Finds the module that owns {@code derivedServiceName}.
     *
     * <p>Uses a two-pass lookup to be robust against any naming convention:
     * <ol>
     *   <li><b>Exact match</b> — fast path for the common case.</li>
     *   <li><b>Normalized match</b> — strips non-alphanumeric characters and common
     *       infrastructure suffixes ({@code service}, {@code client}, {@code module})
     *       from both sides before comparing. This handles module names that do not
     *       follow the {@code <name>-service} pattern (e.g. {@code svc1}, {@code payments},
     *       {@code auth-module}) and bean types with unusual capitalization.</li>
     * </ol>
     */
    /**
     * Finds the module whose service name matches {@code derivedServiceName}.
     * Public so that other pipeline steps ({@link ConfigurationGenerator},
     * {@link NetScopeRegistryBridgeStep}, etc.) share one consistent lookup
     * rather than each maintaining their own exact-match filter.
     *
     * <p>Delegates to {@link NameResolver} — single implementation.
     */
    public static FractalModule findModule(String derivedServiceName, List<FractalModule> modules) {
        return NameResolver.defaults().findModule(derivedServiceName, modules);
    }

    /**
     * Normalizes a module/service name for fuzzy comparison.
     * Strips separators and configured infrastructure suffixes, then lowercases.
     *
     * <p>Delegates to {@link NameResolver} — single implementation.
     */
    public static String normalizeModuleName(String name) {
        return NameResolver.defaults().normalizeModuleName(name);
    }

    private Path toPackagePath(Path root, String packageName) {
        Path path = root;
        for (String part : packageName.split("\\.")) {
            path = path.resolve(part);
        }
        return path;
    }
}
