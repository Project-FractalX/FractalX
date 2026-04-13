package org.fractalx.core.verifier;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.fractalx.core.model.FractalModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * <p>Detection strategy (AST-based):
 * <ol>
 *   <li>Walk all Java files in the generated service</li>
 *   <li>Find methods annotated with {@code @Transactional}</li>
 *   <li>Inside that method body, look for calls on objects whose type ends in
 *       {@code "Client"} — the naming convention for all NetScope client interfaces</li>
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
        try (Stream<Path> walk = Files.walk(srcJava)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    // Only exclude *Client.java files in the generated client/ package
                    // to avoid dropping legitimate domain classes named *Client.java
                    .filter(p -> !(p.getFileName().toString().endsWith("Client.java")
                                   && p.toString().replace('\\', '/').contains("/client/")))
                    .forEach(file -> analyseFile(file, serviceName, violations));
        } catch (IOException e) {
            log.debug("Could not walk {}: {}", srcJava, e.getMessage());
        }
    }

    private void analyseFile(Path file, String serviceName,
                              List<TransactionViolation> violations) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);

            cu.findAll(MethodDeclaration.class).stream()
                    .filter(this::isTransactional)
                    .forEach(method -> {
                        List<String> crossCalls = findCrossServiceCalls(method);
                        for (String call : crossCalls) {
                            violations.add(new TransactionViolation(
                                    serviceName, file, method.getNameAsString(), call));
                        }
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
     * Finds method calls inside a @Transactional method where the scope object's
     * name ends with "Client" — the naming convention for NetScope client interfaces.
     */
    private List<String> findCrossServiceCalls(MethodDeclaration method) {
        List<String> crossCalls = new ArrayList<>();
        method.findAll(MethodCallExpr.class).forEach(call -> {
            call.getScope().ifPresent(scope -> {
                String scopeName = scope.toString();
                // NetScope client fields are injected as e.g. paymentServiceClient
                if (scopeName.toLowerCase().endsWith("client")) {
                    crossCalls.add(scopeName + "." + call.getNameAsString() + "(...)");
                }
            });
        });
        return crossCalls;
    }
}
