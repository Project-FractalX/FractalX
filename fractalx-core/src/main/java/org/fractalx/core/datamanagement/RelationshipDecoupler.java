package org.fractalx.core.datamanagement;

import org.fractalx.core.graph.DependencyGraph;
import org.fractalx.core.graph.EdgeKind;
import org.fractalx.core.graph.GraphEdge;
import org.fractalx.core.graph.GraphNode;
import org.fractalx.core.model.FractalModule;
import org.fractalx.core.util.SpringDataUtils;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.ReturnStmt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Performs AST-based decoupling of cross-service relationships.
 * Converts JPA relationship fields to String / List&lt;String&gt; ID fields and updates
 * associated service logic.  Handles POJO, Record, and Lombok-annotated request DTOs.
 *
 * <h3>Annotation handling</h3>
 * <ul>
 *   <li>{@code @ManyToOne} / {@code @OneToOne} — singular remote entity → {@code String *Id}</li>
 *   <li>{@code @OneToMany} referencing a remote entity — collection removed entirely</li>
 *   <li>{@code @ManyToMany} referencing a remote entity — collection converted to
 *       {@code @ElementCollection List<String> *Ids}</li>
 *   <li>Local relationships (both sides in the same module) — left untouched</li>
 * </ul>
 */
public class RelationshipDecoupler {

    private static final Logger log = LoggerFactory.getLogger(RelationshipDecoupler.class);

    private final JavaParser javaParser = new JavaParser();

    private static final Set<String> REL_ANNOS  = Set.of("ManyToOne", "OneToOne", "JoinColumn");
    private static final String ONE_TO_MANY  = "OneToMany";
    private static final String MANY_TO_MANY = "ManyToMany";

    /** JPA class-level annotations — classes with these are entities, not request DTOs. */
    private static final Set<String> JPA_CLASS_ANNOS = Set.of(
            "Entity", "Table", "MappedSuperclass", "Embeddable");

    /** Spring stereotype annotations — classes with these are components, not request DTOs. */
    private static final Set<String> SPRING_STEREOTYPE_ANNOS = Set.of(
            "Service", "Component", "Repository", "Controller", "RestController",
            "Configuration", "SpringBootApplication");

    /** Graph reference, set per-transform call. Nullable — falls back to AST when absent. */
    private DependencyGraph graph;

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Graph-aware overload. Delegates to the main transform logic after storing
     * the graph reference for structural queries.
     */
    public void transform(Path serviceRoot, FractalModule module, String basePackage,
                           DependencyGraph graph) {
        this.graph = graph;
        transform(serviceRoot, module, basePackage);
    }

    /**
     * Orchestrates the transformation process: identifies local/remote entities,
     * collects per-entity remote field info, indexes request DTOs, and applies AST
     * modifications to all source files under {@code serviceRoot}.
     */
    public void transform(Path serviceRoot, FractalModule module, String basePackage) {
        try {
            String modulePackage = module.getPackageName() != null ? module.getPackageName() : "";

            Set<String> localEntities  = findLocalEntityNames(serviceRoot, modulePackage);
            Set<String> remoteEntities = findRemoteEntities(serviceRoot, localEntities, modulePackage);

            if (remoteEntities.isEmpty()) {
                log.info("✅ [Data] No remote entity references found for {}. Data is fully local.",
                        module.getServiceName());
                return;
            }

            log.info("🧩 [Data] Remote entity types to decouple for {}: {}",
                    module.getServiceName(), remoteEntities);

            // Pre-pass: build a map of entityClass → remote field info so that service
            // validation injection can work without requiring cross-file state in the main walk.
            Map<String, RemoteFieldInfo> entityRemoteIdFields =
                    collectEntityRemoteIdFields(serviceRoot, remoteEntities, modulePackage);

            // Build getter rename map for service-side chain detection (step 4)
            Map<String, String> collectionGetterRenames = buildCollectionGetterRenames(entityRemoteIdFields);

            RequestInfoIndex requestIndex = buildRequestIndex(serviceRoot);

            // Rename repository derived-query methods and update their parameters FIRST,
            // so that stripRemoteEntityMethodParams (inside transformFile) does not strip the
            // original entity parameter before we get a chance to re-type it.
            // e.g. findByCustomer(Customer c) → findByCustomerId(String customerId) must happen
            // before the Customer parameter is removed.
            renameRepositoryQueryMethods(serviceRoot, entityRemoteIdFields);

            try (Stream<Path> paths = Files.walk(serviceRoot)) {
                paths.filter(p -> p.toString().endsWith(".java")).forEach(path -> {
                    try {
                        transformFile(path, localEntities, remoteEntities, requestIndex,
                                module, entityRemoteIdFields, collectionGetterRenames, basePackage);
                    } catch (Exception e) {
                        log.error("❌ [Data] Failed to transform file: {}", path, e);
                    }
                });
            }

        } catch (IOException e) {
            log.error("❌ [Data] Relationship decoupling failed during file traversal", e);
        }
    }

    // =========================================================================
    // Repository query method renaming
    // =========================================================================

    /**
     * Scans all JPA Repository interfaces in the service source tree and renames derived
     * query methods whose names embed a field name that was renamed during entity decoupling.
     *
     * <p>Example: if {@code FraudRecord.payment} was renamed to {@code FraudRecord.paymentId},
     * then {@code FraudRecordRepository.findByPayment()} is renamed to
     * {@code FraudRecordRepository.findByPaymentId()}.
     *
     * <p>The rename applies to all repository interfaces regardless of whether they are
     * {@code JpaRepository}, {@code CrudRepository}, or {@code PagingAndSortingRepository}.
     *
     * <p>Also rewrites JPQL {@code @Query} annotation strings that reference the old field name
     * with a simple {@code String.replace} (best-effort; covers {@code o.payment = } style).
     */
    private void renameRepositoryQueryMethods(Path serviceRoot,
                                               Map<String, RemoteFieldInfo> entityRemoteIdFields) {
        // Build a flat old → new field-name map from all entity infos
        Map<String, String> allRenames = new LinkedHashMap<>();
        // Build a type-simple-name → new-id-field-name map (e.g. "Customer" → "customerId")
        // so repository parameter types can be updated alongside method names.
        Map<String, String> typeToIdFieldName = new LinkedHashMap<>();
        for (RemoteFieldInfo info : entityRemoteIdFields.values()) {
            allRenames.putAll(info.singleOldToNewField);
            allRenames.putAll(info.collectionOldToNewField);
            // singleTypeToIdField: entityType → newIdFieldName  e.g. "Customer" → "customerId"
            typeToIdFieldName.putAll(info.singleTypeToIdField);
        }
        if (allRenames.isEmpty()) return;

        try (Stream<Path> paths = Files.walk(serviceRoot)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(javaFile -> {
                try {
                    renameQueryMethodsInFile(javaFile, allRenames, typeToIdFieldName);
                } catch (IOException e) {
                    log.warn("⚠️ [Data] Could not rename repository methods in {}: {}", javaFile.getFileName(), e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("⚠️ [Data] Could not walk service root for repository method renaming", e);
        }
    }

    private void renameQueryMethodsInFile(Path javaFile, Map<String, String> fieldRenames) throws IOException {
        renameQueryMethodsInFile(javaFile, fieldRenames, Map.of());
    }

    /**
     * Renames repository query methods and updates their parameters.
     *
     * @param fieldRenames      old field name → new field name (e.g. {@code payment → paymentId})
     * @param typeToIdFieldName entity type simple name → new id-field name (e.g. {@code Customer → customerId})
     *                          used to update parameter declarations from {@code Customer customer} → {@code String customerId}
     */
    private void renameQueryMethodsInFile(Path javaFile, Map<String, String> fieldRenames,
                                           Map<String, String> typeToIdFieldName) throws IOException {
        Optional<CompilationUnit> cuOpt = javaParser.parse(javaFile).getResult();
        if (cuOpt.isEmpty()) return;
        CompilationUnit cu = cuOpt.get();

        boolean modified = false;

        for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (!decl.isInterface()) continue;

            // Check if this interface extends a known Spring Data repository interface
            boolean isRepository = SpringDataUtils.isRepositoryInterface(decl)
                    || decl.getAnnotations().stream()
                           .anyMatch(a -> a.getNameAsString().equals("Repository"));
            if (!isRepository) continue;

            for (MethodDeclaration method : decl.getMethods()) {
                String originalName = method.getNameAsString();
                String renamedName  = originalName;

                for (Map.Entry<String, String> entry : fieldRenames.entrySet()) {
                    if (entry.getValue() == null) {
                        log.warn("RelationshipDecoupler: null rename target for field '{}' in {} "
                                + "— skipping rename for this entry",
                                entry.getKey(), javaFile.getFileName());
                        continue;
                    }
                    String oldSegment = upperFirst(entry.getKey());   // e.g. "Payment"
                    String newSegment = upperFirst(entry.getValue()); // e.g. "PaymentId"
                    // Avoid double-renaming: if oldSegment is already followed by the suffix
                    // we're adding (e.g. "Customer" already followed by "Id"), don't rename.
                    // e.g. findByCustomerId.replace("Customer","CustomerId") must NOT yield findByCustomerIdId
                    if (newSegment.startsWith(oldSegment) && newSegment.length() > oldSegment.length()) {
                        String suffix = java.util.regex.Pattern.quote(
                                newSegment.substring(oldSegment.length()));
                        renamedName = renamedName.replaceAll(oldSegment + "(?!" + suffix + ")", newSegment);
                    } else {
                        renamedName = renamedName.replace(oldSegment, newSegment);
                    }
                }

                if (!renamedName.equals(originalName)) {
                    method.setName(renamedName);
                    log.info("🔧 [Data] Renamed repository method: {} → {} in {}",
                            originalName, renamedName, javaFile.getFileName());
                    modified = true;
                }

                // Also update parameters: Customer customer → String customerId
                // This prevents stripRemoteEntityMethodParams from dropping the param entirely.
                if (!typeToIdFieldName.isEmpty()) {
                    for (Parameter param : new ArrayList<>(method.getParameters())) {
                        String paramTypeName = param.getTypeAsString();
                        String newIdField = typeToIdFieldName.get(paramTypeName);
                        if (newIdField != null) {
                            param.setType(new ClassOrInterfaceType(null, "String"));
                            param.setName(newIdField);
                            modified = true;
                            log.debug("🔧 [Data] Updated repository param {} {} → String {} in {}",
                                    paramTypeName, param.getNameAsString(), newIdField, javaFile.getFileName());
                        }
                    }
                }

                // Best-effort: rewrite @Query annotation JPQL strings (e.g. "o.payment = :payment")
                method.getAnnotationByName("Query").ifPresent(ann -> {
                    String annStr = ann.toString();
                    String updated = annStr;
                    for (Map.Entry<String, String> entry : fieldRenames.entrySet()) {
                        updated = updated.replace("." + entry.getKey() + " ",  "." + entry.getValue() + " ")
                                         .replace("." + entry.getKey() + "=",  "." + entry.getValue() + "=")
                                         .replace("." + entry.getKey() + ")",  "." + entry.getValue() + ")")
                                         .replace(":" + entry.getKey() + ")",  ":" + entry.getValue() + ")")
                                         .replace(":" + entry.getKey() + " ",  ":" + entry.getValue() + " ");
                    }
                    if (!updated.equals(annStr)) {
                        // Replace the annotation by removing + re-parsing it
                        ann.remove();
                        try {
                            new JavaParser().parseAnnotation(updated).getResult()
                                    .ifPresent(newAnn -> method.addAnnotation(newAnn));
                        } catch (Exception ignored) {
                            log.debug("Could not re-parse @Query annotation after rename in {}", javaFile.getFileName());
                        }
                    }
                });
            }
        }

        if (modified) {
            Files.writeString(javaFile, cu.toString());
        }
    }

    // =========================================================================
    // Inner data holders
    // =========================================================================

    /**
     * Remote-field information discovered for one entity class during the pre-pass.
     * Exposed as package-private so tests can inspect it directly if needed.
     */
    static class RemoteFieldInfo {
        /** e.g. {@code "Payment" → "paymentId"} — singular {@code @ManyToOne} / {@code @OneToOne} field */
        final Map<String, String> singleTypeToIdField      = new LinkedHashMap<>();
        /** e.g. {@code "Course" → "courseIds"} — {@code @ManyToMany} decoupled to {@code @ElementCollection} */
        final Map<String, String> collectionTypeToIdsField = new LinkedHashMap<>();
        /** Old field name → new field name for collection renames (e.g. {@code "courses" → "courseIds"}) */
        final Map<String, String> collectionOldToNewField  = new LinkedHashMap<>();
        /** Old field name → new field name for singular renames (e.g. {@code "payment" → "paymentId"}).
         *  Used to rename JPA Repository derived query methods after entity transformation. */
        final Map<String, String> singleOldToNewField      = new LinkedHashMap<>();

        boolean isEmpty() {
            return singleTypeToIdField.isEmpty() && collectionTypeToIdsField.isEmpty();
        }
    }

    private static class RequestInfoIndex {
        final Map<String, RequestInfo> requestInfo = new HashMap<>();
    }

    private static class RequestInfo {
        final boolean isRecord;
        final Set<String> recordComponents   = new HashSet<>();
        final Set<String> pojoGetters        = new HashSet<>();
        /** Getter name → declared return-type simple name (e.g. "getPrimaryTagId" → "Long"). */
        final Map<String, String> getterTypes = new HashMap<>();

        RequestInfo(boolean isRecord) {
            this.isRecord = isRecord;
        }
    }

    // =========================================================================
    // Pre-pass: entity remote-field collection
    // =========================================================================

    /**
     * Scans entity classes (filtered to {@code modulePackage}) and, for each, records
     * which remote types map to which decoupled field names — both singular and collection.
     *
     * <p>This pre-pass is necessary because service files and entity files are separate;
     * the main file walk transforms them independently, so cross-file knowledge must be
     * gathered up front.
     */
    private Map<String, RemoteFieldInfo> collectEntityRemoteIdFields(
            Path root, Set<String> remoteEntities, String modulePackage) {

        Map<String, RemoteFieldInfo> result = new HashMap<>();

        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(path -> {
                try {
                    Optional<CompilationUnit> cuOpt = javaParser.parse(path).getResult();
                    if (cuOpt.isEmpty()) return;
                    CompilationUnit cu = cuOpt.get();

                    if (!modulePackage.isBlank()) {
                        String filePkg = cu.getPackageDeclaration()
                                .map(pd -> pd.getNameAsString()).orElse("");
                        if (!filePkg.startsWith(modulePackage)) return;
                    }

                    for (ClassOrInterfaceDeclaration c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                        if (!hasAnnotation(c, "Entity")) continue;

                        RemoteFieldInfo info = new RemoteFieldInfo();

                        for (FieldDeclaration field : c.getFields()) {
                            // Singular remote relationship (@ManyToOne / @OneToOne)
                            if (hasAnyAnnotation(field, REL_ANNOS)) {
                                for (VariableDeclarator var : field.getVariables()) {
                                    String typeName = simpleTypeName(var.getType());
                                    if (typeName != null && remoteEntities.contains(typeName)) {
                                        String old     = var.getNameAsString();
                                        String newName = old.endsWith("Id") ? old : old + "Id";
                                        info.singleTypeToIdField.put(typeName, newName);
                                        if (!old.equals(newName)) {
                                            info.singleOldToNewField.put(old, newName);
                                        }
                                    }
                                }
                            }

                            // Collection remote relationship (@ManyToMany)
                            if (hasAnnotation(field, MANY_TO_MANY)) {
                                for (VariableDeclarator var : field.getVariables()) {
                                    Optional<String> generic = extractGenericTypeName(var.getType());
                                    if (generic.isPresent() && remoteEntities.contains(generic.get())) {
                                        String old     = var.getNameAsString();
                                        String newName = toIdsFieldName(old);
                                        info.collectionTypeToIdsField.put(generic.get(), newName);
                                        info.collectionOldToNewField.put(old, newName);
                                    }
                                }
                            }
                        }

                        if (!info.isEmpty()) {
                            result.put(c.getNameAsString(), info);
                        }
                    }
                } catch (Exception ignored) {}
            });
        } catch (IOException e) {
            log.warn("Could not collect entity remote id fields: {}", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a map of old getter name → new getter name for collection field renames
     * across all entities.  E.g. {@code "getCourses" → "getCourseIds"}.
     */
    private Map<String, String> buildCollectionGetterRenames(Map<String, RemoteFieldInfo> entityRemoteIdFields) {
        Map<String, String> renames = new HashMap<>();
        for (RemoteFieldInfo info : entityRemoteIdFields.values()) {
            for (Map.Entry<String, String> e : info.collectionOldToNewField.entrySet()) {
                renames.put("get" + upperFirst(e.getKey()), "get" + upperFirst(e.getValue()));
            }
        }
        return renames;
    }

    // =========================================================================
    // Entity scanning
    // =========================================================================

    /**
     * Scans source files to identify @Entity classes defined within {@code modulePackage}.
     *
     * <p>The package filter is critical when regenerating without a clean build: copied
     * model classes from other modules may already be present from a previous generation run.
     */
    private Set<String> findLocalEntityNames(Path root, String modulePackage) throws IOException {
        // Graph-first: query for @Entity nodes within the module package
        if (graph != null) {
            Set<String> local = graph.nodesWithAnnotation("Entity").stream()
                    .filter(n -> modulePackage.isBlank() || n.packageName().startsWith(modulePackage))
                    .map(GraphNode::simpleName)
                    .collect(java.util.stream.Collectors.toSet());
            if (!local.isEmpty()) {
                log.debug("📊 [Data] Graph-based local entity detection: {}", local);
                return local;
            }
        }

        // AST fallback when graph is absent or yielded no results
        Set<String> local = new HashSet<>();

        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(path -> {
                try {
                    Optional<CompilationUnit> cuOpt = javaParser.parse(path).getResult();
                    if (cuOpt.isEmpty()) return;
                    CompilationUnit cu = cuOpt.get();

                    if (!modulePackage.isBlank()) {
                        String filePkg = cu.getPackageDeclaration()
                                .map(pd -> pd.getNameAsString()).orElse("");
                        if (!filePkg.startsWith(modulePackage)) return;
                    }

                    for (ClassOrInterfaceDeclaration c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                        if (hasAnnotation(c, "Entity")) {
                            local.add(c.getNameAsString());
                        }
                    }
                } catch (Exception ignored) {}
            });
        }
        return local;
    }

    /**
     * Identifies entity types used in relationships by module-owned entities that are
     * not themselves defined locally.  Now handles {@code @ManyToMany} in addition to
     * {@code @ManyToOne}, {@code @OneToOne}, and {@code @OneToMany}.
     *
     * <p>Graph-first: when a {@link DependencyGraph} is available, derives remote entities
     * by following {@link EdgeKind#FIELD_REFERENCE} edges from local @Entity nodes to
     * @Entity targets outside the module package. Falls back to AST scanning when the
     * graph is absent.
     */
    private Set<String> findRemoteEntities(Path root,
                                            Set<String> localEntities,
                                            String modulePackage) throws IOException {
        // Graph-first: follow FIELD_REFERENCE edges from local entities to entity targets
        if (graph != null) {
            Set<String> localFqcns = graph.nodesWithAnnotation("Entity").stream()
                    .filter(n -> modulePackage.isBlank() || n.packageName().startsWith(modulePackage))
                    .map(GraphNode::fqcn)
                    .collect(java.util.stream.Collectors.toSet());

            Set<String> remote = new HashSet<>();
            for (String fqcn : localFqcns) {
                for (GraphEdge edge : graph.edgesFrom(fqcn)) {
                    if (edge.kind() != EdgeKind.FIELD_REFERENCE) continue;
                    graph.node(edge.targetNode()).ifPresent(target -> {
                        if (target.annotations().contains("Entity")
                                && !localFqcns.contains(target.fqcn())) {
                            remote.add(target.simpleName());
                        }
                    });
                }
            }
            if (!remote.isEmpty()) {
                log.debug("📊 [Data] Graph-based remote entity detection: {}", remote);
                return remote;
            }
        }

        // AST fallback
        Set<String> referenced = new HashSet<>();

        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(path -> {
                try {
                    Optional<CompilationUnit> cuOpt = javaParser.parse(path).getResult();
                    if (cuOpt.isEmpty()) return;
                    CompilationUnit cu = cuOpt.get();

                    if (!modulePackage.isBlank()) {
                        String filePkg = cu.getPackageDeclaration()
                                .map(pd -> pd.getNameAsString()).orElse("");
                        if (!filePkg.startsWith(modulePackage)) return;
                    }

                    for (ClassOrInterfaceDeclaration c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                        if (!hasAnnotation(c, "Entity")) continue;

                        for (FieldDeclaration field : c.getFields()) {
                            boolean isRelAnno   = hasAnyAnnotation(field, REL_ANNOS);
                            boolean isOneToMany = hasAnnotation(field, ONE_TO_MANY);
                            boolean isManyToMany = hasAnnotation(field, MANY_TO_MANY);

                            if (!isRelAnno && !isOneToMany && !isManyToMany) continue;

                            if (isOneToMany || isManyToMany) {
                                // Collection relationships: extract generic type (List<T> → T)
                                field.getVariables().forEach(v ->
                                        extractGenericTypeName(v.getType()).ifPresent(referenced::add));
                                continue;
                            }

                            // Singular relationships: direct type name
                            field.getVariables().forEach(v -> {
                                String typeName = simpleTypeName(v.getType());
                                if (typeName != null) referenced.add(typeName);
                            });
                        }
                    }
                } catch (Exception ignored) {}
            });
        }

        referenced.removeAll(localEntities);
        referenced.removeAll(Set.of("String", "Long", "Integer", "UUID", "Double", "Float", "Boolean"));
        return referenced;
    }

    // =========================================================================
    // Request-index building (for service-side call-site rewriting)
    // =========================================================================

    /**
     * Builds an index of Request classes to determine if they are Records or POJOs
     * and capture available fields/getters.
     *
     * <p>Lombok {@code @Data} / {@code @Getter}: when the class carries one of these
     * annotations there are no explicit {@code MethodDeclaration} nodes in the AST.
     * The getter names are inferred from the declared field names so that
     * {@link #tryBuildRequestIdAccessor} can still produce correct {@code request.getXxxId()}
     * expressions on the call site.
     */
    private RequestInfoIndex buildRequestIndex(Path serviceRoot) throws IOException {
        RequestInfoIndex idx = new RequestInfoIndex();

        try (Stream<Path> paths = Files.walk(serviceRoot)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.getFileName().toString().endsWith("Request.java"))
                    .forEach(path -> {
                        try {
                            Optional<CompilationUnit> cuOpt = javaParser.parse(path).getResult();
                            if (cuOpt.isEmpty()) return;
                            CompilationUnit cu = cuOpt.get();

                            // Records
                            for (RecordDeclaration rd : cu.findAll(RecordDeclaration.class)) {
                                String name = rd.getNameAsString();
                                RequestInfo info = new RequestInfo(true);
                                rd.getParameters().forEach(p -> info.recordComponents.add(p.getNameAsString()));
                                idx.requestInfo.put(name, info);
                            }

                            // POJO classes — structural: a data-holder has no JPA entity or
                            // Spring stereotype annotations. This replaces the name-based
                            // endsWith("Request") heuristic; any non-entity, non-component
                            // class can serve as a request DTO regardless of what it is named.
                            for (ClassOrInterfaceDeclaration cd : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                                if (cd.isInterface()) continue;
                                if (hasAnyAnnotation(cd, JPA_CLASS_ANNOS)) continue;
                                if (hasAnyAnnotation(cd, SPRING_STEREOTYPE_ANNOS)) continue;

                                String name = cd.getNameAsString();
                                RequestInfo info = new RequestInfo(false);

                                // Explicit getter methods
                                for (MethodDeclaration m : cd.getMethods()) {
                                    if (m.getNameAsString().startsWith("get")) {
                                        info.pojoGetters.add(m.getNameAsString());
                                        String retType = simpleTypeName(m.getType());
                                        if (retType != null) info.getterTypes.put(m.getNameAsString(), retType);
                                    }
                                }

                                // Lombok @Data / @Getter: infer getter names from field declarations
                                if (hasAnnotation(cd, "Data") || hasAnnotation(cd, "Getter")) {
                                    for (FieldDeclaration fd : cd.getFields()) {
                                        String typeName = simpleTypeName(fd.getElementType());
                                        fd.getVariables().forEach(v -> {
                                            String getter = "get" + upperFirst(v.getNameAsString());
                                            info.pojoGetters.add(getter);
                                            if (typeName != null) info.getterTypes.put(getter, typeName);
                                        });
                                    }
                                }

                                idx.requestInfo.put(name, info);
                            }

                        } catch (Exception ignored) {}
                    });
        }
        return idx;
    }

    // =========================================================================
    // File-level transformation
    // =========================================================================

    /**
     * Applies AST transformations to a single Java file: entity decoupling, service
     * logic rewriting, validator injection, and import cleanup.
     */
    private void transformFile(Path javaFile,
                               Set<String> localEntities,
                               Set<String> remoteEntities,
                               RequestInfoIndex requestIndex,
                               FractalModule module,
                               Map<String, RemoteFieldInfo> entityRemoteIdFields,
                               Map<String, String> collectionGetterRenames,
                               String basePackage) throws IOException {

        Optional<CompilationUnit> cuOpt = javaParser.parse(javaFile).getResult();
        if (cuOpt.isEmpty()) return;

        CompilationUnit cu = cuOpt.get();
        boolean modified = false;

        // Track which field names in this file were converted from @ManyToMany collections
        // so that updateEntityAccessors() knows to use List<String> instead of String.
        Set<String> collectionIdFields = new HashSet<>();

        for (ClassOrInterfaceDeclaration c : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (hasAnnotation(c, "Entity")) {
                modified |= transformEntityClass(c, remoteEntities, collectionIdFields);
            }
        }

        // Build a flat oldFieldName → newFieldName map for service-layer call-site rewriting
        Map<String, String> allFieldRenames = new LinkedHashMap<>();
        for (RemoteFieldInfo info : entityRemoteIdFields.values()) {
            allFieldRenames.putAll(info.singleOldToNewField);
            allFieldRenames.putAll(info.collectionOldToNewField);
        }
        modified |= transformServiceLogic(cu, remoteEntities, requestIndex, collectionGetterRenames, allFieldRenames);
        modified |= injectReferenceValidatorUsage(cu, entityRemoteIdFields, module, basePackage);

        // Remove imports for remote entity types that are no longer referenced.
        // Must run last so the check reflects the final AST state.
        modified |= removeRemoteImports(cu, remoteEntities);

        if (modified) {
            Files.writeString(javaFile, cu.toString());
            log.info("🔧 [Data] Refactored: {}", javaFile.getFileName());
        }
    }

    // =========================================================================
    // Entity class transformation
    // =========================================================================

    /**
     * Modifies entity classes: converts relationship fields to ID fields and updates accessors.
     *
     * @param collectionIdFields out-parameter populated with OLD field names whose type was
     *                           widened to {@code List<String>} (i.e. former {@code @ManyToMany}
     *                           fields).  Used by {@link #updateEntityAccessors} to choose the
     *                           correct accessor return / parameter type.
     */
    private boolean transformEntityClass(ClassOrInterfaceDeclaration entityClass,
                                         Set<String> remoteEntities,
                                         Set<String> collectionIdFields) {
        boolean modified = false;
        Map<String, String> fieldRenameMap = new HashMap<>();

        for (FieldDeclaration field : new ArrayList<>(entityClass.getFields())) {

            // Remove @OneToMany collections referencing remote entities
            if (hasAnnotation(field, ONE_TO_MANY)) {
                modified |= removeRemoteOneToManyCollections(entityClass, field, remoteEntities);
                continue;
            }

            // Convert @ManyToMany List<RemoteEntity> → @ElementCollection List<String> *Ids
            if (hasAnnotation(field, MANY_TO_MANY)) {
                modified |= convertRemoteManyToManyCollection(
                        entityClass, field, remoteEntities, fieldRenameMap, collectionIdFields);
                continue;
            }

            if (!hasAnyAnnotation(field, REL_ANNOS)) continue;

            // Convert singular entity references to String ID fields
            for (VariableDeclarator var : field.getVariables()) {
                String typeName = simpleTypeName(var.getType());
                if (typeName == null || !remoteEntities.contains(typeName)) continue;

                String oldFieldName = var.getNameAsString();
                String newFieldName = oldFieldName.endsWith("Id") ? oldFieldName : oldFieldName + "Id";

                var.setType("String");

                if (!oldFieldName.equals(newFieldName)) {
                    var.setName(newFieldName);
                    fieldRenameMap.put(oldFieldName, newFieldName);
                }

                removeAnnotationsByName(field, REL_ANNOS);
                removeAnnotationsByName(field, Set.of(ONE_TO_MANY));

                modified = true;
            }
        }

        if (!fieldRenameMap.isEmpty()) {
            modified |= updateEntityAccessors(entityClass, fieldRenameMap, collectionIdFields);
            modified |= renameFieldReferences(entityClass, fieldRenameMap);
        }

        return modified;
    }

    /**
     * Removes list fields annotated with {@code @OneToMany} if they reference a remote entity.
     */
    private boolean removeRemoteOneToManyCollections(ClassOrInterfaceDeclaration entityClass,
                                                     FieldDeclaration field,
                                                     Set<String> remoteEntities) {
        boolean modified = false;

        for (VariableDeclarator var : field.getVariables()) {
            Optional<String> generic = extractGenericTypeName(var.getType());
            if (generic.isPresent() && remoteEntities.contains(generic.get())) {
                String remoteType   = generic.get();
                String fieldName    = var.getNameAsString();
                String clientMethod = "findBy" + Character.toUpperCase(entityClass.getNameAsString().charAt(0))
                        + entityClass.getNameAsString().substring(1) + "Id";
                field.remove();
                // Inject multi-line guidance comment so developers know how to access this data
                entityClass.addOrphanComment(new LineComment(
                        " TODO [FractalX Gap 9d]: '" + fieldName + "' (OneToMany → " + remoteType + ") removed — cross-service relationship decoupled."));
                entityClass.addOrphanComment(new LineComment(
                        "   To retrieve these records, call the remote service via a NetScope client:"));
                entityClass.addOrphanComment(new LineComment(
                        "   " + remoteType + "Client." + clientMethod + "(this.id) — the remote service must expose"));
                entityClass.addOrphanComment(new LineComment(
                        "   @NetworkPublic List<" + remoteType + "> " + clientMethod + "(String parentId)"));
                entityClass.addOrphanComment(new LineComment(
                        "   See DECOMPOSITION_HINTS.md Gap 9d for details."));
                modified = true;
            }
        }
        return modified;
    }

    /**
     * Converts a remote {@code @ManyToMany List<Course> courses} field to
     * {@code @ElementCollection List<String> courseIds}.
     *
     * <p>Side effects:
     * <ul>
     *   <li>Adds old field name to {@code fieldRenameMap} and {@code collectionIdFields}</li>
     *   <li>Adds {@code jakarta.persistence.ElementCollection} import to the CU</li>
     *   <li>Adds {@code java.util.List} import to the CU (if not already present)</li>
     * </ul>
     */
    private boolean convertRemoteManyToManyCollection(ClassOrInterfaceDeclaration entityClass,
                                                       FieldDeclaration field,
                                                       Set<String> remoteEntities,
                                                       Map<String, String> fieldRenameMap,
                                                       Set<String> collectionIdFields) {
        boolean modified = false;

        for (VariableDeclarator var : field.getVariables()) {
            Optional<String> generic = extractGenericTypeName(var.getType());
            if (generic.isEmpty() || !remoteEntities.contains(generic.get())) continue;

            String oldFieldName = var.getNameAsString();       // e.g. "courses"
            String newFieldName = toIdsFieldName(oldFieldName); // e.g. "courseIds"

            // Change List<Course> → List<String>
            var.setType(listOfString());

            if (!oldFieldName.equals(newFieldName)) {
                var.setName(newFieldName);
                fieldRenameMap.put(oldFieldName, newFieldName);
                collectionIdFields.add(oldFieldName);
            }

            // Remove @ManyToMany / @JoinTable; replace with @ElementCollection
            removeAnnotationsByName(field, Set.of(MANY_TO_MANY, "JoinTable"));
            field.addAnnotation(new MarkerAnnotationExpr("ElementCollection"));

            // Ensure the required imports exist
            entityClass.findCompilationUnit().ifPresent(cu -> {
                ensureImport(cu, "jakarta.persistence.ElementCollection");
                ensureImport(cu, "java.util.List");
            });

            entityClass.addOrphanComment(new LineComment(
                    " Decoupled cross-service ManyToMany: " + generic.get()
                            + " replaced by ElementCollection " + newFieldName));
            modified = true;
        }
        return modified;
    }

    /**
     * Updates getter and setter signatures and bodies to match renamed ID fields.
     *
     * @param collectionIdFields set of OLD field names that were widened to {@code List<String>}
     *                           (former {@code @ManyToMany} fields).  These get
     *                           {@code List<String>} as their accessor type instead of
     *                           {@code String}.
     */
    private boolean updateEntityAccessors(ClassOrInterfaceDeclaration entityClass,
                                          Map<String, String> renameMap,
                                          Set<String> collectionIdFields) {
        boolean modified = false;

        // Field-first: for each field being renamed, directly look up its accessor by the
        // conventional name rather than scanning all methods for name-prefix patterns.
        // This is structural — driven by what fields exist in the rename map, not by
        // guessing method roles from their names.
        for (Map.Entry<String, String> entry : renameMap.entrySet()) {
            String oldField = entry.getKey();
            String newField = entry.getValue();
            boolean isList  = collectionIdFields.contains(oldField);
            Type newType    = isList ? listOfString() : new ClassOrInterfaceType(null, "String");

            // Record-style accessor: method named exactly as the field (0 params)
            for (MethodDeclaration m : entityClass.getMethodsByName(oldField)) {
                if (!m.getParameters().isEmpty()) continue;
                m.setType(newType);
                m.setName(newField);
                m.findAll(ReturnStmt.class).forEach(r ->
                        r.getExpression().ifPresent(expr -> {
                            if (expr.isNameExpr()
                                    && expr.asNameExpr().getNameAsString().equals(oldField)) {
                                r.setExpression(new NameExpr(newField));
                            }
                        }));
                modified = true;
            }

            // Bean getter: getXxx() with 0 params
            for (MethodDeclaration m : entityClass.getMethodsByName("get" + upperFirst(oldField))) {
                if (!m.getParameters().isEmpty()) continue;
                m.setType(newType);
                m.setName("get" + upperFirst(newField));
                m.findAll(ReturnStmt.class).forEach(r ->
                        r.getExpression().ifPresent(expr -> {
                            if (expr.isNameExpr()
                                    && expr.asNameExpr().getNameAsString().equals(oldField)) {
                                r.setExpression(new NameExpr(newField));
                            }
                            if (expr.isFieldAccessExpr()
                                    && expr.asFieldAccessExpr().getNameAsString().equals(oldField)) {
                                expr.asFieldAccessExpr().setName(newField);
                            }
                        }));
                modified = true;
            }

            // Bean setter: setXxx(T) with 1 param
            for (MethodDeclaration m : entityClass.getMethodsByName("set" + upperFirst(oldField))) {
                if (m.getParameters().size() != 1) continue;
                m.setName("set" + upperFirst(newField));
                m.getParameter(0).setType(newType);
                m.getParameter(0).setName(newField);
                m.findAll(AssignExpr.class).forEach(a -> {
                    Expression target = a.getTarget();
                    if (target.isFieldAccessExpr()) {
                        FieldAccessExpr fa = target.asFieldAccessExpr();
                        if (fa.getNameAsString().equals(oldField)) fa.setName(newField);
                    } else if (target.isNameExpr()
                            && target.asNameExpr().getNameAsString().equals(oldField)) {
                        a.setTarget(new NameExpr(newField));
                    }
                    Expression value = a.getValue();
                    if (value.isNameExpr()
                            && value.asNameExpr().getNameAsString().equals(oldField)) {
                        a.setValue(new NameExpr(newField));
                    }
                });
                modified = true;
            }
        }

        return modified;
    }

    /**
     * Updates internal field references ({@code this.field → this.fieldId}).
     */
    private boolean renameFieldReferences(ClassOrInterfaceDeclaration entityClass,
                                          Map<String, String> renameMap) {
        boolean modified = false;

        for (NameExpr ne : entityClass.findAll(NameExpr.class)) {
            String old = ne.getNameAsString();
            if (renameMap.containsKey(old)) {
                ne.setName(renameMap.get(old));
                modified = true;
            }
        }

        for (FieldAccessExpr fa : entityClass.findAll(FieldAccessExpr.class)) {
            String old = fa.getNameAsString();
            if (renameMap.containsKey(old)) {
                fa.setName(renameMap.get(old));
                modified = true;
            }
        }

        return modified;
    }

    // =========================================================================
    // Service logic transformation
    // =========================================================================

    /**
     * Updates service logic to handle ID fields instead of object references.
     * Handles variable instantiation, setId calls, setter calls, old collection
     * getter renames, and chained access warnings.
     *
     * @param collectionGetterRenames map of {@code oldGetterName → newGetterName} derived from
     *                                the entity pre-pass (e.g. {@code "getCourses" → "getCourseIds"})
     */
    private boolean transformServiceLogic(CompilationUnit cu,
                                          Set<String> remoteEntities,
                                          RequestInfoIndex requestIndex,
                                          Map<String, String> collectionGetterRenames,
                                          Map<String, String> fieldRenames) {
        boolean modified = false;

        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            // Step 0: Remove method parameters whose type is a cross-module entity.
            // e.g. createBudget(Request r, Tag primaryTag, List<Tag> tags)
            //   → createBudget(Request r)  (Tag params stripped, body fixed)
            modified |= stripRemoteEntityMethodParams(method, remoteEntities);

            Set<String> localNames = new HashSet<>();
            Map<String, String> remoteVarToIdVar = new HashMap<>();
            // Vars holding remote entities returned from service calls — kept as objects
            Set<String> remoteEntityServiceVars = new HashSet<>();

            method.getParameters().forEach(p -> localNames.add(p.getNameAsString()));

            // Step 1: Update local variable declarations (Entity e → String eId)
            // Skip vars initialised from method calls — those are service-call results.
            for (VariableDeclarator vd : method.findAll(VariableDeclarator.class)) {
                String typeName = simpleTypeName(vd.getType());
                if (typeName == null || !remoteEntities.contains(typeName)) continue;

                if (vd.getInitializer().isPresent() && vd.getInitializer().get().isMethodCallExpr()) {
                    remoteEntityServiceVars.add(vd.getNameAsString());
                    continue;
                }

                String oldName = vd.getNameAsString();
                String newName = oldName.endsWith("Id") ? oldName : oldName + "Id";

                vd.setType("String");
                vd.setName(newName);

                if (vd.getInitializer().isPresent() && vd.getInitializer().get().isObjectCreationExpr()) {
                    ObjectCreationExpr oce = vd.getInitializer().get().asObjectCreationExpr();
                    String createdType = simpleTypeName(oce.getType());
                    if (createdType != null && remoteEntities.contains(createdType)) {
                        vd.setInitializer(new NullLiteralExpr());
                    }
                }

                remoteVarToIdVar.put(oldName, newName);
                localNames.add(newName);
                modified = true;
            }

            // Step 2: Update setId calls (e.setId(x) → eId = x)
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                if (call.getScope().isEmpty()) continue;
                if (!call.getNameAsString().equals("setId")) continue;
                if (call.getArguments().size() != 1) continue;

                Expression scope = call.getScope().get();
                if (!scope.isNameExpr()) continue;

                String varName = scope.asNameExpr().getNameAsString();
                if (!remoteVarToIdVar.containsKey(varName)) continue;

                String idVar = remoteVarToIdVar.get(varName);
                AssignExpr assign = new AssignExpr(
                        new NameExpr(idVar), call.getArgument(0), AssignExpr.Operator.ASSIGN);

                Optional<Statement> stmtOpt = call.findAncestor(Statement.class);
                if (stmtOpt.isPresent() && stmtOpt.get().isExpressionStmt()) {
                    stmtOpt.get().asExpressionStmt().setExpression(assign);
                    modified = true;
                }
            }

            // Step 3: Update setter calls (setEntity(e) → setEntityId(eId) / setEntityId(req.getId()))
            for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                if (!call.getNameAsString().startsWith("set")) continue;
                if (call.getArguments().size() != 1) continue;

                Expression arg = call.getArgument(0);
                if (!arg.isNameExpr()) continue;

                String argName = arg.asNameExpr().getNameAsString();

                // Case A: Variable was renamed locally
                if (remoteVarToIdVar.containsKey(argName)) {
                    String idVar = remoteVarToIdVar.get(argName);
                    if (!call.getNameAsString().endsWith("Id")) {
                        call.setName(call.getNameAsString() + "Id");
                    }
                    call.setArgument(0, new NameExpr(idVar));
                    modified = true;
                    continue;
                }

                // Case B: Try to resolve ID from Request object
                Optional<Expression> requestAccessor =
                        tryBuildRequestIdAccessor(method, call.getNameAsString(), requestIndex);
                if (requestAccessor.isPresent()) {
                    if (!call.getNameAsString().endsWith("Id")) {
                        call.setName(call.getNameAsString() + "Id");
                    }
                    call.setArgument(0, requestAccessor.get());
                    modified = true;
                    continue;
                }

                // Case C: Variable holds a remote entity returned from a service call.
                // Rename setter to *Id and convert .getId() to String.
                if (remoteEntityServiceVars.contains(argName)) {
                    if (!call.getNameAsString().endsWith("Id")) {
                        call.setName(call.getNameAsString() + "Id");
                    }
                    MethodCallExpr getIdCall = new MethodCallExpr(new NameExpr(argName), "getId");
                    call.setArgument(0, new MethodCallExpr(
                            new NameExpr("String"), "valueOf", new NodeList<>(getIdCall)));
                    modified = true;
                }
            }

            // Step 4: Rename old collection getters (getCourses → getCourseIds) and warn
            //         on any complex chain that accesses entity properties via the renamed getter.
            if (!collectionGetterRenames.isEmpty()) {
                final boolean[] step4Modified = {false};

                for (MethodCallExpr call : new ArrayList<>(method.findAll(MethodCallExpr.class))) {
                    String callName = call.getNameAsString();
                    if (!collectionGetterRenames.containsKey(callName)) continue;

                    String newGetterName = collectionGetterRenames.get(callName);
                    call.setName(newGetterName);
                    step4Modified[0] = true;

                    // If this renamed call is the scope of an outer chained call, the chain
                    // now accesses List<String> items rather than entity objects → warn developer.
                    call.getParentNode().ifPresent(parent -> {
                        if (parent instanceof MethodCallExpr outerCall
                                && outerCall.getScope().map(s -> s == call).orElse(false)) {
                            call.findAncestor(Statement.class).ifPresent(stmt -> {
                                if (stmt.getComment().isEmpty()) {
                                    stmt.setComment(new LineComment(
                                            " [FractalX] DECOUPLING WARNING: " + newGetterName
                                                    + "() returns List<String>."
                                                    + " This chain accesses remote entity properties"
                                                    + " that no longer exist here."
                                                    + " Rewrite using a remote service lookup."));
                                }
                            });
                        }
                    });
                }

                modified |= step4Modified[0];
            }

            // Step 5: Rewrite builder and findBy/countBy/existsBy calls that pass a remote entity
            //         variable (obtained from a service call) where the entity field was renamed.
            //
            //   Order.builder().customer(customer)          → .customerId(String.valueOf(customer.getId()))
            //   orderRepo.findByCustomer(customer)          → findByCustomerId(String.valueOf(customer.getId()))
            //
            // This covers patterns missed by Step 3 (which only handles set* prefixes).
            if (!fieldRenames.isEmpty() && !remoteEntityServiceVars.isEmpty()) {
                for (MethodCallExpr call : new ArrayList<>(method.findAll(MethodCallExpr.class))) {
                    if (call.getArguments().size() != 1) continue;
                    Expression arg = call.getArgument(0);
                    if (!arg.isNameExpr()) continue;
                    String argName = arg.asNameExpr().getNameAsString();
                    if (!remoteEntityServiceVars.contains(argName)) continue;

                    String methodName = call.getNameAsString();

                    // Exact match: builder method name = old field name (e.g., customer(customer))
                    if (fieldRenames.containsKey(methodName)) {
                        String newFieldName = fieldRenames.get(methodName);
                        call.setName(newFieldName);
                        call.setArgument(0, new MethodCallExpr(
                                new NameExpr("String"), "valueOf",
                                new NodeList<>(new MethodCallExpr(new NameExpr(argName), "getId"))));
                        modified = true;
                        log.debug("Step5: renamed builder call .{}({}) → .{}(String.valueOf({}.getId()))",
                                methodName, argName, newFieldName, argName);
                        continue;
                    }

                    // Prefix match: findBy/countBy/existsBy/deleteBy + UpperFirst(oldFieldName)
                    for (Map.Entry<String, String> entry : fieldRenames.entrySet()) {
                        String oldSeg = upperFirst(entry.getKey());   // e.g. "Customer"
                        String newSeg = upperFirst(entry.getValue()); // e.g. "CustomerId"
                        for (String prefix : new String[]{"findBy", "countBy", "existsBy", "deleteBy",
                                "findAllBy", "findFirstBy", "findTopBy"}) {
                            if (methodName.startsWith(prefix + oldSeg)) {
                                String renamedMethod = methodName.replace(prefix + oldSeg, prefix + newSeg);
                                call.setName(renamedMethod);
                                call.setArgument(0, new MethodCallExpr(
                                        new NameExpr("String"), "valueOf",
                                        new NodeList<>(new MethodCallExpr(new NameExpr(argName), "getId"))));
                                modified = true;
                                log.debug("Step5: renamed repo call {}({}) → {}(String.valueOf({}.getId()))",
                                        methodName, argName, renamedMethod, argName);
                                break;
                            }
                        }
                    }
                }
            }

            // Step 6: Rename singular-field getters in service/lambda code.
            //
            //   p.getOrder()           → p.getOrderId()
            //   p.getOrder().getId()   → p.getOrderId()   (chain collapsed to String)
            //
            // Build getter rename map from fieldRenames: "order" → "orderId"
            // translates to getOrder → getOrderId.
            if (!fieldRenames.isEmpty()) {
                Map<String, String> singularGetterRenames = new LinkedHashMap<>();
                for (Map.Entry<String, String> entry : fieldRenames.entrySet()) {
                    singularGetterRenames.put(
                            "get" + upperFirst(entry.getKey()),
                            "get" + upperFirst(entry.getValue()));
                }

                // Collect all matching getter call nodes (copy list to avoid CME)
                List<MethodCallExpr> getterCalls = new ArrayList<>(
                        method.findAll(MethodCallExpr.class, call ->
                                singularGetterRenames.containsKey(call.getNameAsString())));

                for (MethodCallExpr getterCall : getterCalls) {
                    String newGetterName = singularGetterRenames.get(getterCall.getNameAsString());

                    // Check whether this getter is the scope of a chained .getId() call
                    // e.g. p.getOrder().getId() — parent call is "getId" with getterCall as scope
                    boolean chainedWithGetId = getterCall.getParentNode()
                            .filter(parent -> parent instanceof MethodCallExpr outerCall
                                    && "getId".equals(outerCall.getNameAsString())
                                    && outerCall.getArguments().isEmpty()
                                    && outerCall.getScope().map(s -> s == getterCall).orElse(false))
                            .isPresent();

                    if (chainedWithGetId) {
                        // Replace the outer p.getOrder().getId() with p.getOrderId()
                        MethodCallExpr outerCall = (MethodCallExpr) getterCall.getParentNode().get();
                        MethodCallExpr replacement = new MethodCallExpr(
                                getterCall.getScope().orElse(null), newGetterName);
                        outerCall.replace(replacement);
                        modified = true;
                        log.debug("Step6: collapsed chain {}.getId() → {}",
                                getterCall.getNameAsString(), newGetterName);
                    } else if (getterCall.findAncestor(MethodCallExpr.class)
                            .map(p -> !singularGetterRenames.containsKey(p.getNameAsString()))
                            .orElse(true)) {
                        // Standalone getter: just rename getOrder() → getOrderId()
                        // (only if this node is still in the AST — it might have been
                        //  replaced above as part of a chain)
                        try {
                            getterCall.setName(newGetterName);
                            modified = true;
                            log.debug("Step6: renamed getter {} → {}", getterCall.getNameAsString(), newGetterName);
                        } catch (Exception ignored) {
                            // node may have been detached during chain replacement
                        }
                    }
                }
            }
        }

        return modified;
    }

    // =========================================================================
    // Remote entity method-parameter stripping
    // =========================================================================

    /**
     * Removes method parameters whose declared type (or generic type argument) is a
     * cross-module entity type, then fixes any body statements that still reference the
     * removed parameter names.
     *
     * <p>Example:
     * <pre>
     *   createBudgetWithTag(Request r, Tag primaryTag, List&lt;Tag&gt; tags)
     *   →  createBudgetWithTag(Request r)
     *   budget.setTags(tags) → budget.setTagIds(new java.util.ArrayList&lt;&gt;())
     * </pre>
     */
    private boolean stripRemoteEntityMethodParams(MethodDeclaration method,
                                                  Set<String> remoteEntities) {
        // Collect params whose base or generic type is a remote entity
        Set<String> removedNames = new LinkedHashSet<>();
        Map<String, Boolean> isListParam = new HashMap<>();

        for (Parameter param : new ArrayList<>(method.getParameters())) {
            String base = simpleTypeName(param.getType());
            boolean directMatch = base != null && remoteEntities.contains(base);
            boolean genericMatch = !directMatch
                    && extractGenericTypeName(param.getType())
                               .map(remoteEntities::contains).orElse(false);
            if (directMatch || genericMatch) {
                removedNames.add(param.getNameAsString());
                isListParam.put(param.getNameAsString(), genericMatch);
                method.getParameters().remove(param);
            }
        }

        if (removedNames.isEmpty()) return false;
        if (method.getBody().isEmpty()) return true;

        BlockStmt body = method.getBody().get();

        for (Statement stmt : new ArrayList<>(body.getStatements())) {
            boolean refsRemovedParam = stmt.findAll(NameExpr.class).stream()
                    .map(NameExpr::getNameAsString)
                    .anyMatch(removedNames::contains);
            if (!refsRemovedParam) continue;

            if (!stmt.isExpressionStmt()) {
                body.getStatements().remove(stmt);
                continue;
            }

            Expression expr = stmt.asExpressionStmt().getExpression();
            if (!expr.isMethodCallExpr()) {
                body.getStatements().remove(stmt);
                continue;
            }

            MethodCallExpr call = expr.asMethodCallExpr();
            String callName = call.getNameAsString();

            // Setter call with removed param as argument
            if (callName.startsWith("set") && call.getArguments().size() == 1
                    && call.getArgument(0).isNameExpr()
                    && removedNames.contains(call.getArgument(0).asNameExpr().getNameAsString())) {
                String paramName = call.getArgument(0).asNameExpr().getNameAsString();
                if (isListParam.getOrDefault(paramName, false)) {
                    // List<RemoteEntity> param: setFoo(listParam) → setFooIds(new ArrayList<>())
                    // Use entity naming convention to derive the correct id-list setter name.
                    String suffix      = callName.substring(3);              // "Tags"
                    String idFieldName = toIdsFieldName(lowerFirst(suffix)); // "tagIds"
                    call.setName("set" + upperFirst(idFieldName));           // "setTagIds"
                    call.setArgument(0, new ObjectCreationExpr(null,
                            new ClassOrInterfaceType(null, "java.util.ArrayList"),
                            new NodeList<>()));
                }
                // Singular entity param: leave the statement so Step 3 Case B can rewrite
                // setFoo(param) → setFooId(request.getFooId()).
                continue;
            }

            // Log call: drop the removed-param arguments (dangling {} in format string is harmless)
            if (callName.equals("info") || callName.equals("debug")
                    || callName.equals("warn") || callName.equals("error")) {
                for (int i = call.getArguments().size() - 1; i >= 1; i--) {
                    Expression arg = call.getArgument(i);
                    if (arg.isNameExpr()
                            && removedNames.contains(arg.asNameExpr().getNameAsString())) {
                        call.getArguments().remove(i);
                    }
                }
                continue;
            }

            // Any other statement referencing a removed param — remove it
            body.getStatements().remove(stmt);
        }

        return true;
    }

    // ReferenceValidator injection into @Service classes
    // =========================================================================

    /**
     * Injects a {@code ReferenceValidator} field, constructor parameter, and pre-save
     * validation calls into any {@code @Service} class found in {@code cu}.
     *
     * <p>Skips injection when {@code entityRemoteIdFields} is empty (no remote relationships
     * exist) or when the class already carries a {@code referenceValidator} field (idempotent).
     */
    private boolean injectReferenceValidatorUsage(CompilationUnit cu,
                                                   Map<String, RemoteFieldInfo> entityRemoteIdFields,
                                                   FractalModule module, String basePackage) {
        if (entityRemoteIdFields.isEmpty() || module.getDependencies().isEmpty()) return false;

        boolean modified = false;

        String validationPackage = basePackage + ".validation";

        for (ClassOrInterfaceDeclaration svcClass : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (svcClass.isInterface()) continue;
            if (!hasAnnotation(svcClass, "Service")) continue;

            // Idempotency guard
            boolean alreadyInjected = svcClass.getFields().stream()
                    .anyMatch(f -> f.getElementType().asString().equals("ReferenceValidator"));
            if (alreadyInjected) continue;

            // 1. Add private final field
            svcClass.addField("ReferenceValidator", "referenceValidator",
                    Modifier.Keyword.PRIVATE, Modifier.Keyword.FINAL);

            // 2. Import
            ensureImport(cu, validationPackage + ".ReferenceValidator");

            // 3. Update constructor (or create one if none exist and no Lombok ctor annotation)
            List<ConstructorDeclaration> ctors = svcClass.getConstructors();
            if (!ctors.isEmpty()) {
                ConstructorDeclaration ctor = ctors.get(0);
                ctor.addParameter(new Parameter(
                        new ClassOrInterfaceType(null, "ReferenceValidator"), "referenceValidator"));
                ctor.getBody().addStatement(new ExpressionStmt(new AssignExpr(
                        new FieldAccessExpr(new ThisExpr(), "referenceValidator"),
                        new NameExpr("referenceValidator"),
                        AssignExpr.Operator.ASSIGN)));
            } else if (!hasAnnotation(svcClass, "RequiredArgsConstructor")
                    && !hasAnnotation(svcClass, "AllArgsConstructor")) {
                // No constructor and no Lombok — generate a minimal one
                ConstructorDeclaration ctor = svcClass.addConstructor(Modifier.Keyword.PUBLIC);
                ctor.addParameter(new Parameter(
                        new ClassOrInterfaceType(null, "ReferenceValidator"), "referenceValidator"));
                ctor.getBody().addStatement(new ExpressionStmt(new AssignExpr(
                        new FieldAccessExpr(new ThisExpr(), "referenceValidator"),
                        new NameExpr("referenceValidator"),
                        AssignExpr.Operator.ASSIGN)));
            }
            // If @RequiredArgsConstructor / @AllArgsConstructor: the added final field is enough.

            // 4. Inject validation calls before repository.save() in service methods
            for (MethodDeclaration method : svcClass.getMethods()) {
                modified |= injectValidationBeforeSave(method, entityRemoteIdFields);
            }

            modified = true;
        }

        return modified;
    }

    /**
     * For each {@code repository.save(entityVar)} call in {@code method}, inserts
     * {@code referenceValidator.validateXExists(entityVar.getXId())} statements
     * (and the collection variant) immediately before the save statement.
     *
     * <p>Uses {@link ValidationNaming} to derive method names so they are guaranteed to
     * match what {@link ReferenceValidatorGenerator} generates in the validator bean.
     */
    private boolean injectValidationBeforeSave(MethodDeclaration method,
                                               Map<String, RemoteFieldInfo> entityRemoteIdFields) {
        boolean modified = false;

        for (MethodCallExpr saveCall : new ArrayList<>(method.findAll(MethodCallExpr.class))) {
            if (!saveCall.getNameAsString().equals("save")) continue;
            if (saveCall.getArguments().size() != 1) continue;

            Expression arg = saveCall.getArgument(0);
            if (!arg.isNameExpr()) continue;

            String entityVarName = arg.asNameExpr().getNameAsString();

            // Resolve the declared type of the entity variable within this method scope.
            // Also detect whether the variable was loaded from the repository (existing entity)
            // vs newly created — only inject validation for new entities. Updating an existing
            // entity (e.g. status change) should not re-validate foreign keys that were already
            // validated at creation time; doing so causes unnecessary cross-service gRPC calls
            // that fail when the remote service is temporarily unavailable.
            String entityType = null;
            boolean fetchedFromRepository = false;
            for (VariableDeclarator vd : method.findAll(VariableDeclarator.class)) {
                if (vd.getNameAsString().equals(entityVarName)) {
                    entityType = simpleTypeName(vd.getType());
                    // Check if the initializer is a repository/service lookup
                    if (vd.getInitializer().isPresent()) {
                        String init = vd.getInitializer().get().toString().toLowerCase();
                        fetchedFromRepository = SpringDataUtils.isRepositoryFetchExpression(init);
                    }
                    break;
                }
            }
            if (entityType == null) continue;
            // Skip validation for entities loaded from the repository — their foreign keys
            // were validated at creation time and do not need to be re-checked on updates.
            if (fetchedFromRepository) continue;

            RemoteFieldInfo info = entityRemoteIdFields.get(entityType);
            if (info == null || info.isEmpty()) continue;

            Optional<Statement> stmtOpt = saveCall.findAncestor(Statement.class);
            if (stmtOpt.isEmpty()) continue;
            Statement saveStmt = stmtOpt.get();

            Optional<BlockStmt> blockOpt = saveStmt.findAncestor(BlockStmt.class);
            if (blockOpt.isEmpty()) continue;
            BlockStmt block = blockOpt.get();

            NodeList<Statement> stmts = block.getStatements();
            int saveIdx = -1;
            for (int i = 0; i < stmts.size(); i++) {
                if (stmts.get(i) == saveStmt) { saveIdx = i; break; }
            }
            if (saveIdx < 0) continue;

            List<Statement> validateStmts = new ArrayList<>();

            // Singular: referenceValidator.validatePaymentExists(entity.getPaymentId())
            for (Map.Entry<String, String> entry : info.singleTypeToIdField.entrySet()) {
                String type      = entry.getKey();   // "Payment"
                String idField   = entry.getValue(); // "paymentId"
                MethodCallExpr getter   = new MethodCallExpr(
                        new NameExpr(entityVarName), "get" + upperFirst(idField));
                MethodCallExpr validate = new MethodCallExpr(
                        new NameExpr("referenceValidator"),
                        ValidationNaming.singleValidateMethod(type),   // shared utility
                        new NodeList<>(getter));
                validateStmts.add(new ExpressionStmt(validate));
            }

            // Collection: referenceValidator.validateAllCourseExist(entity.getCourseIds())
            for (Map.Entry<String, String> entry : info.collectionTypeToIdsField.entrySet()) {
                String type     = entry.getKey();    // "Course"
                String idsField = entry.getValue();  // "courseIds"
                MethodCallExpr getter   = new MethodCallExpr(
                        new NameExpr(entityVarName), "get" + upperFirst(idsField));
                MethodCallExpr validate = new MethodCallExpr(
                        new NameExpr("referenceValidator"),
                        ValidationNaming.collectionValidateMethod(type), // shared utility
                        new NodeList<>(getter));
                validateStmts.add(new ExpressionStmt(validate));
            }

            for (int i = 0; i < validateStmts.size(); i++) {
                stmts.add(saveIdx + i, validateStmts.get(i));
            }

            if (!validateStmts.isEmpty()) modified = true;
        }

        return modified;
    }

    // =========================================================================
    // Request accessor helper
    // =========================================================================

    /**
     * Attempts to build an ID accessor (e.g. {@code request.getCustomerId()}) based on
     * the setter name and request type.
     */
    private Optional<Expression> tryBuildRequestIdAccessor(MethodDeclaration method,
                                                            String setterName,
                                                            RequestInfoIndex requestIndex) {
        if (!setterName.startsWith("set") || setterName.length() <= 3) return Optional.empty();
        String base   = lowerFirst(setterName.substring(3));
        String idName = base.endsWith("Id") ? base : base + "Id";

        Optional<Parameter> requestParamOpt = method.getParameters().stream()
                .filter(p -> p.getNameAsString().equals("request"))
                .findFirst();
        if (requestParamOpt.isEmpty()) return Optional.empty();

        String requestType = simpleTypeName(requestParamOpt.get().getType());
        if (requestType == null) return Optional.empty();

        RequestInfo info = requestIndex.requestInfo.get(requestType);
        if (info == null) return Optional.empty();

        if (info.isRecord) {
            if (info.recordComponents.contains(idName)) {
                return Optional.of(new MethodCallExpr(new NameExpr("request"), idName));
            }
        } else {
            String getter = "get" + upperFirst(idName);
            if (info.pojoGetters.contains(getter)) {
                MethodCallExpr getterCall = new MethodCallExpr(new NameExpr("request"), getter);
                // If the DTO field type is not String, the converted entity ID field (String) would
                // cause a type mismatch. Wrap with String.valueOf() to coerce safely.
                String fieldType = info.getterTypes.get(getter);
                if (fieldType != null && !fieldType.equals("String")) {
                    return Optional.of(new MethodCallExpr(new NameExpr("String"), "valueOf",
                            new NodeList<>(getterCall)));
                }
                return Optional.of(getterCall);
            }
        }

        return Optional.empty();
    }

    // =========================================================================
    // Import cleanup
    // =========================================================================

    /**
     * Removes imports for remote entity types that are no longer referenced in the file.
     *
     * <p>Must run after all AST transformations so the reference check reflects the
     * final state of the file.
     */
    private boolean removeRemoteImports(CompilationUnit cu, Set<String> remoteEntities) {
        boolean modified = false;

        Set<String> stillReferenced = new HashSet<>();
        cu.findAll(ClassOrInterfaceType.class).forEach(t -> stillReferenced.add(t.getNameAsString()));

        List<ImportDeclaration> toRemove = new ArrayList<>();
        for (ImportDeclaration imp : cu.getImports()) {
            String last = imp.getName().getIdentifier();
            if (remoteEntities.contains(last) && !stillReferenced.contains(last)) {
                toRemove.add(imp);
            }
        }

        for (ImportDeclaration imp : toRemove) {
            cu.getImports().remove(imp);
            modified = true;
        }
        return modified;
    }

    // =========================================================================
    // Utility methods
    // =========================================================================

    private boolean hasAnnotation(NodeWithAnnotations<?> node, String annoSimpleName) {
        return node.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals(annoSimpleName));
    }

    private boolean hasAnyAnnotation(NodeWithAnnotations<?> node, Set<String> annoNames) {
        for (AnnotationExpr a : node.getAnnotations()) {
            if (annoNames.contains(a.getNameAsString())) return true;
        }
        return false;
    }

    private void removeAnnotationsByName(NodeWithAnnotations<?> node, Set<String> annoNames) {
        node.getAnnotations().removeIf(a -> annoNames.contains(a.getNameAsString()));
    }

    private String simpleTypeName(Type t) {
        if (t.isPrimitiveType()) return null;
        if (t.isArrayType()) return simpleTypeName(t.asArrayType().getComponentType());
        if (t.isClassOrInterfaceType()) {
            return t.asClassOrInterfaceType().getName().getIdentifier();
        }
        return t.asString();
    }

    private Optional<String> extractGenericTypeName(Type t) {
        if (!t.isClassOrInterfaceType()) return Optional.empty();
        ClassOrInterfaceType ct = t.asClassOrInterfaceType();
        if (ct.getTypeArguments().isEmpty()) return Optional.empty();
        NodeList<Type> args = ct.getTypeArguments().get();
        if (args.isEmpty()) return Optional.empty();
        String name = simpleTypeName(args.get(0));
        return Optional.ofNullable(name);
    }

    /**
     * Converts a plural collection field name to its decoupled ID-list name.
     * <p>Examples: {@code "courses" → "courseIds"}, {@code "products" → "productIds"},
     * {@code "categories" → "categoryIds"} (-ies → -y), {@code "aliases" → "aliasIds"} (-ses → -s).
     *
     * <p>Uses standard English plural morphology rules — longer suffixes are matched
     * first so that sibilant plurals ({@code ses}, {@code xes}, {@code ches}) are
     * singularized before the plain-{@code s} rule applies.
     */
    private String toIdsFieldName(String fieldName) {
        String lower = fieldName.toLowerCase();
        // Sibilant -es after x, z, ch, sh
        if (lower.endsWith("xes") || lower.endsWith("zes")
                || lower.endsWith("ches") || lower.endsWith("shes")) {
            return fieldName.substring(0, fieldName.length() - 2) + "Ids";
        }
        // Double-s sibilant: classes → class
        if (lower.endsWith("sses")) {
            return fieldName.substring(0, fieldName.length() - 2) + "Ids";
        }
        // -ses only when the char just before "ses" is a vowel (buses, aliases)
        // but NOT when it's a consonant (courses, horses) which are regular plurals
        if (lower.endsWith("ses") && lower.length() > 4
                && "aeiou".indexOf(lower.charAt(lower.length() - 4)) >= 0) {
            return fieldName.substring(0, fieldName.length() - 2) + "Ids";
        }
        // -ies → -y: categories → category
        if (lower.endsWith("ies") && lower.length() > 3) {
            return fieldName.substring(0, fieldName.length() - 3) + "yIds";
        }
        // Plain -s (not -ss): courses → course, products → product
        if (lower.endsWith("s") && !lower.endsWith("ss") && lower.length() > 1) {
            return fieldName.substring(0, fieldName.length() - 1) + "Ids";
        }
        return fieldName + "Ids";
    }

    /** Returns a {@code List<String>} type node for use in setType() calls. */
    private static ClassOrInterfaceType listOfString() {
        return new ClassOrInterfaceType(null, "List")
                .setTypeArguments(new ClassOrInterfaceType(null, "String"));
    }

    /** Adds the given fully-qualified import to {@code cu} if not already present. */
    private void ensureImport(CompilationUnit cu, String fqn) {
        if (cu == null) return;
        boolean present = cu.getImports().stream()
                .anyMatch(imp -> imp.getNameAsString().equals(fqn));
        if (!present) {
            cu.addImport(fqn);
        }
    }

    private String lowerFirst(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private String upperFirst(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
