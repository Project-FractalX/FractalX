package org.fractalx.core.datamanagement;

import org.fractalx.core.generator.service.NetScopeClientGenerator;
import org.fractalx.core.model.FractalModule;
import org.fractalx.core.model.MethodParam;
import org.fractalx.core.model.SagaDefinition;
import org.fractalx.core.model.SagaStep;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Scans source files for {@code @DistributedSaga}-annotated methods and builds
 * {@link SagaDefinition} objects for the saga orchestrator generator.
 *
 * <p>For each saga method, this analyzer:
 * <ol>
 *   <li>Reads the {@code sagaId}, {@code compensationMethod}, {@code timeout}, and
 *       {@code description} from the annotation.</li>
 *   <li>Identifies the injected fields whose types are cross-module dependencies
 *       (via {@link FractalModule#getDependencies()}).</li>
 *   <li>Scans the method body for calls on those fields in source order — each
 *       becomes a {@link SagaStep}.</li>
 *   <li>Attempts to find a matching compensation method on the same dependency bean.</li>
 * </ol>
 */
public class SagaAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SagaAnalyzer.class);

    private final JavaParser javaParser = new JavaParser();

    /**
     * Analyzes all modules' source files and returns every detected saga definition.
     *
     * @param sourceRoot monolith source root
     * @param modules    all decomposable modules (used to resolve dependency → service mappings)
     * @return unmodifiable list of detected sagas, possibly empty
     */
    public List<SagaDefinition> analyzeSagas(Path sourceRoot, List<FractalModule> modules) {
        // First pass: build a map of className → Set<methodName> so deriveCompensationName()
        // can check whether a compensation method actually exists on the target bean.
        Map<String, Set<String>> classMethodMap = buildClassMethodMap(sourceRoot);

        List<SagaDefinition> sagas = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(path -> {
                try {
                    sagas.addAll(analyzeFile(path, modules, classMethodMap));
                } catch (IOException e) {
                    log.error("Failed to analyze saga in: {}", path, e);
                }
            });
        } catch (IOException e) {
            log.error("Failed to walk source root for saga analysis", e);
        }

        if (!sagas.isEmpty()) {
            log.info("Detected {} saga(s): {}", sagas.size(),
                    sagas.stream().map(SagaDefinition::getSagaId).toList());
        }
        return Collections.unmodifiableList(sagas);
    }

    /**
     * Builds a map of {@code simpleClassName → Set<methodName>} by walking all {@code .java}
     * files under {@code sourceRoot}. Used by {@link #deriveCompensationName} to verify that
     * a candidate compensation method actually exists on the target bean before recording it.
     */
    private Map<String, Set<String>> buildClassMethodMap(Path sourceRoot) {
        Map<String, Set<String>> map = new HashMap<>();
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(path -> {
                try {
                    CompilationUnit cu = javaParser.parse(path).getResult().orElse(null);
                    if (cu == null) return;
                    for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                        Set<String> methods = new HashSet<>();
                        cls.getMethods().forEach(m -> methods.add(m.getNameAsString()));
                        map.put(cls.getNameAsString(), methods);
                    }
                } catch (IOException e) {
                    log.warn("buildClassMethodMap: failed to parse {}", path);
                }
            });
        } catch (IOException e) {
            log.warn("buildClassMethodMap: failed to walk {}", sourceRoot);
        }
        log.debug("buildClassMethodMap: indexed {} classes", map.size());
        return map;
    }

    // -------------------------------------------------------------------------
    // Per-file analysis
    // -------------------------------------------------------------------------

    private List<SagaDefinition> analyzeFile(Path javaFile, List<FractalModule> modules,
                                              Map<String, Set<String>> classMethodMap) throws IOException {
        CompilationUnit cu = javaParser.parse(javaFile).getResult().orElse(null);
        if (cu == null) return List.of();

        List<SagaDefinition> result = new ArrayList<>();

        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            // Build a map: field variable name → bean type (for cross-module deps only)
            Map<String, String> crossModuleFields = findCrossModuleFields(cls, modules);

            for (MethodDeclaration method : cls.getMethods()) {
                method.getAnnotationByName("DistributedSaga").ifPresent(annotation -> {
                    SagaDefinition saga = buildSagaDefinition(annotation, method, cls,
                            crossModuleFields, modules, cu, classMethodMap);
                    if (saga != null) {
                        result.add(saga);
                        log.info("Found @DistributedSaga '{}' in {}.{}",
                                saga.getSagaId(), cls.getNameAsString(), method.getNameAsString());
                    }
                });
            }
        }
        return result;
    }

    private SagaDefinition buildSagaDefinition(AnnotationExpr annotation,
                                               MethodDeclaration method,
                                               ClassOrInterfaceDeclaration cls,
                                               Map<String, String> crossModuleFields,
                                               List<FractalModule> modules,
                                               CompilationUnit cu,
                                               Map<String, Set<String>> classMethodMap) {
        // Extract annotation attributes
        String sagaId            = extractAttr(annotation, "sagaId", "");
        String compensationMethod= extractAttr(annotation, "compensationMethod", "");
        String description       = extractAttr(annotation, "description", "");
        long   timeout           = extractLongAttr(annotation, "timeout", 30000L);
        String successStatus     = extractAttr(annotation, "successStatus", "");
        String failureStatus     = extractAttr(annotation, "failureStatus", "");

        if (sagaId.isBlank()) {
            log.warn("@DistributedSaga on {}.{} has no sagaId — skipping",
                    cls.getNameAsString(), method.getNameAsString());
            return null;
        }

        // Detect the owning module
        String packageName  = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        String ownerService = findOwnerService(packageName, modules);

        // Extract the saga method's own parameters for payload DTO generation
        List<MethodParam> sagaMethodParams = new ArrayList<>();
        for (Parameter p : method.getParameters()) {
            sagaMethodParams.add(new MethodParam(p.getType().asString(), p.getNameAsString()));
        }

        // Detect ordered cross-module calls within this method body
        List<SagaStep> steps = detectSteps(method, crossModuleFields, modules, classMethodMap);

        // Detect local vars used in step call args that are NOT saga method params
        // (e.g. orderId = draftOrder.getId() passed to processPayment)
        List<MethodParam> extraLocalVars = detectExtraLocalVars(method, steps, sagaMethodParams);

        return new SagaDefinition(
                sagaId,
                ownerService,
                cls.getNameAsString(),
                method.getNameAsString(),
                steps,
                compensationMethod,
                timeout,
                description,
                sagaMethodParams,
                extraLocalVars,
                successStatus,
                failureStatus
        );
    }

    // -------------------------------------------------------------------------
    // Field and step detection
    // -------------------------------------------------------------------------

    /**
     * Returns a map of {@code fieldName → beanType} for fields whose type is in
     * the cross-module dependency set of any module.
     */
    private Map<String, String> findCrossModuleFields(ClassOrInterfaceDeclaration cls,
                                                      List<FractalModule> modules) {
        Set<String> allDepTypes = new HashSet<>();
        for (FractalModule m : modules) {
            allDepTypes.addAll(m.getDependencies());
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (FieldDeclaration field : cls.getFields()) {
            String typeName = field.getElementType().asString();
            if (allDepTypes.contains(typeName)) {
                field.getVariables().forEach(v -> result.put(v.getNameAsString(), typeName));
            }
        }
        return result;
    }

    /**
     * Walks the method body in source order and records calls whose receiver is a
     * cross-module dependency field.
     */
    private List<SagaStep> detectSteps(MethodDeclaration method,
                                       Map<String, String> crossModuleFields,
                                       List<FractalModule> modules,
                                       Map<String, Set<String>> classMethodMap) {
        List<SagaStep> steps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>(); // dedup while preserving order

        method.findAll(MethodCallExpr.class).forEach(call -> {
            if (call.getScope().isEmpty()) return;
            String scope = call.getScope().get().toString();

            if (!crossModuleFields.containsKey(scope)) return;

            String beanType    = crossModuleFields.get(scope);
            String calledMethod = call.getNameAsString();
            String key         = beanType + "#" + calledMethod;

            if (seen.contains(key)) return;
            seen.add(key);

            String targetService    = NetScopeClientGenerator.beanTypeToServiceName(beanType);
            String compensationName = deriveCompensationName(calledMethod, beanType, classMethodMap);

            // Capture the actual argument expressions used in the call.
            // These are typically parameter names from the parent saga method,
            // which lets the generator emit typed calls instead of TODO stubs.
            List<String> callArgs = call.getArguments().stream()
                    .map(arg -> arg.toString())
                    .collect(java.util.stream.Collectors.toList());

            steps.add(new SagaStep(beanType, targetService, calledMethod, compensationName, callArgs));
        });

        return steps;
    }

    /**
     * Derives the compensation method name for a forward method by checking what methods
     * actually exist on the target bean class in the source tree.
     *
     * <p>Tries each of the common compensation prefixes in order:
     * {@code cancel}, {@code rollback}, {@code undo}, {@code revert}, {@code release}, {@code refund}.
     * The first candidate that exists on {@code beanType} in {@code classMethodMap} is returned.
     *
     * <p>Returns an empty string if no compensation method was found. The generated
     * orchestrator will skip compensation for that step (see {@link SagaStep#hasCompensation()}).
     */
    private String deriveCompensationName(String forwardMethod,
                                          String beanType,
                                          Map<String, Set<String>> classMethodMap) {
        String capitalized = Character.toUpperCase(forwardMethod.charAt(0)) + forwardMethod.substring(1);

        List<String> prefixes = List.of("cancel", "rollback", "undo", "revert", "release", "refund");

        Set<String> availableMethods = classMethodMap.getOrDefault(beanType, Set.of());

        for (String prefix : prefixes) {
            String candidate = prefix + capitalized;
            if (availableMethods.contains(candidate)) {
                log.debug("Compensation for {}.{} → {}", beanType, forwardMethod, candidate);
                return candidate;
            }
        }

        log.info("No compensation method found for {}.{} (checked: {}) — step will not be compensated",
                beanType, forwardMethod,
                prefixes.stream().map(p -> p + capitalized).collect(java.util.stream.Collectors.joining(", ")));
        return "";
    }

    // -------------------------------------------------------------------------
    // Helper utilities
    // -------------------------------------------------------------------------

    private String findOwnerService(String packageName, List<FractalModule> modules) {
        return modules.stream()
                .filter(m -> packageName.startsWith(m.getPackageName())
                          || m.getPackageName().startsWith(packageName))
                .map(FractalModule::getServiceName)
                .findFirst()
                .orElse("unknown-service");
    }

    private String extractAttr(AnnotationExpr annotation, String attrName, String defaultValue) {
        if (annotation.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                if (pair.getNameAsString().equals(attrName)) {
                    return pair.getValue().toString().replace("\"", "");
                }
            }
        } else if (annotation.isSingleMemberAnnotationExpr() && "sagaId".equals(attrName)) {
            return annotation.asSingleMemberAnnotationExpr().getMemberValue()
                    .toString().replace("\"", "");
        }
        return defaultValue;
    }

    private long extractLongAttr(AnnotationExpr annotation, String attrName, long defaultValue) {
        if (!annotation.isNormalAnnotationExpr()) return defaultValue;
        for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
            if (pair.getNameAsString().equals(attrName)) {
                try {
                    return Long.parseLong(pair.getValue().toString().replace("L", ""));
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    /**
     * Finds local variables declared in the saga method body that are used as step call
     * arguments but are NOT formal parameters of the saga method.
     *
     * <p>Example: {@code Long orderId = draftOrder.getId()} where {@code orderId} is
     * passed to {@code processPayment(customerId, totalAmount, orderId)}.
     * The orchestrator needs this value in the payload DTO so it can forward it.
     */
    private List<MethodParam> detectExtraLocalVars(MethodDeclaration method,
                                                    List<SagaStep> steps,
                                                    List<MethodParam> sagaMethodParams) {
        // Build name→type map of all local var declarations in method body
        Map<String, String> localVarTypes = new LinkedHashMap<>();
        method.findAll(VariableDeclarationExpr.class).forEach(vde ->
                vde.getVariables().forEach(v ->
                        localVarTypes.put(v.getNameAsString(), vde.getElementType().asString())
                )
        );

        // Collect formal param names for quick lookup
        Set<String> paramNames = new HashSet<>();
        for (MethodParam mp : sagaMethodParams) paramNames.add(mp.getName());

        // For each step call arg not in params, check if it resolves to a local var
        List<MethodParam> extras = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SagaStep step : steps) {
            for (String arg : step.getCallArguments()) {
                if (!paramNames.contains(arg) && localVarTypes.containsKey(arg) && seen.add(arg)) {
                    extras.add(new MethodParam(localVarTypes.get(arg), arg));
                    log.debug("SagaAnalyzer: extra local var '{}' ({}) needed by saga step {}.{}",
                            arg, localVarTypes.get(arg), step.getTargetServiceName(), step.getMethodName());
                }
            }
        }
        return extras;
    }
}
