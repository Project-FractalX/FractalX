package org.fractalx.core.util;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import java.util.Set;

/**
 * Utility constants and helpers for Spring Data repository detection,
 * replacing hardcoded string-matching heuristics throughout the codebase.
 */
public final class SpringDataUtils {

    private SpringDataUtils() { /* utility */ }

    /**
     * All Spring Data derived query method prefixes.
     * See <a href="https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html">Spring Data docs</a>.
     */
    public static final Set<String> QUERY_PREFIXES = Set.of(
            "find", "get", "query", "search", "read", "stream",
            "count", "exists", "delete", "remove", "fetch", "load",
            "retrieve"
    );

    /**
     * Known Spring Data repository base interface simple names.
     */
    public static final Set<String> REPOSITORY_INTERFACES = Set.of(
            "Repository", "CrudRepository", "JpaRepository",
            "PagingAndSortingRepository", "ReactiveCrudRepository",
            "ListCrudRepository", "ListPagingAndSortingRepository",
            "R2dbcRepository", "MongoRepository", "ReactiveMongoRepository",
            "ReactiveSortingRepository"
    );

    /**
     * Returns {@code true} if the given class/interface declaration extends
     * a known Spring Data repository interface.
     */
    public static boolean isRepositoryInterface(ClassOrInterfaceDeclaration decl) {
        return decl.getExtendedTypes().stream()
                .map(t -> t.getNameAsString())
                .anyMatch(REPOSITORY_INTERFACES::contains);
    }

    /**
     * Returns {@code true} if the method name starts with any known
     * Spring Data derived query method prefix.
     */
    public static boolean isRepositoryQueryMethod(String methodName) {
        if (methodName == null || methodName.isEmpty()) return false;
        String lower = methodName.toLowerCase();
        return QUERY_PREFIXES.stream().anyMatch(lower::startsWith);
    }

    /**
     * Returns {@code true} if the lowercased initializer expression string
     * looks like a repository fetch operation.
     *
     * <p>Detects patterns like {@code findById(...)}, {@code getBy...(...)},
     * {@code .orElseThrow()}, {@code .orElse(...)}, and any method call
     * starting with a known query prefix followed by {@code "by"}.
     */
    public static boolean isRepositoryFetchExpression(String initExprStr) {
        if (initExprStr == null || initExprStr.isEmpty()) return false;
        String lower = initExprStr.toLowerCase();

        // Check for Optional unwrapping — strong signal of a repository fetch
        if (lower.contains("orelsethrow") || lower.contains("orelse(")) {
            return true;
        }

        // Check for any query prefix followed by "by" or used standalone
        for (String prefix : QUERY_PREFIXES) {
            if (lower.contains(prefix + "by") || lower.contains(prefix + "all")
                    || lower.contains(prefix + "one")) {
                return true;
            }
        }

        return false;
    }
}
