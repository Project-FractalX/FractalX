package org.fractalx.core.graph;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds a {@link DependencyGraph} by parsing all Java source files under a
 * given root directory. Every edge is derived deterministically from the AST —
 * no heuristics, no name-based inference.
 */
public class GraphBuilder {

    private static final Set<String> JDK_TYPES = Set.of(
            "String", "Integer", "Long", "Double", "Float", "Boolean", "Byte",
            "Short", "Character", "Object", "Void", "Class",
            "List", "Set", "Map", "Collection", "Optional", "Stream",
            "ArrayList", "LinkedList", "HashSet", "LinkedHashSet", "TreeSet",
            "HashMap", "LinkedHashMap", "TreeMap", "ConcurrentHashMap",
            "Queue", "Deque", "ArrayDeque", "PriorityQueue",
            "Iterator", "Iterable", "Comparable", "Comparator",
            "Serializable", "Cloneable", "AutoCloseable",
            "Exception", "RuntimeException", "Throwable", "Error",
            "Supplier", "Consumer", "Function", "Predicate", "BiFunction",
            "CompletableFuture", "Future"
    );

    public DependencyGraph build(Path sourceRoot) {
        List<CompilationUnit> compilationUnits = parseAllJavaFiles(sourceRoot);

        // Phase 1: collect all type declarations → build node index
        // Key: simple name → FQCN (for same-package resolution)
        // Key: FQCN → GraphNode
        Map<String, GraphNode> nodesByFqcn = new LinkedHashMap<>();
        Map<String, List<String>> simpleNameToFqcns = new HashMap<>();

        for (CompilationUnit cu : compilationUnits) {
            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("");
            Path sourceFile = cu.getStorage()
                    .map(s -> s.getPath())
                    .orElse(null);

            collectNodes(cu, packageName, "", sourceFile, nodesByFqcn, simpleNameToFqcns);
        }

        // Phase 2: build edges from AST relationships
        DependencyGraph.Builder builder = DependencyGraph.builder();
        nodesByFqcn.values().forEach(builder::addNode);

        for (CompilationUnit cu : compilationUnits) {
            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("");

            // Build import map: simple name → FQCN from import declarations
            Map<String, String> importMap = buildImportMap(cu);

            collectEdges(cu, packageName, "", importMap, nodesByFqcn,
                    simpleNameToFqcns, builder);
        }

        return builder.build();
    }

    // ── Phase 1: Node collection ───────────────────────────────────────────

    private void collectNodes(CompilationUnit cu, String packageName,
                              String outerPrefix, Path sourceFile,
                              Map<String, GraphNode> nodesByFqcn,
                              Map<String, List<String>> simpleNameToFqcns) {
        for (TypeDeclaration<?> type : cu.getTypes()) {
            collectNodeRecursive(type, packageName, outerPrefix, sourceFile,
                    nodesByFqcn, simpleNameToFqcns);
        }
    }

    private void collectNodeRecursive(TypeDeclaration<?> type, String packageName,
                                      String outerPrefix, Path sourceFile,
                                      Map<String, GraphNode> nodesByFqcn,
                                      Map<String, List<String>> simpleNameToFqcns) {
        String simpleName = type.getNameAsString();
        String fqcn = packageName.isEmpty()
                ? (outerPrefix.isEmpty() ? simpleName : outerPrefix + "." + simpleName)
                : (outerPrefix.isEmpty()
                    ? packageName + "." + simpleName
                    : packageName + "." + outerPrefix + "." + simpleName);

        NodeKind kind = resolveNodeKind(type);
        Set<String> annotations = type.getAnnotations().stream()
                .map(a -> a.getNameAsString())
                .collect(Collectors.toSet());

        Set<String> implemented = Set.of();
        String superclass = null;

        if (type instanceof ClassOrInterfaceDeclaration cid) {
            if (!cid.isInterface()) {
                implemented = cid.getImplementedTypes().stream()
                        .map(ClassOrInterfaceType::getNameAsString)
                        .collect(Collectors.toSet());
                superclass = cid.getExtendedTypes().stream()
                        .findFirst()
                        .map(ClassOrInterfaceType::getNameAsString)
                        .orElse(null);
            } else {
                // Interface extending other interfaces
                implemented = cid.getExtendedTypes().stream()
                        .map(ClassOrInterfaceType::getNameAsString)
                        .collect(Collectors.toSet());
            }
        }

        // Phase 2: collect method-level metadata
        List<MethodInfo> methods = collectMethods(type);

        GraphNode node = new GraphNode(fqcn, simpleName, kind, annotations,
                implemented, superclass, packageName, sourceFile, methods);
        nodesByFqcn.put(fqcn, node);
        simpleNameToFqcns.computeIfAbsent(simpleName, k -> new ArrayList<>()).add(fqcn);

        // Recurse into nested types
        String nestedPrefix = outerPrefix.isEmpty() ? simpleName : outerPrefix + "." + simpleName;
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                collectNodeRecursive(nested, packageName, nestedPrefix, sourceFile,
                        nodesByFqcn, simpleNameToFqcns);
            }
        }
    }

    private NodeKind resolveNodeKind(TypeDeclaration<?> type) {
        if (type instanceof EnumDeclaration) return NodeKind.ENUM;
        if (type instanceof AnnotationDeclaration) return NodeKind.ANNOTATION;
        if (type instanceof RecordDeclaration) return NodeKind.RECORD;
        if (type instanceof ClassOrInterfaceDeclaration cid) {
            return cid.isInterface() ? NodeKind.INTERFACE : NodeKind.CLASS;
        }
        return NodeKind.CLASS;
    }

    // ── Method-level data collection ────────────────────────────────────────

    private List<MethodInfo> collectMethods(TypeDeclaration<?> type) {
        List<MethodInfo> methods = new ArrayList<>();
        for (MethodDeclaration method : type.getMethods()) {
            String name = method.getNameAsString();
            Set<String> methodAnnotations = method.getAnnotations().stream()
                    .map(a -> a.getNameAsString())
                    .collect(Collectors.toSet());
            String returnType = method.getType().asString();
            // Strip generics from return type
            int genIdx = returnType.indexOf('<');
            if (genIdx > 0) returnType = returnType.substring(0, genIdx);

            List<String> paramTypes = method.getParameters().stream()
                    .map(p -> {
                        String t = p.getType().asString();
                        int gi = t.indexOf('<');
                        return gi > 0 ? t.substring(0, gi) : t;
                    })
                    .toList();

            // Collect string literals from method body
            Set<String> stringLiterals = method.findAll(StringLiteralExpr.class).stream()
                    .map(StringLiteralExpr::asString)
                    .collect(Collectors.toSet());

            // Collect method call names from method body
            List<MethodCallExpr> allCalls = method.findAll(MethodCallExpr.class);
            List<String> bodyMethodCalls = allCalls.stream()
                    .map(MethodCallExpr::getNameAsString)
                    .distinct()
                    .toList();

            // Collect call-site argument literals: method call name → string args
            Map<String, Set<String>> callArgLiterals = new HashMap<>();
            for (MethodCallExpr call : allCalls) {
                String callName = call.getNameAsString();
                call.getArguments().forEach(arg -> {
                    if (arg instanceof StringLiteralExpr sle) {
                        callArgLiterals.computeIfAbsent(callName, k -> new HashSet<>())
                                .add(sle.asString());
                    }
                });
            }

            methods.add(new MethodInfo(name, methodAnnotations, returnType,
                    paramTypes, stringLiterals, bodyMethodCalls, callArgLiterals));
        }
        return methods;
    }

    // ── Phase 2: Edge collection ───────────────────────────────────────────

    private void collectEdges(CompilationUnit cu, String packageName,
                              String outerPrefix,
                              Map<String, String> importMap,
                              Map<String, GraphNode> nodesByFqcn,
                              Map<String, List<String>> simpleNameToFqcns,
                              DependencyGraph.Builder builder) {
        for (TypeDeclaration<?> type : cu.getTypes()) {
            collectEdgesRecursive(type, packageName, outerPrefix, importMap,
                    nodesByFqcn, simpleNameToFqcns, builder);
        }
    }

    private void collectEdgesRecursive(TypeDeclaration<?> type, String packageName,
                                       String outerPrefix,
                                       Map<String, String> importMap,
                                       Map<String, GraphNode> nodesByFqcn,
                                       Map<String, List<String>> simpleNameToFqcns,
                                       DependencyGraph.Builder builder) {
        String simpleName = type.getNameAsString();
        String sourceFqcn = packageName.isEmpty()
                ? (outerPrefix.isEmpty() ? simpleName : outerPrefix + "." + simpleName)
                : (outerPrefix.isEmpty()
                    ? packageName + "." + simpleName
                    : packageName + "." + outerPrefix + "." + simpleName);

        // EXTENDS / IMPLEMENTS edges
        if (type instanceof ClassOrInterfaceDeclaration cid) {
            if (!cid.isInterface()) {
                for (ClassOrInterfaceType ext : cid.getExtendedTypes()) {
                    String resolved = resolveType(ext.getNameAsString(), packageName,
                            importMap, nodesByFqcn, simpleNameToFqcns);
                    if (resolved != null) {
                        builder.addEdge(new GraphEdge(sourceFqcn, resolved,
                                EdgeKind.EXTENDS, null));
                    }
                }
                for (ClassOrInterfaceType impl : cid.getImplementedTypes()) {
                    String resolved = resolveType(impl.getNameAsString(), packageName,
                            importMap, nodesByFqcn, simpleNameToFqcns);
                    if (resolved != null) {
                        builder.addEdge(new GraphEdge(sourceFqcn, resolved,
                                EdgeKind.IMPLEMENTS, null));
                    }
                }
            } else {
                // Interface extending other interfaces
                for (ClassOrInterfaceType ext : cid.getExtendedTypes()) {
                    String resolved = resolveType(ext.getNameAsString(), packageName,
                            importMap, nodesByFqcn, simpleNameToFqcns);
                    if (resolved != null) {
                        builder.addEdge(new GraphEdge(sourceFqcn, resolved,
                                EdgeKind.EXTENDS, null));
                    }
                }
            }
        }

        // FIELD_REFERENCE edges
        for (FieldDeclaration field : type.getFields()) {
            String fieldType = extractTypeName(field);
            if (fieldType != null && !isJdkType(fieldType)) {
                String resolved = resolveType(fieldType, packageName,
                        importMap, nodesByFqcn, simpleNameToFqcns);
                if (resolved != null) {
                    String fieldName = field.getVariables().get(0).getNameAsString();
                    builder.addEdge(new GraphEdge(sourceFqcn, resolved,
                            EdgeKind.FIELD_REFERENCE, fieldName));
                }
            }
        }

        // CONSTRUCTOR_PARAM edges
        for (ConstructorDeclaration ctor : type.getConstructors()) {
            for (var param : ctor.getParameters()) {
                String paramType = extractSimpleTypeName(param.getTypeAsString());
                if (paramType != null && !isJdkType(paramType)) {
                    String resolved = resolveType(paramType, packageName,
                            importMap, nodesByFqcn, simpleNameToFqcns);
                    if (resolved != null) {
                        builder.addEdge(new GraphEdge(sourceFqcn, resolved,
                                EdgeKind.CONSTRUCTOR_PARAM, param.getNameAsString()));
                    }
                }
            }
        }

        // Recurse into nested types
        String nestedPrefix = outerPrefix.isEmpty() ? simpleName : outerPrefix + "." + simpleName;
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                collectEdgesRecursive(nested, packageName, nestedPrefix, importMap,
                        nodesByFqcn, simpleNameToFqcns, builder);
            }
        }
    }

    // ── Type resolution ────────────────────────────────────────────────────

    private String resolveType(String simpleName, String currentPackage,
                               Map<String, String> importMap,
                               Map<String, GraphNode> nodesByFqcn,
                               Map<String, List<String>> simpleNameToFqcns) {
        if (simpleName == null || isJdkType(simpleName)) return null;

        // Handle fully-qualified names
        if (simpleName.contains(".")) {
            return nodesByFqcn.containsKey(simpleName) ? simpleName : null;
        }

        // 1. Check explicit imports
        String fromImport = importMap.get(simpleName);
        if (fromImport != null && nodesByFqcn.containsKey(fromImport)) {
            return fromImport;
        }

        // 2. Check same package
        String samePackage = currentPackage.isEmpty()
                ? simpleName
                : currentPackage + "." + simpleName;
        if (nodesByFqcn.containsKey(samePackage)) {
            return samePackage;
        }

        // 3. Check all known FQCNs with this simple name (unambiguous only)
        List<String> candidates = simpleNameToFqcns.getOrDefault(simpleName, List.of());
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        return null;
    }

    private boolean isJdkType(String simpleName) {
        return JDK_TYPES.contains(simpleName);
    }

    // ── Import map ─────────────────────────────────────────────────────────

    private Map<String, String> buildImportMap(CompilationUnit cu) {
        // NOTE: Wildcard imports (import com.example.*) and static imports are not resolved.
        // For wildcard imports the fallback in resolveType() (unambiguous simple-name lookup
        // across all parsed types) covers most cases. Ambiguous simple names from wildcard
        // imports will silently produce no edge — a safe degradation, not a wrong edge.
        Map<String, String> map = new HashMap<>();
        for (ImportDeclaration imp : cu.getImports()) {
            if (!imp.isAsterisk() && !imp.isStatic()) {
                String fqcn = imp.getNameAsString();
                String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
                map.put(simple, fqcn);
            }
        }
        return map;
    }

    // ── Type name extraction ───────────────────────────────────────────────

    private String extractTypeName(FieldDeclaration field) {
        String typeStr = field.getElementType().asString();
        return extractSimpleTypeName(typeStr);
    }

    private String extractSimpleTypeName(String typeStr) {
        if (typeStr == null) return null;
        // NOTE: Generic type parameters are intentionally discarded.
        // List<Order> → List (JDK, dropped); only the outer type is tracked.
        // A field typed as List<Order> will not produce an edge to Order.
        // This keeps the graph focused on explicit composition, not type usage.
        // Tracking inner type parameters is a future enhancement.
        int genericIdx = typeStr.indexOf('<');
        if (genericIdx > 0) typeStr = typeStr.substring(0, genericIdx);
        // Strip array: Order[] → Order
        typeStr = typeStr.replace("[]", "");
        // Strip fully qualified: com.example.Order → Order
        if (typeStr.contains(".")) {
            return typeStr; // Keep FQN for resolution
        }
        // Filter primitives
        if (isPrimitive(typeStr)) return null;
        return typeStr.trim();
    }

    private boolean isPrimitive(String type) {
        return switch (type) {
            case "int", "long", "double", "float", "boolean",
                 "byte", "short", "char", "void" -> true;
            default -> false;
        };
    }

    // ── File parsing ───────────────────────────────────────────────────────

    private List<CompilationUnit> parseAllJavaFiles(Path sourceRoot) {
        List<CompilationUnit> units = new ArrayList<>();
        if (!Files.exists(sourceRoot)) return units;

        JavaParser parser = new JavaParser();
        try {
            Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")) {
                        try {
                            parser.parse(file).getResult().ifPresent(units::add);
                        } catch (IOException e) {
                            System.err.printf("[GraphBuilder] WARNING: skipping unparseable file %s: %s%n",
                                    file, e.getMessage());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to walk source tree: " + sourceRoot, e);
        }
        return units;
    }
}
