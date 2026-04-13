package org.fractalx.core.observability

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import org.fractalx.core.config.FractalxConfig

/**
 * Verifies that LoggerServiceGenerator creates a fully functional centralized
 * log aggregation service with correct REST API, in-memory storage, and configuration.
 *
 * Generated structure:
 *   logger-service/
 *     pom.xml
 *     src/main/java/org/fractalx/logger/
 *       LoggerApplication.java
 *       LogEntry.java
 *       LogRepository.java
 *       LogController.java
 *     src/main/resources/
 *       application.yml
 */
class LoggerServiceGeneratorSpec extends Specification {

    @TempDir
    Path outputRoot

    LoggerServiceGenerator generator = new LoggerServiceGenerator()

    private Path serviceRoot()    { outputRoot.resolve("logger-service") }
    private Path javaRoot()       { serviceRoot().resolve("src/main/java/org/fractalx/logger") }
    private Path resourcesRoot()  { serviceRoot().resolve("src/main/resources") }

    private String pom()           { Files.readString(serviceRoot().resolve("pom.xml")) }
    private String application()   { Files.readString(javaRoot().resolve("LoggerApplication.java")) }
    private String logEntry()      { Files.readString(javaRoot().resolve("LogEntry.java")) }
    private String logRepository() { Files.readString(javaRoot().resolve("LogRepository.java")) }
    private String logController() { Files.readString(javaRoot().resolve("LogController.java")) }
    private String config()        { Files.readString(resourcesRoot().resolve("application.yml")) }

    // -------------------------------------------------------------------------
    // Directory structure
    // -------------------------------------------------------------------------

    def "logger-service java and resources directories are created"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        Files.exists(javaRoot())
        Files.exists(resourcesRoot())
    }

    def "all six source files are created"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        Files.exists(serviceRoot().resolve("pom.xml"))
        Files.exists(javaRoot().resolve("LoggerApplication.java"))
        Files.exists(javaRoot().resolve("LogEntry.java"))
        Files.exists(javaRoot().resolve("LogRepository.java"))
        Files.exists(javaRoot().resolve("LogController.java"))
        Files.exists(resourcesRoot().resolve("application.yml"))
    }

    // -------------------------------------------------------------------------
    // pom.xml
    // -------------------------------------------------------------------------

    def "pom.xml declares artifactId logger-service with Spring Boot parent 3.2.0"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        def c = pom()
        c.contains("<artifactId>logger-service</artifactId>")
        c.contains("spring-boot-starter-parent")
        c.contains("3.2.0")
    }

    def "pom.xml includes spring-boot-starter-web and spring-boot-starter-actuator"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        def c = pom()
        c.contains("spring-boot-starter-web")
        c.contains("spring-boot-starter-actuator")
    }

    def "pom.xml uses Java 17"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        pom().contains("<java.version>21</java.version>")
    }

    // -------------------------------------------------------------------------
    // LoggerApplication.java
    // -------------------------------------------------------------------------

    def "LoggerApplication is a @SpringBootApplication main class in org.fractalx.logger"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        def c = application()
        c.contains("package org.fractalx.logger;")
        c.contains("@SpringBootApplication")
        c.contains("public class LoggerApplication")
        c.contains("SpringApplication.run(LoggerApplication.class, args)")
    }

    // -------------------------------------------------------------------------
    // LogEntry.java
    // -------------------------------------------------------------------------

    def "LogEntry has correlationId field (renamed from traceId)"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        def c = logEntry()
        c.contains("package org.fractalx.logger;")
        c.contains("public class LogEntry")
        c.contains("correlationId")
    }

    def "LogEntry has spanId and parentSpanId for distributed trace linking"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        def c = logEntry()
        c.contains("spanId")
        c.contains("parentSpanId")
    }

    def "LogEntry has Instant timestamp field"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        def c = logEntry()
        c.contains("Instant timestamp")
        c.contains("java.time.Instant")
    }

    def "LogEntry has service, level, message, and receivedAt fields"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        def c = logEntry()
        c.contains("String  service")
        c.contains("String  level")
        c.contains("String  message")
        c.contains("receivedAt")
    }

    def "LogEntry does NOT use legacy traceId field"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        !logEntry().contains("traceId")
    }

    def "LogEntry has getters and setters for all fields"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        def c = logEntry()
        c.contains("getCorrelationId()")
        c.contains("setCorrelationId(")
        c.contains("getService()")
        c.contains("getTimestamp()")
    }

    // -------------------------------------------------------------------------
    // LogRepository.java
    // -------------------------------------------------------------------------

    def "LogRepository is a @Component with CopyOnWriteArrayList storage"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        def c = logRepository()
        c.contains("@Component")
        c.contains("public class LogRepository")
        c.contains("CopyOnWriteArrayList")
    }

    def "LogRepository defines MAX_SIZE of 5000 entries"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        logRepository().contains("MAX_SIZE = 5_000")
    }

    def "LogRepository evicts oldest entry when buffer is full"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        def c = logRepository()
        c.contains("storage.size() >= MAX_SIZE")
        c.contains("storage.remove(0)")
    }

    def "LogRepository exposes save, findAll(page, size), and query methods"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        def c = logRepository()
        c.contains("void save(LogEntry entry)")
        c.contains("List<LogEntry> findAll(int page, int size)")
        c.contains("List<LogEntry> query(")
    }

    def "LogRepository query filters by correlationId, service, and level"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        def c = logRepository()
        c.contains("findByCorrelationId")
        c.contains("findByService")
        c.contains("findByLevel")
    }

    def "LogRepository exposes findDistinctServices returning sorted unique names"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        def c = logRepository()
        c.contains("findDistinctServices()")
        c.contains(".distinct()")
        c.contains(".sorted()")
    }

    def "LogRepository exposes stats() returning per-service total and error counts"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        def c = logRepository()
        c.contains("stats()")
        c.contains("Map<String, Map<String, Long>>")
        c.contains("\"total\"")
        c.contains("\"errors\"")
    }

    // -------------------------------------------------------------------------
    // LogController.java
    // -------------------------------------------------------------------------

    def "LogController is @RestController mapped to /api/logs with @CrossOrigin"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        def c = logController()
        c.contains("@RestController")
        c.contains("@RequestMapping(\"/api/logs\")")
        c.contains("@CrossOrigin")
    }

    def "LogController POST /api/logs ingests a log entry and returns 202 Accepted"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        def c = logController()
        c.contains("@PostMapping")
        c.contains("@RequestBody LogEntry entry")
        c.contains("ResponseEntity.accepted()")
    }

    def "LogController sets receivedAt timestamp if not provided by the caller"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        def c = logController()
        c.contains("getReceivedAt() == null")
        c.contains("Instant.now().toString()")
    }

    def "LogController GET /api/logs accepts correlationId, service, level, page, size params"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        def c = logController()
        c.contains("String correlationId")
        c.contains("String service")
        c.contains("String level")
        c.contains("int page")
        c.contains("int size")
    }

    def "LogController caps page size at 200 for safety"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        logController().contains("Math.min(size, 200)")
    }

    def "LogController GET /api/logs/services returns distinct service names"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        def c = logController()
        c.contains("\"/services\"")
        c.contains("findDistinctServices()")
    }

    def "LogController GET /api/logs/stats returns per-service totals and error counts"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        def c = logController()
        c.contains("\"/stats\"")
        c.contains("stats()")
        c.contains("Map<String, Map<String, Long>>")
    }

    // -------------------------------------------------------------------------
    // application.yml
    // -------------------------------------------------------------------------

    def "application.yml sets port to 9099 and application name to logger-service"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        def c = config()
        c.contains("port: 9099")
        c.contains("name: logger-service")
    }

    def "application.yml exposes health and info actuator endpoints"() {
        when:
        generator.generate(outputRoot, FractalxConfig.defaults())

        then:
        config().contains("include: health,info")
    }
}
