package org.fractalx.core.util;

/**
 * Null-safe string case-conversion utilities used across the generation pipeline.
 *
 * <p>Centralises the {@code charAt(0)} patterns that were previously duplicated
 * in 10+ locations with no empty/null guard.
 */
public final class NamingUtils {

    private NamingUtils() { /* utility */ }

    /**
     * Converts the first character of {@code s} to lower case (camelCase).
     *
     * <pre>
     * decapitalize("OrderService")  → "orderService"
     * decapitalize("A")             → "a"
     * decapitalize("")              → ""
     * decapitalize(null)            → null
     * </pre>
     */
    public static String decapitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Converts the first character of {@code s} to upper case (PascalCase).
     *
     * <pre>
     * capitalize("orderService")  → "OrderService"
     * capitalize("a")             → "A"
     * capitalize("")              → ""
     * capitalize(null)            → null
     * </pre>
     */
    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
