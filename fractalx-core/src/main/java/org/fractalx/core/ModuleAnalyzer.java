package org.fractalx.core;

import org.fractalx.core.model.FractalModule;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes source code to identify FractalX decomposable modules.
 *
 * <p>Uses a two-phase approach:
 * <ol>
 *   <li>Parse every {@code .java} file in the source tree once.</li>
 *   <li>For each {@code @DecomposableModule} class found, collect all import
 *       statements from every file whose package matches that module's package
 *       prefix. These are stored in {@link FractalModule#getDetectedImports()}
 *       so downstream generators (e.g. {@code PomGenerator}) can emit only the
 *       Maven dependencies the module actually needs.</li>
 * </ol>
 */
public class ModuleAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ModuleAnalyzer.class);
    private final JavaParser javaParser;

    public ModuleAnalyzer() {
        this.javaParser = new JavaParser();
    }

    /**
     * Analyzes a source directory and returns all decomposable modules found.
     */
    public List<FractalModule> analyzeProject(Path sourceRoot) throws IOException {

        // ── Phase 1: parse every .java file once ─────────────────────────────
        Map<Path, CompilationUnit> parsedFiles = new LinkedHashMap<>();
        Files.walk(sourceRoot)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> {
                    try {
                        javaParser.parse(p).getResult()
                                .ifPresent(cu -> parsedFiles.put(p, cu));
                    } catch (IOException e) {
                        log.error("Failed to parse file: {}", p, e);
                    }
                });

        // ── Phase 1b: build annotation index — all types with Spring stereotype annotations ──
        Set<String> springBeanTypes = buildSpringBeanTypeIndex(parsedFiles);

        // ── Phase 2: find @DecomposableModule classes ─────────────────────────
        List<FractalModule> modules = new ArrayList<>();
        for (CompilationUnit cu : parsedFiles.values()) {
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
                classDecl.getAnnotationByName("DecomposableModule").ifPresent(annotation -> {
                    String pkgName = cu.getPackageDeclaration()
                            .map(pd -> pd.getNameAsString()).orElse("");
                    Set<String> imports = collectImportsForPackage(pkgName, parsedFiles);
                    FractalModule module = extractModuleDescriptor(classDecl, annotation, cu, imports,
                            sourceRoot, springBeanTypes);
                    modules.add(module);
                    log.info("Found decomposable module: {}  ({} imports detected)",
                            module.getServiceName(), module.getDetectedImports().size());
                });
            });
        }

        // Validate: no two modules may share the same package — CodeCopier would copy the
        // same directory twice, producing duplicate/conflicting generated services.
        modules.stream()
                .collect(Collectors.groupingBy(FractalModule::getPackageName, Collectors.counting()))
                .forEach((pkg, count) -> {
                    if (count > 1) {
                        throw new IllegalStateException(
                            count + " @DecomposableModule classes share package '" + pkg + "'. " +
                            "Each module must have its own distinct package subtree. " +
                            "Move them into separate sub-packages (e.g., com.example.order, com.example.payment).");
                    }
                });

        return modules;
    }

    // ── Annotation index ──────────────────────────────────────────────────────

    /** Spring stereotype annotations that identify Spring-managed bean types. */
    private static final Set<String> SPRING_STEREOTYPES = Set.of(
            "Service", "Component", "Repository", "Controller", "RestController", "Configuration");

    /**
     * Builds an index of all simple type names that carry a Spring stereotype annotation
     * across the entire parsed source tree. Used to structurally identify cross-module
     * dependencies without relying on naming conventions.
     */
    private Set<String> buildSpringBeanTypeIndex(Map<Path, CompilationUnit> parsedFiles) {
        Set<String> beanTypes = new HashSet<>();
        for (CompilationUnit cu : parsedFiles.values()) {
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                boolean isBean = cls.getAnnotations().stream()
                        .anyMatch(a -> SPRING_STEREOTYPES.contains(a.getNameAsString()));
                if (isBean) {
                    beanTypes.add(cls.getNameAsString());
                }
            });
        }
        log.debug("Spring bean type index: {}", beanTypes);
        return beanTypes;
    }

    // ── Import collection ────────────────────────────────────────────────────

    /**
     * Collects all import strings from every parsed file whose package equals
     * {@code basePackage} or starts with {@code basePackage + "."}.
     */
    private Set<String> collectImportsForPackage(String basePackage,
                                                  Map<Path, CompilationUnit> parsedFiles) {
        Set<String> imports = new LinkedHashSet<>();
        for (CompilationUnit cu : parsedFiles.values()) {
            String pkg = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString()).orElse("");
            if (pkg.equals(basePackage) || pkg.startsWith(basePackage + ".")) {
                cu.getImports().forEach(imp -> imports.add(imp.getNameAsString()));
            }
        }
        return imports;
    }

    // ── Module extraction ────────────────────────────────────────────────────

    private FractalModule extractModuleDescriptor(ClassOrInterfaceDeclaration classDecl,
                                                   AnnotationExpr annotation,
                                                   CompilationUnit cu,
                                                   Set<String> detectedImports,
                                                   Path sourceRoot,
                                                   Set<String> springBeanTypes) {
        FractalModule.Builder builder = FractalModule.builder();
        builder.className(classDecl.getFullyQualifiedName().orElse(classDecl.getNameAsString()));

        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString()).orElse("");
        if (!packageName.isEmpty()) builder.packageName(packageName);

        List<String> explicitDeps = new ArrayList<>();

        if (annotation.isNormalAnnotationExpr()) {
            NormalAnnotationExpr normalAnnotation = annotation.asNormalAnnotationExpr();
            for (MemberValuePair pair : normalAnnotation.getPairs()) {
                String name  = pair.getNameAsString();
                String value = pair.getValue().toString().replace("\"", "");

                switch (name) {
                    case "serviceName" -> builder.serviceName(value);
                    case "port" -> {
                        try {
                            builder.port(Integer.parseInt(value));
                        } catch (NumberFormatException e) {
                            log.warn("Invalid port number: {}", value);
                        }
                    }
                    case "independentDeployment" -> builder.independentDeployment(Boolean.parseBoolean(value));
                    case "dependencies" -> explicitDeps.addAll(extractClassArrayValues(pair.getValue()));
                }
            }
        }

        List<String> dependencies;
        if (!explicitDeps.isEmpty()) {
            dependencies = explicitDeps;
            log.info("Explicit dependencies declared for {}: {}", builder.build().getServiceName(), dependencies);
        } else {
            dependencies = findDependencies(classDecl, packageName, sourceRoot, springBeanTypes);
            if (!dependencies.isEmpty()) {
                log.warn("Module '{}': dependencies inferred by annotation scan ({}). " +
                         "Declare them explicitly with @DecomposableModule(dependencies={{...}}) " +
                         "for full control.",
                         // serviceName not yet set on builder if we call build() — get from annotation
                         annotation.isNormalAnnotationExpr()
                             ? annotation.asNormalAnnotationExpr().getPairs().stream()
                               .filter(p -> "serviceName".equals(p.getNameAsString()))
                               .map(p -> p.getValue().toString().replace("\"", ""))
                               .findFirst().orElse("?")
                             : "?",
                         dependencies);
            }
        }

        builder.dependencies(dependencies);
        builder.detectedImports(detectedImports);

        FractalModule module = builder.build();

        if (!dependencies.isEmpty()) {
            log.info("Cross-module dependencies for {}: {}", module.getServiceName(), dependencies);
        }
        if (!detectedImports.isEmpty()) {
            log.debug("Detected imports for {}: {}", module.getServiceName(), detectedImports);
        }

        return module;
    }

    /**
     * Finds cross-module dependencies by scanning <em>all</em> {@code .java} files in the
     * module's package directory tree (not just the {@code @DecomposableModule} class).
     *
     * <p>Algorithm (structural, annotation-based):
     * <ol>
     *   <li>Walk every {@code .java} file under {@code sourceRoot/<packagePath>}.</li>
     *   <li>Collect the simple names of every class/interface declared in those files
     *       (these are "local" types that live inside this module).</li>
     *   <li>Collect every field type and constructor-parameter type whose simple name
     *       is a Spring-managed bean (has {@code @Service}, {@code @Component}, etc.).</li>
     *   <li>Subtract local types — what remains are genuine cross-module references.</li>
     * </ol>
     *
     * <p>Falls back to scanning just {@code classDecl} when the package directory cannot be
     * resolved (e.g. running from a single-file test fixture).
     */
    private List<String> findDependencies(ClassOrInterfaceDeclaration classDecl,
                                           String packageName, Path sourceRoot,
                                           Set<String> springBeanTypes) {
        Set<String> dependencies = new HashSet<>();
        Set<String> localTypes   = new HashSet<>();

        Path packageDir = sourceRoot.resolve(packageName.replace('.', '/'));
        if (Files.isDirectory(packageDir)) {
            try (var stream = Files.walk(packageDir)) {
                stream.filter(p -> p.toString().endsWith(".java"))
                      .forEach(javaFile -> {
                          try {
                              CompilationUnit cu = new JavaParser().parse(javaFile)
                                      .getResult().orElse(null);
                              if (cu == null) return;

                              // Track all locally-defined type names (classes + interfaces)
                              cu.findAll(ClassOrInterfaceDeclaration.class)
                                .forEach(c -> localTypes.add(c.getNameAsString()));

                              // Collect field types that are Spring-managed beans (structural annotation check)
                              cu.findAll(FieldDeclaration.class).forEach(field -> {
                                  String t = field.getCommonType().asString();
                                  if (springBeanTypes.contains(t)) {
                                      dependencies.add(t);
                                      log.debug("Found candidate dependency: {} in {}", t, javaFile.getFileName());
                                  }
                              });

                              // Lombok @RequiredArgsConstructor / @AllArgsConstructor:
                              // the constructor is synthesized from private final fields,
                              // so also scan those fields broadly — suffix heuristic alone
                              // misses types like NotificationGateway or PaymentFacade.
                              // localTypes subtraction and downstream findModule() filter
                              // out JDK / library types automatically.
                              cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                                  boolean hasLombokCtor =
                                      cls.getAnnotationByName("RequiredArgsConstructor").isPresent()
                                      || cls.getAnnotationByName("AllArgsConstructor").isPresent();
                                  if (!hasLombokCtor) return;
                                  cls.getFields().stream()
                                      .filter(f -> f.isFinal() && !f.isStatic())
                                      .forEach(f -> {
                                          String t = f.getCommonType().asString();
                                          if (!isWellKnownType(t)) {
                                              dependencies.add(t);
                                              log.debug("Lombok ctor field dep: {} in {}", t, javaFile.getFileName());
                                          }
                                      });
                              });

                              // Collect constructor-parameter types that are Spring-managed beans
                              cu.findAll(ConstructorDeclaration.class).forEach(ctor ->
                                  ctor.getParameters().forEach(param -> {
                                      String t = param.getTypeAsString();
                                      if (springBeanTypes.contains(t)) {
                                          dependencies.add(t);
                                          log.debug("Found candidate dependency: {} in {} constructor", t, javaFile.getFileName());
                                      }
                                  }));
                          } catch (IOException e) {
                              log.warn("Could not parse {} during dependency scan", javaFile, e);
                          }
                      });
            } catch (IOException e) {
                log.warn("Could not walk package directory {} — falling back to single-class scan", packageDir, e);
                fallbackScan(classDecl, dependencies, springBeanTypes);
            }
        } else {
            // Package directory not found on disk (test fixture / non-standard layout) — fall back
            log.debug("Package directory not found: {} — using single-class fallback scan", packageDir);
            fallbackScan(classDecl, dependencies, springBeanTypes);
        }

        // Remove types that are defined within this module — they are not cross-module
        dependencies.removeAll(localTypes);
        log.debug("Local types in package '{}': {}", packageName, localTypes);
        return new ArrayList<>(dependencies);
    }

    /** Original single-class scan used as a fallback when the package directory is unavailable. */
    private void fallbackScan(ClassOrInterfaceDeclaration classDecl, Set<String> dependencies,
                               Set<String> springBeanTypes) {
        classDecl.findAll(FieldDeclaration.class).forEach(field -> {
            String t = field.getCommonType().asString();
            if (springBeanTypes.contains(t)) {
                dependencies.add(t);
                log.debug("Fallback: found dependency {} in field declaration", t);
            }
        });

        // Lombok fallback: scan private final fields broadly when Lombok ctor annotation present
        boolean hasLombokCtor = classDecl.getAnnotationByName("RequiredArgsConstructor").isPresent()
                || classDecl.getAnnotationByName("AllArgsConstructor").isPresent();
        if (hasLombokCtor) {
            classDecl.getFields().stream()
                .filter(f -> f.isFinal() && !f.isStatic())
                .forEach(f -> {
                    String t = f.getCommonType().asString();
                    if (springBeanTypes.contains(t)) {
                        dependencies.add(t);
                        log.debug("Fallback Lombok ctor field dep: {}", t);
                    }
                });
        }

        classDecl.getConstructors().forEach(ctor ->
            ctor.getParameters().forEach(param -> {
                String t = param.getType().asString();
                if (springBeanTypes.contains(t)) {
                    dependencies.add(t);
                    log.debug("Fallback: found dependency {} in constructor parameter", t);
                }
            })
        );
    }

    /**
     * Returns true for well-known JDK, Spring, or primitive types that should
     * never be treated as cross-module dependencies.
     */
    private static boolean isWellKnownType(String typeName) {
        if (typeName == null || typeName.isEmpty()) return true;
        // strip generic type params e.g. List<String> → List
        String raw = typeName.contains("<") ? typeName.substring(0, typeName.indexOf('<')) : typeName;
        return switch (raw) {
            case "String", "Integer", "Long", "Double", "Float", "Boolean",
                 "Byte", "Short", "Character", "Object", "Number",
                 "int", "long", "double", "float", "boolean", "byte", "short", "char", "void",
                 "List", "Map", "Set", "Collection", "Iterable", "Optional",
                 "Stream", "Flux", "Mono",
                 "BigDecimal", "BigInteger", "UUID",
                 "LocalDate", "LocalDateTime", "LocalTime", "Instant", "Duration",
                 "Path", "File", "InputStream", "OutputStream",
                 "Logger", "ObjectMapper", "RestTemplate", "WebClient",
                 "ApplicationEventPublisher", "Environment", "MessageSource" -> true;
            default -> raw.startsWith("java.") || raw.startsWith("jakarta.")
                    || raw.startsWith("org.springframework.")
                    || raw.startsWith("io.") || raw.startsWith("com.fasterxml.");
        };
    }

    /**
     * Extracts simple class names from a {@code dependencies={Foo.class, Bar.class}} annotation value.
     * Handles both a single {@code ClassExpr} and an {@code ArrayInitializerExpr} of {@code ClassExpr}.
     */
    private List<String> extractClassArrayValues(Expression expr) {
        List<String> result = new ArrayList<>();
        if (expr instanceof ClassExpr ce) {
            result.add(ce.getTypeAsString());
        } else if (expr instanceof ArrayInitializerExpr aie) {
            for (Expression element : aie.getValues()) {
                if (element instanceof ClassExpr ce) {
                    result.add(ce.getTypeAsString());
                }
            }
        }
        return result;
    }
}
