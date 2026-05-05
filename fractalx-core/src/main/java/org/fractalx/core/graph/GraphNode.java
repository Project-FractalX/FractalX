package org.fractalx.core.graph;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public record GraphNode(
        String fqcn,
        String simpleName,
        NodeKind kind,
        Set<String> annotations,
        Set<String> implementedInterfaces,
        String superclass,
        String packageName,
        Path sourceFile,
        List<MethodInfo> methods
) {
    /** Full constructor with method-level data. */
    public GraphNode {
        annotations = Set.copyOf(annotations);
        implementedInterfaces = Set.copyOf(implementedInterfaces);
        methods = List.copyOf(methods);
    }

    /** Backward-compatible constructor without method info. */
    public GraphNode(String fqcn, String simpleName, NodeKind kind,
                     Set<String> annotations, Set<String> implementedInterfaces,
                     String superclass, String packageName, Path sourceFile) {
        this(fqcn, simpleName, kind, annotations, implementedInterfaces,
                superclass, packageName, sourceFile, List.of());
    }
}
