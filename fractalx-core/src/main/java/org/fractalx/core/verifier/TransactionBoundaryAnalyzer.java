package org.fractalx.core.verifier;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.fractalx.core.model.FractalModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Detects distributed-transaction anti-patterns in generated service code.
 *
 * <p>A {@code @Transactional} method that also makes a NetScope cross-service call
 * creates an invisible distributed transaction. If the remote call succeeds but
 * the local transaction rolls back (or vice-versa), data consistency is lost.
 *
 * <p>The correct pattern is to use the generated Saga orchestrator for operations
 * that span multiple services, or to accept eventual consistency via the Outbox
 * pattern.
 *
 * <p>Detection strategy (AST-based, structural):
 * <ol>
 *   <li>Pre-scan the service source tree to find all interfaces annotated with
 *       {@code @NetScopeClient} — collect their simple type names</li>
 *   <li>Walk all Java files in the generated service</li>
 *   <li>Find methods annotated with {@code @Transactional}</li>
 *   <li>Inside that method body, resolve scope variables to their declared field types
 *       and check if the type is a {@code @NetScopeClient} interface</li>
 *   <li>Report as a WARNING with file and method name</li>
 * </ol>
 */
public class TransactionBoundaryAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(TransactionBoundaryAnalyzer.class);

    // ── Result model ──────────────────────────────────────────────────────────

    public record TransactionViolation(
            String  serviceName,
            Path    file,
            String  methodName,
            String  crossServiceCall
    ) {
        @Override
        public String toString() {
            return "[WARN] Distributed transaction risk in " + serviceName
                    + " — @Transactional method '" + methodName + "' calls '" + crossServiceCall
                    + "' (a NetScope client). Use Saga or Outbox instead."
                    + " [" + file.getFileName() + "]";
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public List<TransactionViolation> analyse(Path outputDir, List<FractalModule> modules) {
        List<TransactionViolation> violations = new ArrayList<>();

        for (FractalModule module : modules) {
            Path srcJava = outputDir.resolve(module.getServiceName())
                    .resolve("src/main/java");
            if (!Files.isDirectory(srcJava)) continue;
            analyseService(srcJava, module.getServiceName(), violations);
        }

        return violations;
    }

    // ── Per-service analysis ──────────────────────────────────────────────────

    private void analyseService(Path srcJava, String serviceName,
                                List<TransactionViolation> violations) {
        // Pre-scan: collect all type names annotated with @NetScopeClient (structural)
        Set<String> clientTypes = scanNetScopeClientTypes(srcJava);

        try (Stream<Path> walk = Files.walk(srcJava)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(file -> analyseFile(file, serviceName, violations, clientTypes));
        } catch (IOException e) {
            log.debug("Could not walk {}: {}", srcJava, e.getMessage());
        }
    }

    /**
     * Scans the service source tree for interfaces annotated with {@code @NetScopeClient}
     * and returns their simple type names.
     */
    private Set<String> scanNetScopeClientTypes(Path srcJava) {
        Set<String> types = new HashSet<>();
        try (Stream<Path> walk = Files.walk(srcJava)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            CompilationUnit cu = StaticJavaParser.parse(path);
                            cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                                    .filter(ClassOrInterfaceDeclaration::isInterface)
                                    .filter(c -> c.getAnnotations().stream()
                                            .anyMatch(a -> a.getNameAsString().equals("NetScopeClient")))
                                    .forEach(c -> types.add(c.getNameAsString()));
                        } catch (Exception ignored) {}
                    });
        } catch (IOException e) {
            log.debug("Could not scan for NetScopeClient types in {}: {}", srcJava, e.getMessage());
        }
        return types;
    }

    private void analyseFile(Path file, String serviceName,
                              List<TransactionViolation> violations,
                              Set<String> clientTypes) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);

            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                // Build field name → type name map for resolving scope variables
                Map<String, String> fieldTypes = resolveFieldTypes(cls);

                cls.findAll(MethodDeclaration.class).stream()
                        .filter(this::isTransactional)
                        .forEach(method -> {
                            List<String> crossCalls = findCrossServiceCalls(method, fieldTypes, clientTypes);
                            for (String call : crossCalls) {
                                violations.add(new TransactionViolation(
                                        serviceName, file, method.getNameAsString(), call));
                            }
                        });
            });
        } catch (Exception e) {
            log.debug("Could not parse {}: {}", file, e.getMessage());
        }
    }

    // ── AST helpers ───────────────────────────────────────────────────────────

    private boolean isTransactional(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .anyMatch(a -> {
                    String name = a.getNameAsString();
                    return name.equals("Transactional")
                            || name.equals("javax.transaction.Transactional")
                            || name.equals("jakarta.transaction.Transactional");
                });
    }

    /**
     * Builds a map of field/parameter name → declared type simple name for the given class.
     * Used to resolve scope variables in method calls to their declared types.
     */
    private Map<String, String> resolveFieldTypes(ClassOrInterfaceDeclaration cls) {
        Map<String, String> types = new HashMap<>();
        // Class fields
        for (FieldDeclaration field : cls.getFields()) {
            String typeName = field.getElementType().asString();
            field.getVariables().forEach(v -> types.put(v.getNameAsString(), typeName));
        }
        // Constructor parameters (for constructor injection)
        cls.getConstructors().forEach(ctor ->
                ctor.getParameters().forEach(p ->
                        types.put(p.getNameAsString(), p.getTypeAsString())));
        return types;
    }

    /**
     * Finds method calls inside a {@code @Transactional} method where the scope variable's
     * declared type is a {@code @NetScopeClient} interface (structural annotation check).
     */
    private List<String> findCrossServiceCalls(MethodDeclaration method,
                                                Map<String, String> fieldTypes,
                                                Set<String> clientTypes) {
        List<String> crossCalls = new ArrayList<>();
        method.findAll(MethodCallExpr.class).forEach(call -> {
            call.getScope().ifPresent(scope -> {
                if (!(scope instanceof NameExpr nameExpr)) return;
                String scopeName = nameExpr.getNameAsString();
                String declaredType = fieldTypes.get(scopeName);
                if (declaredType != null && clientTypes.contains(declaredType)) {
                    crossCalls.add(scopeName + "." + call.getNameAsString() + "(...)");
                }
            });
        });
        return crossCalls;
    }
}
