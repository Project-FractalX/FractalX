package org.fractalx.core.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates a standalone centralized log aggregation service ({@code logger-service}).
 * Services ship logs via {@code FractalLogAppender}; this service stores them in a
 * thread-safe in-memory ring buffer (5000 entries) and exposes a REST query API.
 *
 * <p>Logs are indexed by {@code correlationId} for cross-service trace correlation.
 */
public class LoggerServiceGenerator {

    private static final Logger log = LoggerFactory.getLogger(LoggerServiceGenerator.class);
    static final String SERVICE_NAME = "logger-service";
    static final int    PORT         = 9099;

    public void generate(Path outputRoot, org.fractalx.core.config.FractalxConfig config) throws IOException {
        log.info("Generating Centralized Logger Service...");

        Path serviceRoot      = outputRoot.resolve(SERVICE_NAME);
        Path srcMainJava      = serviceRoot.resolve("src/main/java/org/fractalx/logger");
        Path srcMainResources = serviceRoot.resolve("src/main/resources");

        Files.createDirectories(srcMainJava);
        Files.createDirectories(srcMainResources);

        generatePom(serviceRoot, config);
        generateApplication(srcMainJava);
        generateModel(srcMainJava);
        generateRepository(srcMainJava);
        generateController(srcMainJava);
        generateConfig(srcMainResources);

        log.info("Generated Logger Service on port {}", PORT);
    }

    // -------------------------------------------------------------------------

    private void generatePom(Path root, org.fractalx.core.config.FractalxConfig config) throws IOException {
        String content = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <groupId>%s</groupId>
                    <artifactId>logger-service</artifactId>
                    <version>%s</version>

                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>%s</version>
                    </parent>

                    <properties>
                        <java.version>%s</java.version>
                    </properties>

                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-actuator</artifactId>
                        </dependency>
                    </dependencies>

                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """;
        Files.writeString(root.resolve("pom.xml"),
                content.formatted(config.effectiveBasePackage(), config.initialServiceVersion(), config.springBootVersion(), config.javaVersion()));
    }

    private void generateConfig(Path resources) throws IOException {
        String content = """
                server:
                  port: %d
                spring:
                  application:
                    name: logger-service
                management:
                  endpoints:
                    web:
                      exposure:
                        include: health,info
                """.formatted(PORT);
        Files.writeString(resources.resolve("application.yml"), content);
    }

    private void generateApplication(Path javaPath) throws IOException {
        String content = """
                package org.fractalx.logger;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class LoggerApplication {
                    public static void main(String[] args) {
                        SpringApplication.run(LoggerApplication.class, args);
                    }
                }
                """;
        Files.writeString(javaPath.resolve("LoggerApplication.java"), content);
    }

    private void generateModel(Path javaPath) throws IOException {
        String content = """
                package org.fractalx.logger;

                import java.time.Instant;

                /** Structured log entry shipped from a microservice via FractalLogAppender. */
                public class LogEntry {

                    private String  service;
                    private String  correlationId;
                    private String  spanId;
                    private String  parentSpanId;
                    private String  level;
                    private String  message;
                    private Instant timestamp;
                    private String  receivedAt;

                    public String  getService()       { return service; }
                    public void    setService(String s) { this.service = s; }

                    public String  getCorrelationId()           { return correlationId; }
                    public void    setCorrelationId(String c)   { this.correlationId = c; }

                    public String  getSpanId()                  { return spanId; }
                    public void    setSpanId(String s)          { this.spanId = s; }

                    public String  getParentSpanId()            { return parentSpanId; }
                    public void    setParentSpanId(String p)    { this.parentSpanId = p; }

                    public String  getLevel()                   { return level; }
                    public void    setLevel(String l)           { this.level = l; }

                    public String  getMessage()                 { return message; }
                    public void    setMessage(String m)         { this.message = m; }

                    public Instant getTimestamp()               { return timestamp; }
                    public void    setTimestamp(Instant t)      { this.timestamp = t; }

                    public String  getReceivedAt()              { return receivedAt; }
                    public void    setReceivedAt(String t)      { this.receivedAt = t; }
                }
                """;
        Files.writeString(javaPath.resolve("LogEntry.java"), content);
    }

    private void generateRepository(Path javaPath) throws IOException {
        String content = """
                package org.fractalx.logger;

                import org.springframework.stereotype.Component;

                import java.util.ArrayList;
                import java.util.List;
                import java.util.Map;
                import java.util.concurrent.CopyOnWriteArrayList;
                import java.util.stream.Collectors;

                /**
                 * Thread-safe in-memory log store with a rolling 5000-entry buffer.
                 * Indexed by correlationId and service name for efficient querying.
                 */
                @Component
                public class LogRepository {

                    static final int MAX_SIZE = 5_000;

                    private final List<LogEntry> storage = new CopyOnWriteArrayList<>();

                    public synchronized void save(LogEntry entry) {
                        if (storage.size() >= MAX_SIZE) {
                            storage.remove(0);
                        }
                        storage.add(entry);
                    }

                    public List<LogEntry> findAll(int page, int size) {
                        List<LogEntry> all = new ArrayList<>(storage);
                        int from = Math.min(page * size, all.size());
                        int to   = Math.min(from + size, all.size());
                        return all.subList(from, to);
                    }

                    public List<LogEntry> findByCorrelationId(String correlationId) {
                        return storage.stream()
                                .filter(e -> correlationId.equals(e.getCorrelationId()))
                                .collect(Collectors.toList());
                    }

                    public List<LogEntry> findByService(String service) {
                        return storage.stream()
                                .filter(e -> service.equals(e.getService()))
                                .collect(Collectors.toList());
                    }

                    public List<LogEntry> findByLevel(String level) {
                        return storage.stream()
                                .filter(e -> level.equalsIgnoreCase(e.getLevel()))
                                .collect(Collectors.toList());
                    }

                    public List<LogEntry> query(String correlationId, String service,
                                                String level, int page, int size) {
                        return storage.stream()
                                .filter(e -> correlationId == null || correlationId.equals(e.getCorrelationId()))
                                .filter(e -> service       == null || service.equals(e.getService()))
                                .filter(e -> level         == null || level.equalsIgnoreCase(e.getLevel()))
                                .skip((long) page * size)
                                .limit(size)
                                .collect(Collectors.toList());
                    }

                    public List<String> findDistinctServices() {
                        return storage.stream()
                                .map(LogEntry::getService)
                                .filter(s -> s != null && !s.isBlank())
                                .distinct()
                                .sorted()
                                .collect(Collectors.toList());
                    }

                    /** Per-service log counts and ERROR counts for the stats endpoint. */
                    public Map<String, Map<String, Long>> stats() {
                        return storage.stream()
                                .filter(e -> e.getService() != null)
                                .collect(Collectors.groupingBy(
                                        LogEntry::getService,
                                        Collectors.collectingAndThen(
                                                Collectors.toList(),
                                                list -> Map.of(
                                                        "total",  (long) list.size(),
                                                        "errors", list.stream()
                                                                .filter(e -> "ERROR".equalsIgnoreCase(e.getLevel()))
                                                                .count()
                                                )
                                        )
                                ));
                    }
                }
                """;
        Files.writeString(javaPath.resolve("LogRepository.java"), content);
    }

    private void generateController(Path javaPath) throws IOException {
        String content = """
                package org.fractalx.logger;

                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.*;

                import java.time.Instant;
                import java.util.List;
                import java.util.Map;

                /**
                 * REST API for the centralized log aggregation service.
                 *
                 * <pre>
                 * POST /api/logs                          — ingest a log entry
                 * GET  /api/logs                          — query logs (paged, filterable)
                 * GET  /api/logs/services                 — list known service names
                 * GET  /api/logs/stats                    — per-service totals and error counts
                 * </pre>
                 */
                @RestController
                @RequestMapping("/api/logs")
                @CrossOrigin(origins = "*")
                public class LogController {

                    private final LogRepository repository;

                    public LogController(LogRepository repository) {
                        this.repository = repository;
                    }

                    /** Ingest a structured log entry from a microservice. */
                    @PostMapping
                    public ResponseEntity<Void> ingestLog(@RequestBody LogEntry entry) {
                        if (entry.getReceivedAt() == null) {
                            entry.setReceivedAt(Instant.now().toString());
                        }
                        repository.save(entry);
                        return ResponseEntity.accepted().build();
                    }

                    /**
                     * Query logs with optional filters.
                     *
                     * @param correlationId filter by correlation ID
                     * @param service       filter by service name
                     * @param level         filter by log level (INFO, WARN, ERROR, …)
                     * @param page          zero-based page number (default 0)
                     * @param size          page size (default 50, max 200)
                     */
                    @GetMapping
                    public ResponseEntity<List<LogEntry>> getLogs(
                            @RequestParam(required = false) String correlationId,
                            @RequestParam(required = false) String service,
                            @RequestParam(required = false) String level,
                            @RequestParam(defaultValue = "0")  int page,
                            @RequestParam(defaultValue = "50") int size) {
                        int safeSize = Math.min(size, 200);
                        return ResponseEntity.ok(repository.query(correlationId, service, level, page, safeSize));
                    }

                    /** List all service names that have shipped at least one log entry. */
                    @GetMapping("/services")
                    public ResponseEntity<List<String>> getServices() {
                        return ResponseEntity.ok(repository.findDistinctServices());
                    }

                    /** Per-service log totals and error counts. */
                    @GetMapping("/stats")
                    public ResponseEntity<Map<String, Map<String, Long>>> getStats() {
                        return ResponseEntity.ok(repository.stats());
                    }
                }
                """;
        Files.writeString(javaPath.resolve("LogController.java"), content);
    }
}
