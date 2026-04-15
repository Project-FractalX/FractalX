package org.fractalx.core.verifier;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Scans generated {@code @Entity} classes in a service directory and extracts
 * field metadata — name, type, and whether the field is the {@code @Id} primary key.
 *
 * <p>This information is used by the smoke-test CRUD lifecycle phase to construct
 * realistic JSON payloads for POST/PUT requests. For example, a field of type
 * {@code String} gets the value {@code "test"}, {@code Long} gets {@code 1}, etc.
 *
 * <p>Fields annotated with {@code @Id} and {@code @GeneratedValue} are excluded
 * from POST payloads (the database generates the ID), but the ID field name is
 * tracked so that the response can be inspected for the generated value.
 */
public class EntityScanner {

    private static final Logger log = LoggerFactory.getLogger(EntityScanner.class);

    // ── Result models ──────────────────────────────────────────────────────────

    /**
     * Describes a single field in a JPA entity.
     *
     * @param name          Java field name (e.g. {@code "orderDate"})
     * @param type          Simple type name (e.g. {@code "String"}, {@code "Long"})
     * @param isId          {@code true} if annotated with {@code @Id}
     * @param isGenerated   {@code true} if annotated with {@code @GeneratedValue}
     */
    public record FieldSpec(String name, String type, boolean isId, boolean isGenerated) {}

    /**
     * Describes a JPA entity class and the REST base path it is likely served at.
     *
     * @param className     Simple class name (e.g. {@code "Order"})
     * @param basePath      Inferred REST path (e.g. {@code "/api/orders"})
     * @param idFieldName   Name of the {@code @Id} field (e.g. {@code "id"})
     * @param idFieldType   Type of the {@code @Id} field (e.g. {@code "Long"})
     * @param fields        All non-static fields including the ID field
     */
    public record EntitySpec(
            String className,
            String basePath,
            String idFieldName,
            String idFieldType,
            List<FieldSpec> fields
    ) {}

    // ── Default test values per type ───────────────────────────────────────────

    private static final Map<String, String> DEFAULT_VALUES = Map.ofEntries(
            Map.entry("String",       "\"test\""),
            Map.entry("string",       "\"test\""),
            Map.entry("Long",         "1"),
            Map.entry("long",         "1"),
            Map.entry("Integer",      "1"),
            Map.entry("int",          "1"),
            Map.entry("Double",       "1.0"),
            Map.entry("double",       "1.0"),
            Map.entry("Float",        "1.0"),
            Map.entry("float",        "1.0"),
            Map.entry("Boolean",      "true"),
            Map.entry("boolean",      "true"),
            Map.entry("BigDecimal",   "1.0"),
            Map.entry("BigInteger",   "1"),
            Map.entry("LocalDate",    "\"2025-01-01\""),
            Map.entry("LocalDateTime","\"2025-01-01T00:00:00\""),
            Map.entry("Instant",      "\"2025-01-01T00:00:00Z\""),
            Map.entry("UUID",         "\"00000000-0000-0000-0000-000000000001\"")
    );

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Scans all Java files under {@code <serviceDir>/src/main/java} for
     * {@code @Entity}-annotated classes and extracts their field metadata.
     *
     * @param serviceDir root directory of a generated service
     * @return list of entity specs; empty if none found
     */
    public List<EntitySpec> scan(Path serviceDir) {
        Path srcJava = serviceDir.resolve("src/main/java");
        if (!Files.isDirectory(srcJava)) return List.of();

        List<EntitySpec> specs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(srcJava)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(file -> scanFile(file, specs));
        } catch (IOException e) {
            log.debug("Could not walk {}: {}", srcJava, e.getMessage());
        }
        return List.copyOf(specs);
    }

    /**
     * Returns the default JSON value for a given field type, or {@code null}
     * if the type is not a known primitive/wrapper/temporal type.
     */
    public static String defaultJsonValue(String typeName) {
        return DEFAULT_VALUES.get(typeName);
    }

    /**
     * Builds a JSON object string containing default values for all writable
     * fields (non-Id or non-generated fields) in the given entity.
     */
    public static String buildCreatePayload(EntitySpec entity) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (FieldSpec f : entity.fields()) {
            if (f.isId() && f.isGenerated()) continue;
            String val = defaultJsonValue(f.type());
            if (val == null) continue; // skip complex/unknown types
            if (!first) sb.append(",");
            sb.append("\"").append(f.name()).append("\":").append(val);
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Builds a JSON object for a PUT/update request — same as create but with
     * slightly different string values to verify the update took effect.
     */
    public static String buildUpdatePayload(EntitySpec entity) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (FieldSpec f : entity.fields()) {
            if (f.isId() && f.isGenerated()) continue;
            String val = defaultUpdateValue(f.type());
            if (val == null) continue;
            if (!first) sb.append(",");
            sb.append("\"").append(f.name()).append("\":").append(val);
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    // ── Per-file scanning ─────────────────────────────────────────────────────

    private void scanFile(Path file, List<EntitySpec> out) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);
            cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(c -> hasAnnotation(c, "Entity"))
                    .forEach(cls -> scanEntity(cls, out));
        } catch (Exception e) {
            log.debug("Could not parse {}: {}", file, e.getMessage());
        }
    }

    private void scanEntity(ClassOrInterfaceDeclaration cls, List<EntitySpec> out) {
        String className = cls.getNameAsString();
        List<FieldSpec> fields = new ArrayList<>();
        String idFieldName = "id";
        String idFieldType = "Long";

        for (FieldDeclaration fd : cls.getFields()) {
            if (fd.isStatic()) continue;

            boolean isId = fd.getAnnotations().stream()
                    .anyMatch(a -> a.getNameAsString().equals("Id"));
            boolean isGenerated = fd.getAnnotations().stream()
                    .anyMatch(a -> a.getNameAsString().equals("GeneratedValue"));

            for (VariableDeclarator var : fd.getVariables()) {
                String name = var.getNameAsString();
                String type = var.getType().asString();

                // Strip generics: List<String> → List
                if (type.contains("<")) {
                    type = type.substring(0, type.indexOf('<'));
                }

                fields.add(new FieldSpec(name, type, isId, isGenerated));

                if (isId) {
                    idFieldName = name;
                    idFieldType = type;
                }
            }
        }

        String basePath = inferBasePath(className);
        out.add(new EntitySpec(className, basePath, idFieldName, idFieldType, List.copyOf(fields)));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Infers the REST base path from an entity class name.
     * {@code "Order"} → {@code "/api/orders"}, {@code "Category"} → {@code "/api/categories"}.
     */
    static String inferBasePath(String className) {
        String lower = className.substring(0, 1).toLowerCase() + className.substring(1);
        return "/api/" + pluralize(lower);
    }

    /** Simple English pluralization — covers most common entity names. */
    static String pluralize(String word) {
        if (word.endsWith("y") && word.length() > 1
                && !isVowel(word.charAt(word.length() - 2))) {
            return word.substring(0, word.length() - 1) + "ies";
        }
        if (word.endsWith("s") || word.endsWith("x") || word.endsWith("z")
                || word.endsWith("ch") || word.endsWith("sh")) {
            return word + "es";
        }
        return word + "s";
    }

    private static boolean isVowel(char c) {
        return "aeiouAEIOU".indexOf(c) >= 0;
    }

    private static boolean hasAnnotation(ClassOrInterfaceDeclaration cls, String name) {
        return cls.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals(name));
    }

    private static String defaultUpdateValue(String typeName) {
        return switch (typeName) {
            case "String", "string"       -> "\"updated\"";
            case "Long", "long"           -> "2";
            case "Integer", "int"         -> "2";
            case "Double", "double"       -> "2.0";
            case "Float", "float"         -> "2.0";
            case "Boolean", "boolean"     -> "false";
            case "BigDecimal"             -> "2.0";
            case "BigInteger"             -> "2";
            case "LocalDate"              -> "\"2025-06-15\"";
            case "LocalDateTime"          -> "\"2025-06-15T12:00:00\"";
            case "Instant"                -> "\"2025-06-15T12:00:00Z\"";
            case "UUID"                   -> "\"00000000-0000-0000-0000-000000000002\"";
            default                       -> null;
        };
    }
}
