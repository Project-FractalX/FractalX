package org.fractalx.core.generator.service

import org.fractalx.core.FractalxVersion
import org.fractalx.core.config.FractalxConfig
import org.fractalx.core.generator.GenerationContext
import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class PomGeneratorSpec extends Specification {

    @TempDir
    Path tempDir

    PomGenerator generator = new PomGenerator()

    /**
     * Directory layout that mirrors real decompose output:
     *   tempDir/               ← "monolith root" — monolith pom.xml lives here
     *   tempDir/fractalx-output/              ← outputRoot
     *   tempDir/fractalx-output/order-service/ ← serviceRoot
     *
     * PomGenerator derives monolithRoot as serviceRoot.getParent().getParent()
     */
    private Path outputRoot()  { tempDir.resolve("fractalx-output") }
    private Path serviceRoot() { outputRoot().resolve("order-service") }

    /** Write a minimal monolith pom.xml with the given dependency XML blocks. */
    private void writeMonolithPom(String... depBlocks) {
        String deps = depBlocks.join("\n")
        Files.writeString(tempDir.resolve("pom.xml"), """
            <project>
                <groupId>com.example</groupId>
                <artifactId>monolith</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    ${deps}
                </dependencies>
            </project>
        """)
    }

    /** Write a full monolith pom.xml verbatim (for tests that need &lt;parent&gt;/&lt;build&gt;). */
    private void writeMonolithPomRaw(String fullXml) {
        Files.writeString(tempDir.resolve("pom.xml"), fullXml)
    }

    private String generate(FractalModule module) {
        def svcRoot = serviceRoot()
        Files.createDirectories(svcRoot)
        def ctx = new GenerationContext(module, outputRoot(), svcRoot,
                [module], FractalxConfig.defaults())
        generator.generate(ctx)
        Files.readString(svcRoot.resolve("pom.xml"))
    }

    private FractalModule baseModule(Set<String> imports = []) {
        FractalModule.builder()
            .serviceName("order-service")
            .packageName("com.example.order")
            .port(8081)
            .detectedImports(imports)
            .build()
    }

    // ── Always-present deps ────────────────────────────────────────────────────

    def "generated pom uses the current fractalx-runtime version"() {
        when:
        def content = generate(baseModule())

        then:
        def v = content.find(/(?s)<artifactId>fractalx-runtime<\/artifactId>\s*<version>([^<]+)<\/version>/) { _, v -> v }
        v == FractalxVersion.get()
    }

    def "netscope-server version is 1.0.1"() {
        when:
        def content = generate(baseModule())

        then:
        def v = content.find(/(?s)<artifactId>netscope-server<\/artifactId>\s*<version>([^<]+)<\/version>/) { _, v -> v }
        v == "1.0.1"
    }

    def "netscope-client version is 1.0.1"() {
        when:
        def content = generate(baseModule())

        then:
        def v = content.find(/(?s)<artifactId>netscope-client<\/artifactId>\s*<version>([^<]+)<\/version>/) { _, v -> v }
        v == "1.0.1"
    }

    def "generated pom sets service artifactId"() {
        when:
        def content = generate(baseModule())

        then:
        content.contains("<artifactId>order-service</artifactId>")
    }

    def "generated pom always includes spring-boot-starter-web"() {
        when:
        def content = generate(baseModule())

        then:
        content.contains("<artifactId>spring-boot-starter-web</artifactId>")
    }

    def "generated pom includes spring-boot-maven-plugin repackage"() {
        given: "monolith pom with spring-boot-maven-plugin already declared"
        writeMonolithPomRaw("""
            <project>
                <groupId>com.example</groupId>
                <artifactId>monolith</artifactId>
                <version>1.0.0</version>
                <dependencies></dependencies>
                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-maven-plugin</artifactId>
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
        """)

        when:
        def content = generate(baseModule())

        then:
        content.contains("<goal>repackage</goal>")
    }

    def "generated pom includes spring-boot-maven-plugin repackage when monolith has no build"() {
        given: "monolith pom with no build section — ensureSpringBootPlugin() must add it"
        writeMonolithPom()

        when:
        def content = generate(baseModule())

        then:
        content.contains("<goal>repackage</goal>")
    }

    // ── Monolith structure preservation ───────────────────────────────────────

    def "generated pom preserves monolith parent block"() {
        given:
        writeMonolithPomRaw("""
            <project>
                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.2.0</version>
                    <relativePath/>
                </parent>
                <groupId>com.example</groupId>
                <artifactId>monolith</artifactId>
                <version>1.0.0</version>
                <dependencies></dependencies>
            </project>
        """)

        when:
        def content = generate(baseModule())

        then:
        content.contains("spring-boot-starter-parent")
        content.contains("<version>3.2.0</version>")
    }

    def "generated pom preserves monolith properties block"() {
        given:
        writeMonolithPomRaw("""
            <project>
                <groupId>com.example</groupId>
                <artifactId>monolith</artifactId>
                <version>1.0.0</version>
                <properties>
                    <java.version>21</java.version>
                    <lombok.version>1.18.38</lombok.version>
                    <maven.compiler.source>21</maven.compiler.source>
                </properties>
                <dependencies></dependencies>
            </project>
        """)

        when:
        def content = generate(baseModule())

        then:
        content.contains("<java.version>21</java.version>")
        content.contains("<lombok.version>1.18.38</lombok.version>")
        content.contains("<maven.compiler.source>21</maven.compiler.source>")
        !content.contains("<fractalx.version>")
    }

    def "fractalx.version property removed from generated pom"() {
        given:
        writeMonolithPomRaw("""
            <project>
                <groupId>com.example</groupId>
                <artifactId>monolith</artifactId>
                <version>1.0.0</version>
                <properties>
                    <java.version>17</java.version>
                    <fractalx.version>0.3.0-SNAPSHOT</fractalx.version>
                </properties>
                <dependencies></dependencies>
            </project>
        """)

        when:
        def content = generate(baseModule())

        then:
        !content.contains("fractalx.version")
        content.contains("<java.version>17</java.version>")
    }

    def "fractalx-annotations and fractalx-core absent from generated pom"() {
        given:
        writeMonolithPom("""
            <dependency>
                <groupId>org.fractalx</groupId>
                <artifactId>fractalx-annotations</artifactId>
                <version>0.3.0</version>
            </dependency>
            <dependency>
                <groupId>org.fractalx</groupId>
                <artifactId>fractalx-core</artifactId>
                <version>0.3.0</version>
            </dependency>
        """)

        when:
        def content = generate(baseModule())

        then:
        !content.contains("fractalx-annotations")
        !content.contains("<artifactId>fractalx-core</artifactId>")
    }

    def "fractalx-maven-plugin absent from generated pom build"() {
        given:
        writeMonolithPomRaw("""
            <project>
                <groupId>com.example</groupId>
                <artifactId>monolith</artifactId>
                <version>1.0.0</version>
                <dependencies></dependencies>
                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.fractalx</groupId>
                            <artifactId>fractalx-maven-plugin</artifactId>
                            <version>0.3.0</version>
                        </plugin>
                        <plugin>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-maven-plugin</artifactId>
                            <executions><execution><goals><goal>repackage</goal></goals></execution></executions>
                        </plugin>
                    </plugins>
                </build>
            </project>
        """)

        when:
        def content = generate(baseModule())

        then:
        !content.contains("fractalx-maven-plugin")
        content.contains("spring-boot-maven-plugin")
    }

    // ── Monolith dep cloning ───────────────────────────────────────────────────

    def "deps from monolith pom are cloned into service pom"() {
        given:
        writeMonolithPom("""
            <dependency>
                <groupId>com.google.guava</groupId>
                <artifactId>guava</artifactId>
                <version>32.0.1-jre</version>
            </dependency>
        """)

        when:
        def content = generate(baseModule())

        then:
        content.contains("guava")
    }

    def "no monolith pom produces pom with only fractalx required deps"() {
        // no pom.xml written to tempDir
        when:
        def content = generate(baseModule())

        then:
        content.contains("spring-boot-starter-web")
        content.contains("fractalx-runtime")
        !content.contains("guava")
    }

    def "spring-boot-starter-web copied from monolith not duplicated"() {
        given:
        writeMonolithPom("""
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-web</artifactId>
            </dependency>
        """)

        when:
        def content = generate(baseModule())

        then:
        // Copied from monolith once; addDepIfAbsent skips re-adding
        content.count("spring-boot-starter-web") == 1
    }

    def "lombok version in generated pom matches monolith when declared"() {
        given:
        writeMonolithPomRaw("""
            <project>
                <groupId>com.example</groupId>
                <artifactId>monolith</artifactId>
                <version>1.0.0</version>
                <properties>
                    <lombok.version>1.18.38</lombok.version>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                        <version>\${lombok.version}</version>
                        <optional>true</optional>
                    </dependency>
                </dependencies>
            </project>
        """)

        when:
        def content = generate(baseModule(["lombok."] as Set))

        then:
        content.contains("1.18.38")
        content.count("<artifactId>lombok</artifactId>") == 1
    }

    // ── Version placeholder handling ───────────────────────────────────────────

    def "dep with parent-managed version placeholder is kept with version stripped (not dropped)"() {
        given: "monolith dep whose version is defined only in the parent BOM, not in local <properties>"
        writeMonolithPomRaw("""
            <project>
                <groupId>com.example</groupId>
                <artifactId>monolith</artifactId>
                <version>1.0.0</version>
                <properties>
                    <!-- external.version is NOT declared here — it lives in the parent BOM -->
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>some-library</artifactId>
                        <version>\${external.version}</version>
                    </dependency>
                </dependencies>
            </project>
        """)

        when:
        def content = generate(baseModule())

        then: "dep is present (not dropped)"
        content.contains("<artifactId>some-library</artifactId>")

        and: "unresolvable placeholder is stripped so the dep becomes BOM-managed"
        !content.contains('${external.version}')
        !content.contains("<version>\${")
    }

    def "dep with locally resolvable version placeholder is resolved inline"() {
        given:
        writeMonolithPomRaw("""
            <project>
                <groupId>com.example</groupId>
                <artifactId>monolith</artifactId>
                <version>1.0.0</version>
                <properties>
                    <guava.version>32.1.0-jre</guava.version>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>com.google.guava</groupId>
                        <artifactId>guava</artifactId>
                        <version>\${guava.version}</version>
                    </dependency>
                </dependencies>
            </project>
        """)

        when:
        def content = generate(baseModule())

        then:
        content.contains("<artifactId>guava</artifactId>")
        content.contains("<version>32.1.0-jre</version>")
        !content.contains('${guava.version}')
    }

    // ── Pruning ────────────────────────────────────────────────────────────────

    def "JPA dep kept when jakarta.persistence import detected"() {
        given:
        writeMonolithPom("""
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-data-jpa</artifactId>
            </dependency>
            <dependency>
                <groupId>com.h2database</groupId>
                <artifactId>h2</artifactId>
                <scope>runtime</scope>
            </dependency>
        """)

        when:
        def content = generate(baseModule(["jakarta.persistence.Entity"] as Set))

        then:
        content.contains("spring-boot-starter-data-jpa")
        content.contains("<artifactId>h2</artifactId>")
    }

    def "JPA dep pruned when no persistence imports detected"() {
        given:
        writeMonolithPom("""
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-data-jpa</artifactId>
            </dependency>
        """)

        when:
        def content = generate(baseModule()) // no imports

        then:
        !content.contains("spring-boot-starter-data-jpa")
    }

    def "mysql dep kept when com.mysql import detected"() {
        given:
        writeMonolithPom("""
            <dependency>
                <groupId>com.mysql</groupId>
                <artifactId>mysql-connector-j</artifactId>
                <scope>runtime</scope>
            </dependency>
        """)

        when:
        def content = generate(baseModule(["com.mysql.cj.jdbc.Driver"] as Set))

        then:
        content.contains("mysql-connector-j")
    }

    def "mysql dep pruned when no mysql import detected"() {
        given:
        writeMonolithPom("""
            <dependency>
                <groupId>com.mysql</groupId>
                <artifactId>mysql-connector-j</artifactId>
                <scope>runtime</scope>
            </dependency>
        """)

        when:
        def content = generate(baseModule()) // no imports

        then:
        !content.contains("mysql-connector-j")
    }

    def "mysql driver kept when JPA import present (runtime dep — no com.mysql import in Spring JPA code)"() {
        given: "service uses JPA but never imports com.mysql directly (normal Spring JPA pattern)"
        writeMonolithPom("""
            <dependency>
                <groupId>com.mysql</groupId>
                <artifactId>mysql-connector-j</artifactId>
                <scope>runtime</scope>
            </dependency>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-data-jpa</artifactId>
            </dependency>
        """)

        when:
        def content = generate(baseModule(["jakarta.persistence.Entity"] as Set))

        then: "driver must be kept — no com.mysql import but JPA is present"
        content.contains("mysql-connector-j")
    }

    def "postgresql driver kept when JPA import present"() {
        given:
        writeMonolithPom("""
            <dependency>
                <groupId>org.postgresql</groupId>
                <artifactId>postgresql</artifactId>
                <scope>runtime</scope>
            </dependency>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-data-jpa</artifactId>
            </dependency>
        """)

        when:
        def content = generate(baseModule(["jakarta.persistence.Entity"] as Set))

        then:
        content.contains("<artifactId>postgresql</artifactId>")
    }

    def "kafka dep kept when spring kafka import detected"() {
        given:
        writeMonolithPom("""
            <dependency>
                <groupId>org.springframework.kafka</groupId>
                <artifactId>spring-kafka</artifactId>
            </dependency>
        """)

        when:
        def content = generate(baseModule(["org.springframework.kafka.annotation.KafkaListener"] as Set))

        then:
        content.contains("spring-kafka")
    }

    def "unknown dep (no prune rule) is always kept"() {
        given:
        writeMonolithPom("""
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
            </dependency>
        """)

        when:
        def content = generate(baseModule()) // no imports — still kept

        then:
        content.contains("jackson-databind")
    }
}
