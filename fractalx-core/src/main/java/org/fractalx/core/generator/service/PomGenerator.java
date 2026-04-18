package org.fractalx.core.generator.service;

import org.fractalx.core.FractalxVersion;
import org.fractalx.core.config.FractalxConfig;
import org.fractalx.core.generator.GenerationContext;
import org.fractalx.core.generator.ServiceFileGenerator;
import org.fractalx.core.model.FractalModule;
import org.fractalx.core.observability.ObservabilityInjector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fractalx.core.util.SpringBootVersionUtil;

/**
 * Generates pom.xml for a microservice by cloning the monolith pom and applying
 * targeted surgical mutations:
 * <ol>
 *   <li>Strip build-time-only FractalX artifacts ({@code fractalx-annotations}, {@code fractalx-core}).</li>
 *   <li>Prune dependencies whose code was not copied into this service.</li>
 *   <li>Add FractalX runtime libraries (NetScope, fractalx-runtime, resilience4j, etc.) idempotently.</li>
 *   <li>Ensure Spring Cloud BOM in {@code <dependencyManagement>} if not already present.</li>
 * </ol>
 * Everything else — {@code <parent>}, {@code <properties>}, {@code <build><plugins>},
 * existing dep versions — is preserved verbatim from the monolith.
 *
 * <p>Falls back to a hardcoded template when the monolith pom cannot be found or parsed.
 */
public class PomGenerator implements ServiceFileGenerator {

    private static final Logger log = LoggerFactory.getLogger(PomGenerator.class);

    private static final String FRACTALX_RUNTIME_VERSION = FractalxVersion.get();
    private static final String NETSCOPE_VERSION         = "1.0.1";
    private static final String RESILIENCE4J_VERSION     = "2.1.0";

    /**
     * ArtifactId fragments of deps we can safely drop when the service's source
     * files contain no matching imports.  Everything NOT in this list is kept
     * by default — we only prune what we are confident about.
     */
    private static final List<PruneRule> PRUNE_RULES = List.of(
            new PruneRule("data-jpa",   "jakarta.persistence", "org.springframework.data.jpa", "org.springframework.data.repository"),
            new PruneRule("hibernate",  "jakarta.persistence", "org.hibernate"),
            new PruneRule("flyway",     "jakarta.persistence", "org.flywaydb", "org.springframework.data.jpa"),
            // DB drivers are runtime-only — Spring JPA code never imports com.mysql.* or org.postgresql.*
            // directly. Keep the driver whenever JPA/persistence imports are present, OR when the
            // rare driver-level import itself is found (e.g. DataSource configuration).
            new PruneRule("mysql",      "com.mysql", "jakarta.persistence", "org.springframework.data.jpa"),
            new PruneRule("postgresql", "org.postgresql", "jakarta.persistence", "org.springframework.data.jpa"),
            new PruneRule("mongodb",    "org.springframework.data.mongodb"),
            new PruneRule("redis",      "org.springframework.data.redis"),
            new PruneRule("kafka",      "org.springframework.kafka"),
            new PruneRule("amqp",       "org.springframework.amqp"),
            new PruneRule("rabbit",     "org.springframework.amqp"),
            new PruneRule("security",   "org.springframework.security"),
            new PruneRule("validation", "jakarta.validation", "org.springframework.validation"),
            new PruneRule("lombok",     "lombok"),
            new PruneRule("h2",         "jakarta.persistence", "org.springframework.data.jpa")
    );

    /**
     * ArtifactIds that must never be copied from the monolith into a generated service pom.
     * These are build-time generator dependencies; copying them would also carry the
     * unresolvable {@code ${fractalx.version}} placeholder into the generated pom.
     *
     * <p>All other FractalX runtime additions (netscope, fractalx-runtime, resilience4j, etc.)
     * are injected via {@link #addFractalxDeps} which is idempotent — no exclusion needed.
     */
    private static final Set<String> STRIP_FROM_MONOLITH = Set.of(
            "fractalx-annotations",
            "fractalx-core"
    );

    private final ObservabilityInjector observabilityInjector;

    public PomGenerator(ObservabilityInjector observabilityInjector) {
        this.observabilityInjector = observabilityInjector;
    }

    @Override
    public void generate(GenerationContext context) throws IOException {
        FractalModule module = context.getModule();
        FractalxConfig cfg = context.getFractalxConfig();
        log.debug("Generating pom.xml for {}", module.getServiceName());
        Files.writeString(context.getServiceRoot().resolve("pom.xml"),
                buildPomContent(module, context.getServiceRoot(), cfg));
    }

    // ── POM assembly ─────────────────────────────────────────────────────────

    private String buildPomContent(FractalModule module, Path serviceRoot, FractalxConfig cfg) {
        Path monolithPom = serviceRoot.getParent().getParent().resolve("pom.xml");
        if (!Files.exists(monolithPom)) {
            log.debug("No monolith pom at {} — generating from scratch", monolithPom);
            return buildPomFromScratch(module, cfg);
        }
        try {
            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(monolithPom.toFile());
            doc.getDocumentElement().normalize();

            Map<String, String> monolithProps = readPropertiesFromDoc(doc);

            // ── Targeted mutations ────────────────────────────────────────
            setChildText(doc.getDocumentElement(), "artifactId", module.getServiceName());
            removePropertyByName(doc, "fractalx.version");
            removeDependencyByArtifact(doc, "fractalx-annotations");
            removeDependencyByArtifact(doc, "fractalx-core");
            removePluginByArtifact(doc, "fractalx-maven-plugin");
            // spring-boot-starter-aop removed from Spring Boot 4.x; strip it so it doesn't
            // appear without a managed version in the generated pom
            boolean isBoot4Plus = SpringBootVersionUtil.isBoot4Plus(cfg.springBootVersion());
            if (isBoot4Plus) removeDependencyByArtifact(doc, "spring-boot-starter-aop");
            pruneAndResolveUnusedDeps(doc, module.getDetectedImports(), monolithProps,
                    module.getServiceName());
            addFractalxDeps(doc, cfg, module.getServiceName());
            addTransactionSupportIfNeeded(doc, module.getDetectedImports());
            if (cfg.features().observability()) {
                appendObservabilityDeps(doc);
            }
            if (isBoot4Plus) {
                // Spring Boot 4.x manages OTel via micrometer-tracing BOM. ObservabilityInjector
                // adds pinned OTel SDK versions (1.32.0) that are binary-incompatible with Boot 4.x's
                // managed opentelemetry-exporter-common (1.55+) and prevent Tracer auto-configuration.
                // Remove them AFTER appendObservabilityDeps() so Boot's BOM resolves correct versions
                // transitively from micrometer-tracing-bridge-otel.
                removeDependencyByArtifact(doc, "opentelemetry-sdk");
                removeDependencyByArtifact(doc, "opentelemetry-exporter-otlp");
                removeDependencyByArtifact(doc, "opentelemetry-semconv");
            }
            ensureSpringBootBom(doc, cfg.springBootVersion());
            ensureSpringCloudBom(doc, cfg.springCloudVersion());
            ensureSpringBootPlugin(doc);

            return serializeDoc(doc);
        } catch (Exception e) {
            log.warn("Could not parse/patch monolith pom — falling back to scratch: {}", e.getMessage());
            return buildPomFromScratch(module, cfg);
        }
    }

    /**
     * Fallback: generates a pom entirely from a hardcoded template, used when the
     * monolith pom cannot be found or parsed.
     */
    private String buildPomFromScratch(FractalModule module, FractalxConfig cfg) {
        boolean isBoot4Plus = SpringBootVersionUtil.isBoot4Plus(cfg.springBootVersion());

        // spring-boot-starter-aop removed from Spring Boot 4.x
        String aopDep = isBoot4Plus ? "" : """
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-aop</artifactId>
                        </dependency>
                """;
        // spring-cloud-context (2025.x) is incompatible with Spring Boot 4.x / Spring Framework 7.x
        String cloudContextDep = isBoot4Plus ? "" : """
                        <dependency>
                            <groupId>org.springframework.cloud</groupId>
                            <artifactId>spring-cloud-context</artifactId>
                        </dependency>
                """;
        // Exclude spring-cloud-context/commons from fractalx-runtime transitive deps on Spring Boot 4.x
        String runtimeDep = isBoot4Plus
                ? """
                        <dependency>
                            <groupId>org.fractalx</groupId>
                            <artifactId>fractalx-runtime</artifactId>
                            <version>%s</version>
                            <exclusions>
                                <exclusion>
                                    <groupId>org.springframework.cloud</groupId>
                                    <artifactId>spring-cloud-context</artifactId>
                                </exclusion>
                                <exclusion>
                                    <groupId>org.springframework.cloud</groupId>
                                    <artifactId>spring-cloud-commons</artifactId>
                                </exclusion>
                            </exclusions>
                        </dependency>
                """.formatted(FRACTALX_RUNTIME_VERSION)
                : """
                        <dependency>
                            <groupId>org.fractalx</groupId>
                            <artifactId>fractalx-runtime</artifactId>
                            <version>%s</version>
                        </dependency>
                """.formatted(FRACTALX_RUNTIME_VERSION);

        // flyway-core included only when flyway is enabled for this service
        FractalxConfig.ServiceOverride scratchOverride = cfg.serviceOverrides().get(module.getServiceName());
        boolean scratchFlyway = scratchOverride != null
                ? scratchOverride.effectiveFlyway(cfg.features().distributedData())
                : cfg.features().distributedData();
        String flywayDep = scratchFlyway ? """
                        <dependency>
                            <groupId>org.flywaydb</groupId>
                            <artifactId>flyway-core</artifactId>
                        </dependency>
                """ : "";

        // For Boot 4.x: ObservabilityInjector includes pinned OTel SDK versions (1.32.0) that are
        // binary-incompatible with Boot 4.x managed versions. Use only the version-managed deps.
        String observabilityDeps = !cfg.features().observability() ? "" : isBoot4Plus ? """
                        <!-- Micrometer OpenTelemetry tracing bridge — versions managed by Boot 4.x BOM -->
                        <dependency>
                            <groupId>io.micrometer</groupId>
                            <artifactId>micrometer-tracing-bridge-otel</artifactId>
                        </dependency>
                        <!-- Prometheus registry for /actuator/prometheus scraping -->
                        <dependency>
                            <groupId>io.micrometer</groupId>
                            <artifactId>micrometer-registry-prometheus</artifactId>
                        </dependency>
                """ : observabilityInjector.getDependencies();

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                    <packaging>jar</packaging>

                    <name>%s</name>
                    <description>Generated microservice by FractalX</description>

                    <properties>
                        <java.version>%s</java.version>
                        <spring-boot.version>%s</spring-boot.version>
                        <maven.compiler.source>%s</maven.compiler.source>
                        <maven.compiler.target>%s</maven.compiler.target>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    </properties>

                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-dependencies</artifactId>
                                <version>${spring-boot.version}</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                            <dependency>
                                <groupId>org.springframework.cloud</groupId>
                                <artifactId>spring-cloud-dependencies</artifactId>
                                <version>%s</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>

                    <dependencies>
                        <!-- ── FractalX required ────────────────────────────── -->
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.fractalx</groupId>
                            <artifactId>netscope-server</artifactId>
                            <version>%s</version>
                        </dependency>
                        <dependency>
                            <groupId>org.fractalx</groupId>
                            <artifactId>netscope-client</artifactId>
                            <version>%s</version>
                        </dependency>
                        %s<dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-actuator</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>io.github.resilience4j</groupId>
                            <artifactId>resilience4j-spring-boot3</artifactId>
                            <version>%s</version>
                        </dependency>
                        %s%s%s
                        <!-- ── Observability (OTel tracing) ─────────────────── -->
                %s
                    </dependencies>

                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <version>3.11.0</version>
                                <configuration>
                                    <parameters>true</parameters>
                                </configuration>
                            </plugin>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>${spring-boot.version}</version>
                                <executions>
                                    <execution>
                                        <goals>
                                            <goal>repackage</goal>
                                        </goals>
                                    </execution>
                                </executions>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """.formatted(
                cfg.effectiveBasePackage(),
                module.getServiceName(),
                cfg.initialServiceVersion(),
                module.getServiceName(),
                cfg.javaVersion(),
                cfg.springBootVersion(),
                cfg.javaVersion(),
                cfg.javaVersion(),
                cfg.springCloudVersion(),
                NETSCOPE_VERSION,
                NETSCOPE_VERSION,
                runtimeDep,
                RESILIENCE4J_VERSION,
                aopDep,
                cloudContextDep,
                flywayDep,
                observabilityDeps
        );
    }

    // ── DOM patch operations ──────────────────────────────────────────────────

    /**
     * Sets the text content of a direct child element with the given tag name.
     * Creates the element if it does not exist as a direct child.
     */
    private static void setChildText(Element parent, String tag, String value) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getNodeName())) {
                n.setTextContent(value);
                return;
            }
        }
        Element el = parent.getOwnerDocument().createElement(tag);
        el.setTextContent(value);
        parent.appendChild(el);
    }

    /**
     * Removes a named property from the monolith's {@code <properties>} block.
     * No-op if the property or the block does not exist.
     */
    private static void removePropertyByName(Document doc, String key) {
        NodeList children = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && "properties".equals(child.getNodeName())) {
                NodeList props = child.getChildNodes();
                for (int j = 0; j < props.getLength(); j++) {
                    Node p = props.item(j);
                    if (p.getNodeType() == Node.ELEMENT_NODE && key.equals(p.getNodeName())) {
                        child.removeChild(p);
                        return;
                    }
                }
                return;
            }
        }
    }

    /**
     * Removes a {@code <dependency>} by artifactId from {@code <project><dependencies>}
     * (not from {@code <dependencyManagement>}).
     */
    private static void removeDependencyByArtifact(Document doc, String artifactId) {
        NodeList children = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && "dependencies".equals(child.getNodeName())) {
                removeDepsByArtifact((Element) child, artifactId);
                return;
            }
        }
    }

    private static void removeDepsByArtifact(Element depsEl, String artifactId) {
        NodeList deps = depsEl.getElementsByTagName("dependency");
        List<Node> toRemove = new ArrayList<>();
        for (int i = 0; i < deps.getLength(); i++) {
            Element dep = (Element) deps.item(i);
            if (artifactId.equals(text(dep, "artifactId"))) toRemove.add(dep);
        }
        toRemove.forEach(n -> n.getParentNode().removeChild(n));
    }

    /**
     * Removes a {@code <plugin>} by artifactId from {@code <build><plugins>} (any depth).
     */
    private static void removePluginByArtifact(Document doc, String artifactId) {
        NodeList plugins = doc.getElementsByTagName("plugin");
        List<Node> toRemove = new ArrayList<>();
        for (int i = 0; i < plugins.getLength(); i++) {
            Node p = plugins.item(i);
            if (p.getNodeType() == Node.ELEMENT_NODE
                    && artifactId.equals(text((Element) p, "artifactId"))) {
                toRemove.add(p);
            }
        }
        toRemove.forEach(n -> n.getParentNode().removeChild(n));
    }

    /**
     * Walks {@code <project><dependencies>} and removes or resolves each entry:
     * <ul>
     *   <li>Deps in {@link #STRIP_FROM_MONOLITH} are always removed.</li>
     *   <li>Deps with unresolvable {@code ${placeholder}} versions are removed.</li>
     *   <li>Deps whose prune rule fires (no matching import) are removed.</li>
     * </ul>
     */
    private void pruneAndResolveUnusedDeps(Document doc, Set<String> imports,
                                            Map<String, String> monolithProps,
                                            String serviceName) {
        NodeList children = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && "dependencies".equals(child.getNodeName())) {
                pruneFromElement((Element) child, imports, monolithProps, serviceName);
                return;
            }
        }
    }

    private void pruneFromElement(Element depsEl, Set<String> imports,
                                   Map<String, String> monolithProps, String serviceName) {
        NodeList deps = depsEl.getElementsByTagName("dependency");
        List<Node> toRemove = new ArrayList<>();
        int kept = 0, pruned = 0;

        for (int i = 0; i < deps.getLength(); i++) {
            Element dep = (Element) deps.item(i);
            String artifactId = text(dep, "artifactId");

            if (STRIP_FROM_MONOLITH.contains(artifactId)) {
                toRemove.add(dep);
                pruned++;
                continue;
            }
            if (!resolveVersionPlaceholder(dep, artifactId, monolithProps)) {
                toRemove.add(dep);
                pruned++;
                continue;
            }
            if (!isDependencyUsed(artifactId, imports)) {
                log.debug("Pruned unused dep '{}' from {}", artifactId, serviceName);
                toRemove.add(dep);
                pruned++;
            } else {
                kept++;
            }
        }

        toRemove.forEach(n -> n.getParentNode().removeChild(n));
        log.info("Dep clone for {}: {} kept, {} pruned", serviceName, kept, pruned);
    }

    /**
     * Idempotently adds all FractalX-required runtime dependencies.
     * Each call to {@link #addDepIfAbsent} is a no-op if the dep is already present
     * (copied from monolith or previously added).
     */
    private void addFractalxDeps(Document doc, FractalxConfig cfg, String serviceName) {
        boolean isBoot4Plus = SpringBootVersionUtil.isBoot4Plus(cfg.springBootVersion());
        Element depsEl = ensureDependenciesElement(doc);
        addDepIfAbsent(doc, depsEl, "org.springframework.boot",  "spring-boot-starter-web",        null,                    null);
        addDepIfAbsent(doc, depsEl, "org.springframework.boot",  "spring-boot-starter-validation", null,                    null);
        addDepIfAbsent(doc, depsEl, "org.springframework.boot",  "spring-boot-starter-actuator",   null,                    null);
        // spring-boot-starter-aop was removed from Spring Boot 4.x; AOP is core in Spring Framework 7
        if (!isBoot4Plus) {
            addDepIfAbsent(doc, depsEl, "org.springframework.boot", "spring-boot-starter-aop",     null,                    null);
        }
        // spring-cloud-context (Spring Cloud 2025.x) is incompatible with Spring Boot 4.x/Spring Framework 7.x
        if (!isBoot4Plus) {
            addDepIfAbsent(doc, depsEl, "org.springframework.cloud", "spring-cloud-context",       null,                    null);
        }
        addDepIfAbsent(doc, depsEl, "org.fractalx",              "netscope-server",               NETSCOPE_VERSION,        null);
        addDepIfAbsent(doc, depsEl, "org.fractalx",              "netscope-client",               NETSCOPE_VERSION,        null);
        addDepIfAbsent(doc, depsEl, "org.fractalx",              "fractalx-runtime",              FRACTALX_RUNTIME_VERSION, null);
        // For Boot 4.x, exclude spring-cloud-context from fractalx-runtime's transitive deps
        // (fractalx-runtime → spring-cloud-starter-openfeign → spring-cloud-context which is Boot 3.x only)
        if (isBoot4Plus) {
            // spring-cloud-context and spring-cloud-commons (2025.x) are not compatible with
            // Spring Boot 4.x / Spring Framework 7.x (WebServerInitializedEvent moved packages etc.)
            addExclusionToDep(doc, "fractalx-runtime", "org.springframework.cloud", "spring-cloud-context");
            addExclusionToDep(doc, "fractalx-runtime", "org.springframework.cloud", "spring-cloud-commons");
        }
        addDepIfAbsent(doc, depsEl, "io.github.resilience4j",    "resilience4j-spring-boot3",     RESILIENCE4J_VERSION,    null);
        // flyway-core is added only when Flyway is enabled for this service (project-level
        // distributed-data flag, overridable per service). This guarantees the YAML
        // flyway.enabled: true and the classpath dependency are always in sync.
        FractalxConfig.ServiceOverride override = cfg.serviceOverrides().get(serviceName);
        boolean flyway = override != null
                ? override.effectiveFlyway(cfg.features().distributedData())
                : cfg.features().distributedData();
        if (flyway) {
            addDepIfAbsent(doc, depsEl, "org.flywaydb", "flyway-core", null, null);
        }
    }

    /**
     * Adds standalone {@code spring-tx} when a service uses {@code @Transactional} but has no
     * JPA content (i.e. {@code spring-boot-starter-data-jpa} was pruned).
     *
     * <p>Using standalone {@code spring-tx} (rather than keeping the full {@code data-jpa})
     * is intentional: {@code spring-tx} does <em>not</em> pull in {@code spring-jdbc}, so
     * Spring Boot's {@code DataSourceAutoConfiguration} is not activated and no DataSource
     * bean is required at startup.
     */
    private static void addTransactionSupportIfNeeded(Document doc, Set<String> imports) {
        if (imports == null || imports.isEmpty()) return;
        if (!importsAny(imports, "org.springframework.transaction")) return;
        // data-jpa already on classpath → spring-tx is a transitive dep, nothing to add
        if (hasDependency(doc, "spring-boot-starter-data-jpa")) return;
        addDepIfAbsent(doc, ensureDependenciesElement(doc),
                "org.springframework", "spring-tx", null, null);
    }

    /**
     * Parses the observability dep XML fragment and appends any dep not already present.
     */
    private void appendObservabilityDeps(Document doc) {
        String depsXml = observabilityInjector.getDependencies();
        if (depsXml == null || depsXml.isBlank()) return;
        try {
            Document fragDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new InputSource(new StringReader("<dependencies>" + depsXml + "</dependencies>")));
            Element depsEl = ensureDependenciesElement(doc);
            NodeList depNodes = fragDoc.getDocumentElement().getElementsByTagName("dependency");
            for (int i = 0; i < depNodes.getLength(); i++) {
                Element dep = (Element) depNodes.item(i);
                String artifactId = text(dep, "artifactId");
                if (!hasDependency(doc, artifactId)) {
                    depsEl.appendChild(doc.importNode(dep, true));
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse observability deps fragment: {}", e.getMessage());
        }
    }

    /**
     * Ensures {@code spring-boot-dependencies} BOM is present in {@code <dependencyManagement>}.
     * This is a safety net: when the monolith uses {@code spring-boot-starter-parent} as parent,
     * all Spring Boot starters are already managed by the parent. However, when the parent version
     * cannot be resolved at build time, having an explicit BOM entry guarantees that starters added
     * by {@link #addFractalxDeps} (e.g. {@code spring-boot-starter-aop}) have managed versions.
     */
    private static void ensureSpringBootBom(Document doc, String version) {
        NodeList dms = doc.getElementsByTagName("dependencyManagement");
        if (dms.getLength() > 0) {
            Element dmEl = (Element) dms.item(0);
            NodeList deps = dmEl.getElementsByTagName("dependency");
            for (int i = 0; i < deps.getLength(); i++) {
                String aid = text((Element) deps.item(i), "artifactId");
                if ("spring-boot-dependencies".equals(aid) || "spring-boot-starter-parent".equals(aid)) {
                    return; // already managed
                }
            }
            NodeList innerDeps = dmEl.getElementsByTagName("dependencies");
            Element innerDepsEl = innerDeps.getLength() > 0
                    ? (Element) innerDeps.item(0)
                    : doc.createElement("dependencies");
            if (innerDeps.getLength() == 0) dmEl.appendChild(innerDepsEl);
            innerDepsEl.appendChild(createSpringBootBomElement(doc, version));
        } else {
            Element dm = doc.createElement("dependencyManagement");
            Element innerDeps = doc.createElement("dependencies");
            innerDeps.appendChild(createSpringBootBomElement(doc, version));
            dm.appendChild(innerDeps);
            doc.getDocumentElement().appendChild(dm);
        }
    }

    private static Element createSpringBootBomElement(Document doc, String version) {
        Element dep = doc.createElement("dependency");
        Element g = doc.createElement("groupId");    g.setTextContent("org.springframework.boot");  dep.appendChild(g);
        Element a = doc.createElement("artifactId"); a.setTextContent("spring-boot-dependencies"); dep.appendChild(a);
        Element v = doc.createElement("version");    v.setTextContent(version);                     dep.appendChild(v);
        Element t = doc.createElement("type");       t.setTextContent("pom");                       dep.appendChild(t);
        Element s = doc.createElement("scope");      s.setTextContent("import");                    dep.appendChild(s);
        return dep;
    }

    /**
     * Ensures the Spring Cloud BOM is present in {@code <dependencyManagement>}.
     * Appended only if no entry with artifactId {@code spring-cloud-dependencies} exists.
     */
    private static void ensureSpringCloudBom(Document doc, String version) {
        // Check if already present anywhere in dependencyManagement
        NodeList dms = doc.getElementsByTagName("dependencyManagement");
        if (dms.getLength() > 0) {
            Element dmEl = (Element) dms.item(0);
            NodeList deps = dmEl.getElementsByTagName("dependency");
            for (int i = 0; i < deps.getLength(); i++) {
                if ("spring-cloud-dependencies".equals(text((Element) deps.item(i), "artifactId"))) {
                    return; // already present
                }
            }
            // Add to existing <dependencyManagement><dependencies>
            NodeList innerDeps = dmEl.getElementsByTagName("dependencies");
            Element innerDepsEl;
            if (innerDeps.getLength() > 0) {
                innerDepsEl = (Element) innerDeps.item(0);
            } else {
                innerDepsEl = doc.createElement("dependencies");
                dmEl.appendChild(innerDepsEl);
            }
            innerDepsEl.appendChild(createSpringCloudBomElement(doc, version));
        } else {
            // Create <dependencyManagement> from scratch
            Element dm = doc.createElement("dependencyManagement");
            Element innerDeps = doc.createElement("dependencies");
            innerDeps.appendChild(createSpringCloudBomElement(doc, version));
            dm.appendChild(innerDeps);
            doc.getDocumentElement().appendChild(dm);
        }
    }

    private static Element createSpringCloudBomElement(Document doc, String version) {
        Element dep = doc.createElement("dependency");
        Element g = doc.createElement("groupId");   g.setTextContent("org.springframework.cloud");  dep.appendChild(g);
        Element a = doc.createElement("artifactId"); a.setTextContent("spring-cloud-dependencies"); dep.appendChild(a);
        Element v = doc.createElement("version");    v.setTextContent(version);                     dep.appendChild(v);
        Element t = doc.createElement("type");       t.setTextContent("pom");                       dep.appendChild(t);
        Element s = doc.createElement("scope");      s.setTextContent("import");                    dep.appendChild(s);
        return dep;
    }

    /**
     * Ensures {@code spring-boot-maven-plugin} with a {@code repackage} execution is present
     * in {@code <build><plugins>}. Creates {@code <build>} and {@code <plugins>} if absent.
     */
    private static void ensureSpringBootPlugin(Document doc) {
        NodeList plugins = doc.getElementsByTagName("plugin");
        for (int i = 0; i < plugins.getLength(); i++) {
            if ("spring-boot-maven-plugin".equals(text((Element) plugins.item(i), "artifactId"))) {
                return; // already present
            }
        }

        // Find or create <build>
        NodeList buildNodes = doc.getElementsByTagName("build");
        Element buildEl;
        if (buildNodes.getLength() > 0) {
            buildEl = (Element) buildNodes.item(0);
        } else {
            buildEl = doc.createElement("build");
            doc.getDocumentElement().appendChild(buildEl);
        }

        // Find or create <plugins>
        NodeList existingPlugins = buildEl.getElementsByTagName("plugins");
        Element pluginsEl;
        if (existingPlugins.getLength() > 0) {
            pluginsEl = (Element) existingPlugins.item(0);
        } else {
            pluginsEl = doc.createElement("plugins");
            buildEl.appendChild(pluginsEl);
        }

        // Build the plugin element
        Element plugin = doc.createElement("plugin");
        Element g = doc.createElement("groupId");    g.setTextContent("org.springframework.boot"); plugin.appendChild(g);
        Element a = doc.createElement("artifactId"); a.setTextContent("spring-boot-maven-plugin"); plugin.appendChild(a);
        Element execs = doc.createElement("executions");
        Element exec  = doc.createElement("execution");
        Element goals = doc.createElement("goals");
        Element goal  = doc.createElement("goal"); goal.setTextContent("repackage");
        goals.appendChild(goal);
        exec.appendChild(goals);
        execs.appendChild(exec);
        plugin.appendChild(execs);
        pluginsEl.appendChild(plugin);
    }

    // ── DOM helpers ───────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the given artifactId exists in {@code <project><dependencies>}
     * (not {@code <dependencyManagement>}).
     */
    private static boolean hasDependency(Document doc, String artifactId) {
        NodeList children = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && "dependencies".equals(child.getNodeName())) {
                NodeList deps = ((Element) child).getElementsByTagName("dependency");
                for (int j = 0; j < deps.getLength(); j++) {
                    if (artifactId.equals(text((Element) deps.item(j), "artifactId"))) return true;
                }
                return false;
            }
        }
        return false;
    }

    /**
     * Appends a {@code <dependency>} to {@code depsEl} only if no dep with the same
     * artifactId is already present in {@code <project><dependencies>}.
     * Omits the {@code <version>} element when {@code version} is {@code null}
     * (BOM or parent manages it).
     */
    private static void addDepIfAbsent(Document doc, Element depsEl,
                                        String groupId, String artifactId,
                                        String version, String scope) {
        if (hasDependency(doc, artifactId)) return;
        Element dep = doc.createElement("dependency");
        Element g = doc.createElement("groupId");    g.setTextContent(groupId);    dep.appendChild(g);
        Element a = doc.createElement("artifactId"); a.setTextContent(artifactId); dep.appendChild(a);
        if (version != null) {
            Element v = doc.createElement("version"); v.setTextContent(version); dep.appendChild(v);
        }
        if (scope != null) {
            Element s = doc.createElement("scope"); s.setTextContent(scope); dep.appendChild(s);
        }
        depsEl.appendChild(dep);
    }

    /**
     * Adds an {@code <exclusion>} to an existing dependency identified by {@code artifactId}.
     * No-op if the dependency is not found or already has this exclusion.
     */
    private static void addExclusionToDep(Document doc, String depArtifactId,
                                           String excludeGroupId, String excludeArtifactId) {
        NodeList children = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE || !"dependencies".equals(child.getNodeName())) continue;
            NodeList deps = ((Element) child).getElementsByTagName("dependency");
            for (int j = 0; j < deps.getLength(); j++) {
                Element dep = (Element) deps.item(j);
                if (!depArtifactId.equals(text(dep, "artifactId"))) continue;
                // Find or create <exclusions>
                NodeList existingExclusions = dep.getElementsByTagName("exclusions");
                Element exclusionsEl;
                if (existingExclusions.getLength() > 0) {
                    exclusionsEl = (Element) existingExclusions.item(0);
                    // Check if exclusion already present
                    NodeList excs = exclusionsEl.getElementsByTagName("exclusion");
                    for (int k = 0; k < excs.getLength(); k++) {
                        if (excludeArtifactId.equals(text((Element) excs.item(k), "artifactId"))) return;
                    }
                } else {
                    exclusionsEl = doc.createElement("exclusions");
                    dep.appendChild(exclusionsEl);
                }
                Element excl = doc.createElement("exclusion");
                Element eg = doc.createElement("groupId");    eg.setTextContent(excludeGroupId);    excl.appendChild(eg);
                Element ea = doc.createElement("artifactId"); ea.setTextContent(excludeArtifactId); excl.appendChild(ea);
                exclusionsEl.appendChild(excl);
                return;
            }
        }
    }

    /**
     * Returns the existing {@code <project><dependencies>} element, creating it if absent.
     */
    private static Element ensureDependenciesElement(Document doc) {
        NodeList children = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && "dependencies".equals(child.getNodeName())) {
                return (Element) child;
            }
        }
        Element depsEl = doc.createElement("dependencies");
        doc.getDocumentElement().appendChild(depsEl);
        return depsEl;
    }

    // ── Usage check ───────────────────────────────────────────────────────────

    /**
     * Returns {@code false} only when a prune rule matches the artifactId AND
     * none of the rule's required import prefixes are detected.
     * Everything else is kept (safe default).
     */
    private static boolean isDependencyUsed(String artifactId, Set<String> imports) {
        String aid = artifactId.toLowerCase();
        for (PruneRule rule : PRUNE_RULES) {
            if (aid.contains(rule.artifactFragment())) {
                return importsAny(imports, rule.requiredPrefixes());
            }
        }
        return true; // cannot determine — keep it
    }

    private static boolean importsAny(Set<String> imports, String... prefixes) {
        for (String imp : imports) {
            for (String prefix : prefixes) {
                if (imp.startsWith(prefix)) return true;
            }
        }
        return false;
    }

    // ── Monolith property reading ─────────────────────────────────────────────

    /**
     * Reads {@code <properties>} from an already-parsed monolith Document into a map
     * so that version placeholders like {@code ${lombok.version}} can be resolved
     * before the dependency element is used in the generated pom.
     */
    private static Map<String, String> readPropertiesFromDoc(Document doc) {
        Map<String, String> props = new HashMap<>();
        NodeList children = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && "properties".equals(child.getNodeName())) {
                NodeList propNodes = child.getChildNodes();
                for (int j = 0; j < propNodes.getLength(); j++) {
                    Node p = propNodes.item(j);
                    if (p.getNodeType() == Node.ELEMENT_NODE)
                        props.put(p.getNodeName(), p.getTextContent().trim());
                }
                break;
            }
        }
        return props;
    }

    /**
     * If the {@code <version>} of {@code dep} is a {@code ${key}} placeholder, replaces its
     * text content with the resolved value from {@code props}.
     *
     * @return {@code true} when the version is usable (literal, absent, or successfully
     *         resolved); {@code false} when the placeholder cannot be resolved — in that case
     *         the caller should skip this dep to avoid emitting an invalid generated pom.
     */
    private boolean resolveVersionPlaceholder(Element dep, String artifactId,
                                              Map<String, String> props) {
        NodeList versions = dep.getElementsByTagName("version");
        if (versions.getLength() == 0) return true;   // no <version> — inherited from BOM, fine

        Node vn = versions.item(0);
        String ver = vn.getTextContent().trim();
        if (!ver.startsWith("${") || !ver.endsWith("}")) return true;  // literal version

        String key = ver.substring(2, ver.length() - 1);
        String resolved = props.get(key);
        if (resolved != null) {
            vn.setTextContent(resolved);
            log.debug("Resolved version placeholder '{}' → '{}' for dep '{}'",
                    ver, resolved, artifactId);
            return true;
        }

        // The property is not in the monolith's own <properties> — it is resolved by the
        // parent BOM (e.g. spring-boot-starter-parent) at build time.  Strip the <version>
        // element so the dep becomes BOM-managed in the generated service pom, which still
        // inherits the same parent.  Dropping the dep entirely would silently lose it.
        dep.removeChild(vn);
        log.warn("PomGenerator: version placeholder '{}' for dependency '{}' could not be resolved "
                + "from monolith <properties> — stripped <version> and relying on parent/BOM. "
                + "If the generated pom fails to build, add the version property explicitly.",
                ver, artifactId);
        return true;
    }

    // ── XML serialization ─────────────────────────────────────────────────────

    private static String serializeDoc(Document doc) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter sw = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(sw));
        return sw.toString();
    }

    private static String text(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) return "";
        return nl.item(0).getTextContent().trim();
    }

    // ── Prune rule ────────────────────────────────────────────────────────────

    private record PruneRule(String artifactFragment, String... requiredPrefixes) {}
}
