package org.fractalx.core.graph;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Structural queries on a {@link DependencyGraph} — derives component roles
 * from graph position, not from names or annotations.
 *
 * <p>These queries bridge the deterministic graph to downstream consumers
 * (generators, analyzers) that need to classify components.
 *
 * <p><b>Phase 2 note (migration plan):</b> The annotation and supertype sets below are
 * a transitional bridge. They identify Spring-framework structural markers by their
 * well-known names — not by domain-specific heuristics — but they still embed framework
 * knowledge that ideally would be derived from graph topology alone (e.g., "nodes with
 * no inbound edges that carry outbound ANNOTATION edges to HTTP-mapping types"). Once the
 * graph encodes meta-annotation inheritance and framework type hierarchies, these sets
 * should be removed and replaced with pure graph queries. See issue #99.
 */
public final class GraphBridge {

    // Spring Web HTTP-mapping annotations — structural markers for inbound request handlers.
    // These are framework contracts, not domain names: any class carrying one of these
    // annotations is structurally an HTTP entry point regardless of its class name.
    private static final Set<String> ENTRY_POINT_ANNOTATIONS = Set.of(
            "RestController", "Controller", "RequestMapping",
            "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping"
    );

    // Spring Data repository supertypes — structural markers for data-store bridges.
    private static final Set<String> REPOSITORY_SUPERTYPES = Set.of(
            "JpaRepository", "CrudRepository", "PagingAndSortingRepository",
            "ListCrudRepository", "ReactiveCrudRepository", "MongoRepository",
            "R2dbcRepository", "ElasticsearchRepository"
    );

    // Servlet/reactive filter and interceptor supertypes — structural markers for
    // classes that sit in the inbound request pipeline before handlers are reached.
    private static final Set<String> FILTER_SUPERTYPES = Set.of(
            "OncePerRequestFilter", "GenericFilterBean", "Filter",
            "HandlerInterceptor", "HandlerInterceptorAdapter",
            "WebFilter", "GatewayFilter", "AbstractGatewayFilterFactory"
    );

    private GraphBridge() {}

    /**
     * Entry points: nodes with HTTP mapping annotations.
     * Structural meaning: classes that accept inbound requests.
     */
    public static List<GraphNode> entryPoints(DependencyGraph graph) {
        return graph.nodesMatching(node ->
                node.annotations().stream().anyMatch(ENTRY_POINT_ANNOTATIONS::contains));
    }

    /**
     * Data accessors: interfaces that extend known repository supertypes.
     * Structural meaning: components that bridge application code to data stores.
     */
    public static List<GraphNode> dataAccessors(DependencyGraph graph) {
        return graph.nodesMatching(node ->
                node.kind() == NodeKind.INTERFACE &&
                node.implementedInterfaces().stream().anyMatch(REPOSITORY_SUPERTYPES::contains));
    }

    /**
     * Pipeline classes: classes that extend filter/interceptor supertypes.
     * Structural meaning: components that sit in the request pipeline and
     * intercept/gate all inbound requests before they reach handlers.
     */
    public static List<GraphNode> pipelineClasses(DependencyGraph graph) {
        return graph.nodesMatching(node ->
                node.superclass() != null && FILTER_SUPERTYPES.contains(node.superclass()));
    }

    /**
     * Cross-package edges: edges where source and target are in different packages.
     * Structural meaning: dependencies that cross potential service boundaries.
     */
    public static List<GraphEdge> crossPackageEdges(DependencyGraph graph) {
        return graph.allEdges().stream()
                .filter(edge -> {
                    var source = graph.node(edge.sourceNode());
                    var target = graph.node(edge.targetNode());
                    return source.isPresent() && target.isPresent()
                            && !source.get().packageName().equals(target.get().packageName());
                })
                .collect(Collectors.toList());
    }
}
