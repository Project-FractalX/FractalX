package org.fractalx.core.graph;

public record GraphEdge(
        String sourceNode,
        String targetNode,
        EdgeKind kind,
        String detail
) {}
