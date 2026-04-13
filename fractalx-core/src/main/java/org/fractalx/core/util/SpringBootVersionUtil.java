package org.fractalx.core.util;

/**
 * Utility methods for Spring Boot version detection during code generation.
 */
public final class SpringBootVersionUtil {

    private SpringBootVersionUtil() {}

    /**
     * Returns {@code true} when the given Spring Boot version string indicates
     * Boot 4.x or higher.  Handles {@code null} and blank strings safely.
     */
    public static boolean isBoot4Plus(String version) {
        if (version == null || version.isBlank()) return false;
        try {
            return Integer.parseInt(version.split("\\.")[0]) >= 4;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
