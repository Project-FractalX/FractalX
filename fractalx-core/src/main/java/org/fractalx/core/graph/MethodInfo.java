package org.fractalx.core.graph;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Method-level metadata captured from the AST. Enables structural queries
 * on method annotations, return types, string literals, and intra-method
 * call expressions — eliminating heuristics that previously re-walked the AST.
 */
public record MethodInfo(
        String name,
        Set<String> annotations,
        String returnType,
        List<String> parameterTypes,
        Set<String> stringLiterals,
        List<String> bodyMethodCalls,
        /** Maps method call name → set of string literal arguments passed to it.
         *  e.g. {@code .claim("customerId", ...)} produces {@code {"claim": {"customerId"}}} */
        Map<String, Set<String>> callArgumentLiterals
) {
    /** Full constructor with call-site argument data. */
    public MethodInfo {
        annotations = Set.copyOf(annotations);
        parameterTypes = List.copyOf(parameterTypes);
        stringLiterals = Set.copyOf(stringLiterals);
        bodyMethodCalls = List.copyOf(bodyMethodCalls);
        callArgumentLiterals = Map.copyOf(callArgumentLiterals);
    }

    /** Backward-compatible constructor without call-site argument data. */
    public MethodInfo(String name, Set<String> annotations, String returnType,
                      List<String> parameterTypes, Set<String> stringLiterals,
                      List<String> bodyMethodCalls) {
        this(name, annotations, returnType, parameterTypes, stringLiterals,
                bodyMethodCalls, Map.of());
    }
}
