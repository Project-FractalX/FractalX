package org.fractalx.core.datamanagement

import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies that OutboxGenerator produces all four transactional outbox files
 * with correct annotations, method signatures, and structural content.
 *
 * Files expected under src/main/java/org/fractalx/generated/&lt;service&gt;/outbox/:
 *   - OutboxEvent.java      — @Entity, table = fractalx_outbox
 *   - OutboxRepository.java — JpaRepository with findByPublishedFalse* methods
 *   - OutboxPublisher.java  — @Component with publish(eventType, aggregateId, payload)
 *   - OutboxPoller.java     — @Scheduled component with MAX_RETRIES
 */
class OutboxGeneratorSpec extends Specification {

    @TempDir
    Path serviceRoot

    OutboxGenerator generator = new OutboxGenerator()

    FractalModule module = FractalModule.builder()
        .serviceName("order-service")
        .packageName("org.fractalx.test.order")
        .port(8081)
        .dependencies(["PaymentService"])
        .build()

    // ── helpers ──────────────────────────────────────────────────────────────

    private Path outboxDir() {
        serviceRoot.resolve(
            "src/main/java/org/fractalx/generated/orderservice/outbox")
    }

    private String read(String filename) {
        Files.readString(outboxDir().resolve(filename))
    }

    // ── feature methods ───────────────────────────────────────────────────────

    def "all four outbox files are generated"() {
        when:
        generator.generateOutbox(module, serviceRoot, "org.fractalx.generated.orderservice")

        then:
        ["OutboxEvent.java", "OutboxRepository.java",
         "OutboxPublisher.java", "OutboxPoller.java"].every { filename ->
            Files.exists(outboxDir().resolve(filename))
        }
    }

    def "OutboxEvent.java is annotated with @Entity and maps to fractalx_outbox table"() {
        when:
        generator.generateOutbox(module, serviceRoot, "org.fractalx.generated.orderservice")

        then:
        def content = read("OutboxEvent.java")
        content.contains("@Entity")
        content.contains("fractalx_outbox")
    }

    def "OutboxEvent.java declares all required fields"() {
        when:
        generator.generateOutbox(module, serviceRoot, "org.fractalx.generated.orderservice")

        then:
        def content = read("OutboxEvent.java")
        content.contains("eventType")
        content.contains("aggregateId")
        content.contains("payload")
        content.contains("published")
        content.contains("retryCount")
        content.contains("createdAt")
    }

    def "OutboxRepository.java declares the findByPublishedFalseOrderByCreatedAtAsc method"() {
        when:
        generator.generateOutbox(module, serviceRoot, "org.fractalx.generated.orderservice")

        then:
        def content = read("OutboxRepository.java")
        content.contains("findByPublishedFalseOrderByCreatedAtAsc")
        content.contains("extends JpaRepository")
    }

    def "OutboxRepository.java has a method for querying failed events by retry count"() {
        when:
        generator.generateOutbox(module, serviceRoot, "org.fractalx.generated.orderservice")

        then:
        read("OutboxRepository.java").contains("findByPublishedFalseAndRetryCountGreaterThan")
    }

    def "OutboxPublisher.java is a @Component with a publish method"() {
        when:
        generator.generateOutbox(module, serviceRoot, "org.fractalx.generated.orderservice")

        then:
        def content = read("OutboxPublisher.java")
        content.contains("@Component")
        content.contains("publish(String eventType")
    }

    def "OutboxPoller.java is a @Component with @Scheduled polling"() {
        when:
        generator.generateOutbox(module, serviceRoot, "org.fractalx.generated.orderservice")

        then:
        def content = read("OutboxPoller.java")
        content.contains("@Component")
        content.contains("@Scheduled")
        content.contains("publishPending")
    }

    def "OutboxPoller.java defines MAX_RETRIES constant"() {
        when:
        generator.generateOutbox(module, serviceRoot, "org.fractalx.generated.orderservice")

        then:
        read("OutboxPoller.java").contains("MAX_RETRIES")
    }

    def "OutboxPoller.java includes the originating service name in its documentation"() {
        when:
        generator.generateOutbox(module, serviceRoot, "org.fractalx.generated.orderservice")

        then: "the service name appears in the generated Javadoc"
        read("OutboxPoller.java").contains("order-service")
    }

    def "generated files live under the correct package namespace"() {
        when:
        generator.generateOutbox(module, serviceRoot, "org.fractalx.generated.orderservice")

        then:
        def content = read("OutboxEvent.java")
        content.startsWith("package org.fractalx.generated.orderservice.outbox")
    }
}
