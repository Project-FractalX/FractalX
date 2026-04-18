package org.fractalx.core.config;

import org.fractalx.core.naming.NamingConventions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.yaml.snakeyaml.Yaml;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Reads platform-level FractalX configuration from the pre-decomposed
 * application's resource directory and produces a {@link FractalxConfig}.
 *
 * <p>Three sources are consulted in priority order:
 * <ol>
 *   <li>{@code fractalx-config.yml} — FractalX-specific config file (preferred)</li>
 *   <li>{@code application.yml} / {@code application.properties} — the monolith's
 *       own Spring config (fallback for values not in fractalx-config.yml)</li>
 *   <li>The monolith's {@code pom.xml} — for groupId, Spring Boot/Cloud versions</li>
 * </ol>
 *
 * <p>Any value not found in any source falls back to {@link FractalxConfig#defaults()}.
 */
public class FractalxConfigReader {

    private static final Logger log = LoggerFactory.getLogger(FractalxConfigReader.class);

    /**
     * Reads configuration from the monolith's resource directory and pom.xml.
     *
     * @param sourceResourcesDir  {@code src/main/resources} of the monolith project
     * @param sourceRoot          root directory of the monolith project (contains pom.xml)
     * @return populated {@link FractalxConfig}, never null
     */
    public FractalxConfig read(Path sourceResourcesDir, Path sourceRoot) {
        FractalxConfig defaults = FractalxConfig.defaults();
        if (!Files.isDirectory(sourceResourcesDir)) return defaults;

        // Primary: fractalx-config.yml
        Path fractalxConfigPath = sourceResourcesDir.resolve("fractalx-config.yml");
        if (!Files.exists(fractalxConfigPath)) {
            log.warn("fractalx-config.yml not found at {} — using defaults for all settings. " +
                     "Create this file to configure base-package, spring versions, ports, and more.",
                     fractalxConfigPath);
        }
        Map<String, Object> fractalxMap = loadYaml(fractalxConfigPath);
        // Fallback: application.yml for spring-standard keys
        Map<String, Object> appMap      = loadYaml(sourceResourcesDir.resolve("application.yml"));
        // Also check application.properties
        Properties appProps = loadProperties(sourceResourcesDir.resolve("application.properties"));

        // Navigate into the 'fractalx' sub-map from fractalx-config.yml
        Map<String, Object> fx = nestedMap(fractalxMap, "fractalx");

        // Parse source pom.xml for groupId and Spring versions
        SourcePomInfo pomInfo = readSourcePom(sourceRoot);

        // ── Standard fields ───────────────────────────────────────────────────

        String registryUrl  = first(
                leaf(fx, "registry", "url"),
                leaf(appMap, "fractalx", "registry", "url"),
                appProps.getProperty("fractalx.registry.url"),
                defaults.registryUrl());

        String loggerUrl    = first(
                leaf(fx, "logger", "url"),
                leaf(appMap, "fractalx", "logger", "url"),
                appProps.getProperty("fractalx.logger.url"),
                defaults.loggerUrl());

        String otelEndpoint = first(
                leaf(fx, "otel", "endpoint"),
                leaf(appMap, "fractalx", "otel", "endpoint"),
                appProps.getProperty("fractalx.otel.endpoint"),
                defaults.otelEndpoint());

        int gatewayPort     = firstInt(defaults.gatewayPort(),
                leaf(fx, "gateway", "port"),
                leaf(appMap, "fractalx", "gateway", "port"),
                appProps.getProperty("fractalx.gateway.port"));

        String corsOrigins  = first(
                leaf(fx, "gateway", "cors", "allowed-origins"),
                leaf(appMap, "fractalx", "gateway", "cors", "allowed-origins"),
                appProps.getProperty("fractalx.gateway.cors.allowed-origins"),
                defaults.corsAllowedOrigins());

        String jwksUri      = first(
                leaf(fx, "gateway", "security", "oauth2", "jwk-set-uri"),
                leaf(appMap, "fractalx", "gateway", "security", "oauth2", "jwk-set-uri"),
                appProps.getProperty("fractalx.gateway.security.oauth2.jwk-set-uri"),
                defaults.oauth2JwksUri());

        int adminPort       = firstInt(defaults.adminPort(),
                leaf(fx, "admin", "port"),
                leaf(appMap, "fractalx", "admin", "port"),
                appProps.getProperty("fractalx.admin.port"));

        // ── Generalisation fields ─────────────────────────────────────────────

        String basePackage = first(
                leaf(fx, "base-package"),
                appProps.getProperty("fractalx.base-package"),
                pomInfo.basePackage());   // may be null → effectiveBasePackage() handles it
        if (basePackage == null) {
            log.warn("base-package not configured (fractalx.base-package not in fractalx-config.yml " +
                     "and <groupId> not found in pom.xml). " +
                     "Generated infrastructure classes will use 'generated' as the package prefix. " +
                     "Set fractalx.base-package in fractalx-config.yml for correct namespacing.");
        }

        String javaVersion = first(
                leaf(fx, "java-version"),
                appProps.getProperty("fractalx.java-version"),
                pomInfo.javaVersion(),
                defaults.javaVersion());

        String initialServiceVersion = first(
                leaf(fx, "initial-service-version"),
                appProps.getProperty("fractalx.initial-service-version"),
                pomInfo.version(),
                defaults.initialServiceVersion());

        boolean springBootVersionFromConfig = first(
                leaf(fx, "spring-boot-version"),
                appProps.getProperty("fractalx.spring-boot-version"),
                pomInfo.springBootVersion()) != null;
        String springBootVersion = first(
                leaf(fx, "spring-boot-version"),
                appProps.getProperty("fractalx.spring-boot-version"),
                pomInfo.springBootVersion(),
                defaults.springBootVersion());
        if (!springBootVersionFromConfig) {
            log.warn("Spring Boot version not detected from pom.xml properties or fractalx-config.yml " +
                     "(version may be declared in a parent POM or BOM). " +
                     "Defaulting to {}. Set fractalx.spring-boot-version in fractalx-config.yml to override.",
                     defaults.springBootVersion());
        }

        String springCloudVersion = first(
                leaf(fx, "spring-cloud-version"),
                appProps.getProperty("fractalx.spring-cloud-version"),
                pomInfo.springCloudVersion(),
                defaults.springCloudVersion());

        int registryPort = firstInt(defaults.registryPort(),
                leaf(fx, "registry", "port"),
                appProps.getProperty("fractalx.registry.port"));

        int loggerPort = firstInt(defaults.loggerPort(),
                leaf(fx, "logger", "port"),
                appProps.getProperty("fractalx.logger.port"));

        int sagaPort = firstInt(defaults.sagaPort(),
                leaf(fx, "saga", "port"),
                appProps.getProperty("fractalx.saga.port"));

        Map<String, Object> otelMap = nestedMap(fx, "otel");
        int jaegerUiPort = firstInt(defaults.jaegerUiPort(),
                leaf(otelMap, "jaeger-ui-port"),
                appProps.getProperty("fractalx.otel.jaeger-ui-port"));
        int jaegerOtlpPort = firstInt(defaults.jaegerOtlpPort(),
                leaf(otelMap, "jaeger-otlp-port"),
                appProps.getProperty("fractalx.otel.jaeger-otlp-port"));

        // ── ResilienceDefaults ────────────────────────────────────────────────

        Map<String, Object> resMap = nestedMap(fx, "resilience");
        FractalxConfig.ResilienceDefaults rd = FractalxConfig.ResilienceDefaults.defaults();
        FractalxConfig.ResilienceDefaults resilience = new FractalxConfig.ResilienceDefaults(
                firstInt(rd.failureRateThreshold(),
                        leaf(resMap, "failure-rate-threshold"),
                        appProps.getProperty("fractalx.resilience.failure-rate-threshold")),
                first(
                        leaf(resMap, "wait-duration-in-open-state"),
                        appProps.getProperty("fractalx.resilience.wait-duration-in-open-state"),
                        rd.waitDurationInOpenState()),
                firstInt(rd.permittedCallsInHalfOpenState(),
                        leaf(resMap, "permitted-calls-in-half-open-state"),
                        appProps.getProperty("fractalx.resilience.permitted-calls-in-half-open-state")),
                firstInt(rd.slidingWindowSize(),
                        leaf(resMap, "sliding-window-size"),
                        appProps.getProperty("fractalx.resilience.sliding-window-size")),
                firstInt(rd.retryMaxAttempts(),
                        leaf(resMap, "retry-max-attempts"),
                        appProps.getProperty("fractalx.resilience.retry-max-attempts")),
                first(
                        leaf(resMap, "retry-wait-duration"),
                        appProps.getProperty("fractalx.resilience.retry-wait-duration"),
                        rd.retryWaitDuration()),
                first(
                        leaf(resMap, "timeout-duration"),
                        appProps.getProperty("fractalx.resilience.timeout-duration"),
                        rd.timeoutDuration())
        );

        // ── DockerImages ──────────────────────────────────────────────────────

        Map<String, Object> dockerMap = nestedMap(fx, "docker");
        FractalxConfig.DockerImages di = FractalxConfig.DockerImages.defaults(javaVersion);
        FractalxConfig.DockerImages dockerImages = new FractalxConfig.DockerImages(
                first(
                        leaf(dockerMap, "maven-build-image"),
                        appProps.getProperty("fractalx.docker.maven-build-image"),
                        di.mavenBuildImage()),
                first(
                        leaf(dockerMap, "jre-runtime-image"),
                        appProps.getProperty("fractalx.docker.jre-runtime-image"),
                        di.jreRuntimeImage()),
                first(
                        leaf(dockerMap, "jaeger-image"),
                        appProps.getProperty("fractalx.docker.jaeger-image"),
                        di.jaegerImage())
        );

        // ── Per-service overrides ─────────────────────────────────────────────

        Map<String, FractalxConfig.ServiceOverride> overrides = readServiceOverrides(fx);

        // ── Feature flags ─────────────────────────────────────────────────────

        Map<String, Object> featMap = nestedMap(fx, "features");
        FractalxConfig.FeatureFlags features = new FractalxConfig.FeatureFlags(
                readBool(featMap, "gateway",          true),
                readBool(featMap, "admin",            true),
                readBool(featMap, "registry",         true),
                readBool(featMap, "logger",           true),
                readBool(featMap, "saga",             true),
                readBool(featMap, "docker",           true),
                readBool(featMap, "observability",    true),
                readBool(featMap, "resilience",       true),
                readBool(featMap, "distributed-data", true)
        );
        if (!features.gateway())         log.info("[FractalxConfig] Feature DISABLED: gateway");
        if (!features.admin())           log.info("[FractalxConfig] Feature DISABLED: admin");
        if (!features.registry())        log.info("[FractalxConfig] Feature DISABLED: registry");
        if (!features.logger())          log.info("[FractalxConfig] Feature DISABLED: logger");
        if (!features.saga())            log.info("[FractalxConfig] Feature DISABLED: saga");
        if (!features.docker())          log.info("[FractalxConfig] Feature DISABLED: docker");
        if (!features.observability())   log.info("[FractalxConfig] Feature DISABLED: observability");
        if (!features.resilience())      log.info("[FractalxConfig] Feature DISABLED: resilience");
        if (!features.distributedData()) log.info("[FractalxConfig] Feature DISABLED: distributed-data");

        // ── NamingConventions ─────────────────────────────────────────────────

        NamingConventions naming = readNamingConventions(nestedMap(fx, "naming"));

        FractalxConfig cfg = new FractalxConfig(
                registryUrl, loggerUrl, otelEndpoint,
                gatewayPort, corsOrigins, jwksUri,
                adminPort, overrides,
                basePackage, javaVersion, initialServiceVersion,
                springBootVersion, springCloudVersion,
                registryPort, loggerPort, sagaPort, jaegerUiPort, jaegerOtlpPort,
                resilience, dockerImages, features, naming);

        log.info("[FractalxConfig] basePackage={} java={} springBoot={} registry-port={} gateway-port={} admin-port={}",
                cfg.effectiveBasePackage(), javaVersion, springBootVersion, registryPort, gatewayPort, adminPort);
        return cfg;
    }

    // ── Source pom.xml parsing ────────────────────────────────────────────────

    /**
     * Parses the monolith's {@code pom.xml} to extract groupId and Spring versions.
     * All fields may be null if the pom cannot be parsed or values are not present.
     */
    SourcePomInfo readSourcePom(Path sourceRoot) {
        if (sourceRoot == null) return SourcePomInfo.empty();
        Path pomPath = sourceRoot.resolve("pom.xml");
        if (!Files.exists(pomPath)) return SourcePomInfo.empty();
        try (InputStream is = Files.newInputStream(pomPath)) {
            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(is);
            doc.getDocumentElement().normalize();

            String groupId            = firstElement(doc, "groupId");
            String version            = firstElement(doc, "version");
            String javaVersion        = first(
                    pomProperty(doc, "java.version"),
                    pomProperty(doc, "maven.compiler.source"));
            String springBootVersion  = pomProperty(doc, "spring-boot.version");
            if (springBootVersion == null) {
                springBootVersion = parentVersionIfStarter(doc);
            }
            String springCloudVersion = dependencyVersion(doc, "spring-cloud-dependencies");

            return new SourcePomInfo(groupId, version, javaVersion, springBootVersion, springCloudVersion);
        } catch (Exception e) {
            log.warn("Could not parse source pom.xml at {} — groupId and Spring versions " +
                     "will fall back to defaults: {}", pomPath, e.getMessage());
            return SourcePomInfo.empty();
        }
    }

    /** Returns the text content of the first direct child element with the given tag. */
    private String firstElement(Document doc, String tag) {
        NodeList nodes = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            org.w3c.dom.Node n = nodes.item(i);
            if (n.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
                    && n.getNodeName().equals(tag)) {
                return n.getTextContent().trim();
            }
        }
        return null;
    }

    /** Returns the value of a {@code <properties>/<key>} element. */
    private String pomProperty(Document doc, String key) {
        NodeList props = doc.getElementsByTagName("properties");
        if (props.getLength() == 0) return null;
        NodeList children = props.item(0).getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node n = children.item(i);
            if (n.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
                    && n.getNodeName().equals(key)) {
                return n.getTextContent().trim();
            }
        }
        return null;
    }

    /**
     * Returns the version from {@code <parent>} when the parent artifact is
     * {@code spring-boot-starter-parent}, as an alternative to reading
     * {@code <properties><spring-boot.version>}.
     */
    private String parentVersionIfStarter(Document doc) {
        NodeList parents = doc.getElementsByTagName("parent");
        if (parents.getLength() == 0) return null;
        org.w3c.dom.Node parentNode = parents.item(0);
        if (parentNode.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) return null;
        String aid = null, ver = null;
        NodeList children = parentNode.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node c = children.item(i);
            if (c.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
            if ("artifactId".equals(c.getNodeName())) aid = c.getTextContent().trim();
            if ("version".equals(c.getNodeName()))    ver = c.getTextContent().trim();
        }
        return "spring-boot-starter-parent".equals(aid) ? ver : null;
    }

    /** Returns the version declared for the named artifact in dependencyManagement. */
    private String dependencyVersion(Document doc, String artifactId) {
        NodeList deps = doc.getElementsByTagName("dependency");
        for (int i = 0; i < deps.getLength(); i++) {
            org.w3c.dom.Node dep = deps.item(i);
            if (dep.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
            String aid = null, ver = null;
            NodeList children = dep.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                org.w3c.dom.Node c = children.item(j);
                if (c.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
                if ("artifactId".equals(c.getNodeName())) aid = c.getTextContent().trim();
                if ("version".equals(c.getNodeName()))    ver = c.getTextContent().trim();
            }
            if (artifactId.equals(aid) && ver != null) return ver;
        }
        return null;
    }

    /** Holds values extracted from the monolith's pom.xml. All fields may be null. */
    record SourcePomInfo(String groupId, String version, String javaVersion,
                         String springBootVersion, String springCloudVersion) {
        static SourcePomInfo empty() { return new SourcePomInfo(null, null, null, null, null); }

        /** Returns {@code groupId + ".generated"}, or null when groupId is unknown. */
        String basePackage() {
            if (groupId == null || groupId.isBlank()) return null;
            return groupId + ".generated";
        }
    }

    // ── NamingConventions parsing ─────────────────────────────────────────────

    /**
     * Reads the {@code fractalx.naming} sub-map and constructs a {@link NamingConventions}.
     * Any omitted key falls back to the value from {@link NamingConventions#defaults()}.
     */
    @SuppressWarnings("unchecked")
    private NamingConventions readNamingConventions(Map<String, Object> namingMap) {
        NamingConventions d = NamingConventions.defaults();

        List<String> compensationPrefixes    = readStringList(namingMap, "compensation-prefixes",    d.compensationPrefixes());
        List<String> infrastructureSuffixes  = readStringList(namingMap, "infrastructure-suffixes",  d.infrastructureSuffixes());
        List<String> dependencyTypeSuffixes  = readStringList(namingMap, "dependency-type-suffixes", d.dependencyTypeSuffixes());
        List<String> aggregateClassSuffixes  = readStringList(namingMap, "aggregate-class-suffixes", d.aggregateClassSuffixes());
        List<String> eventPublisherMethods   = readStringList(namingMap, "event-publisher-method-names", d.eventPublisherMethodNames());
        boolean caseInsensitive              = readBool(namingMap, "case-insensitive-service-names", d.caseInsensitiveServiceNames());

        // irregular-plurals: YAML map of plural → singular entries
        Map<String, String> irregularPlurals = new HashMap<>(d.irregularPlurals());
        Object rawPlurals = namingMap.isEmpty() ? null : namingMap.get("irregular-plurals");
        if (rawPlurals instanceof Map<?, ?> pm) {
            pm.forEach((k, v) -> {
                if (k != null && v != null) {
                    irregularPlurals.put(k.toString().toLowerCase(), v.toString().toLowerCase());
                }
            });
        }

        return new NamingConventions(
                compensationPrefixes,
                infrastructureSuffixes,
                dependencyTypeSuffixes,
                aggregateClassSuffixes,
                Map.copyOf(irregularPlurals),
                eventPublisherMethods,
                caseInsensitive
        );
    }

    /**
     * Reads a YAML list at {@code key} from {@code map}.
     * Accepts a {@code List<?>} value or a single {@code String} (comma-separated).
     * Returns {@code defaultVal} if the key is absent.
     */
    @SuppressWarnings("unchecked")
    private List<String> readStringList(Map<String, Object> map, String key, List<String> defaultVal) {
        if (map == null || map.isEmpty()) return defaultVal;
        Object val = map.get(key);
        if (val instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            list.forEach(item -> { if (item != null) result.add(item.toString()); });
            return List.copyOf(result);
        }
        if (val instanceof String s && !s.isBlank()) {
            List<String> result = new ArrayList<>();
            for (String part : s.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) result.add(trimmed);
            }
            return List.copyOf(result);
        }
        return defaultVal;
    }

    // ── Per-service port overrides ────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, FractalxConfig.ServiceOverride> readServiceOverrides(Map<String, Object> fx) {
        Map<String, FractalxConfig.ServiceOverride> result = new HashMap<>();
        Object services = fx.get("services");
        if (!(services instanceof Map)) return result;
        Map<String, Object> svcMap = (Map<String, Object>) services;
        svcMap.forEach((name, val) -> {
            if (!(val instanceof Map)) return;
            Map<?, ?> svc = (Map<?, ?>) val;
            int port = 0;
            Object p = svc.get("port");
            if (p instanceof Number n) port = n.intValue();
            boolean tracingEnabled = true;
            Object tracingObj = svc.get("tracing");
            if (tracingObj instanceof Map<?, ?> tracingMap) {
                Object enabledVal = tracingMap.get("enabled");
                if (enabledVal instanceof Boolean b) tracingEnabled = b;
                else if ("false".equalsIgnoreCase(String.valueOf(enabledVal))) tracingEnabled = false;
            }
            String dsUrl = null, dsUsername = null, dsPassword = null, dsDriver = null;
            Object dsObj = svc.get("datasource");
            if (dsObj instanceof Map<?, ?> dsMap) {
                Object dsU = dsMap.get("url");      if (dsU != null) dsUrl      = dsU.toString();
                Object dsN = dsMap.get("username"); if (dsN != null) dsUsername = dsN.toString();
                Object dsP = dsMap.get("password"); if (dsP != null) dsPassword = dsP.toString();
                Object dsD = dsMap.get("driver-class-name"); if (dsD != null) dsDriver = dsD.toString();
            }
            Boolean flywayEnabled = null;   // null = inherit project-level distributed-data default
            Object flywayObj = svc.get("flyway");
            if (flywayObj instanceof Map<?, ?> flywayMap) {
                Object enabledVal = flywayMap.get("enabled");
                if (enabledVal instanceof Boolean b) flywayEnabled = b;
                else if (enabledVal != null) flywayEnabled = !"false".equalsIgnoreCase(String.valueOf(enabledVal));
            }
            result.put(name, new FractalxConfig.ServiceOverride(port, tracingEnabled,
                    dsUrl, dsUsername, dsPassword, dsDriver, flywayEnabled));
        });
        return result;
    }

    // ── YAML / Properties loading ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYaml(Path path) {
        if (!Files.exists(path)) return Map.of();
        try (InputStream is = Files.newInputStream(path)) {
            Object loaded = new Yaml().load(is);
            if (loaded instanceof Map<?, ?> m) return (Map<String, Object>) m;
        } catch (Exception e) {
            log.debug("Could not parse {}: {}", path.getFileName(), e.getMessage());
        }
        return Map.of();
    }

    private Properties loadProperties(Path path) {
        Properties p = new Properties();
        if (!Files.exists(path)) return p;
        try (InputStream is = Files.newInputStream(path)) {
            p.load(is);
        } catch (IOException e) {
            log.debug("Could not read {}: {}", path.getFileName(), e.getMessage());
        }
        return p;
    }

    // ── Navigation helpers ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedMap(Map<String, Object> map, String key) {
        if (map == null || map.isEmpty()) return Map.of();
        Object val = map.get(key);
        if (val instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private String leaf(Map<String, Object> map, String... keys) {
        if (map == null || map.isEmpty()) return null;
        Map<String, Object> cur = map;
        for (int i = 0; i < keys.length - 1; i++) {
            Object next = cur.get(keys[i]);
            if (!(next instanceof Map)) return null;
            cur = (Map<String, Object>) next;
        }
        Object val = cur.get(keys[keys.length - 1]);
        return val != null ? val.toString() : null;
    }

    private String first(String... candidates) {
        for (String c : candidates) if (c != null && !c.isBlank()) return c;
        return null;
    }

    private int firstInt(int defaultVal, String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                try { return Integer.parseInt(c.trim()); }
                catch (NumberFormatException ignored) {}
            }
        }
        return defaultVal;
    }

    /**
     * Reads a boolean value from a YAML map by key.
     * Accepts {@code Boolean} objects and {@code "true"}/{@code "false"} strings.
     * Returns {@code defaultVal} when the key is absent.
     */
    private boolean readBool(Map<String, Object> map, String key, boolean defaultVal) {
        if (map == null || map.isEmpty()) return defaultVal;
        Object val = map.get(key);
        if (val instanceof Boolean b) return b;
        if (val != null) return !"false".equalsIgnoreCase(val.toString().trim());
        return defaultVal;
    }
}
