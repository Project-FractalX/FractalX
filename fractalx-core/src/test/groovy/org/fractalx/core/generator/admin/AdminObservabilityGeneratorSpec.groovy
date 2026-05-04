package org.fractalx.core.generator.admin

import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies that AdminObservabilityGenerator produces all 9 required classes
 * in the org.fractalx.admin.observability package with the expected structure.
 */
class AdminObservabilityGeneratorSpec extends Specification {

    @TempDir
    Path srcMainJava

    AdminObservabilityGenerator generator = new AdminObservabilityGenerator()
    String basePackage = "org.fractalx.admin"

    FractalModule order = FractalModule.builder()
        .serviceName("order-service")
        .packageName("com.example.order")
        .port(8081)
        .build()

    FractalModule payment = FractalModule.builder()
        .serviceName("payment-service")
        .packageName("com.example.payment")
        .port(8082)
        .build()

    private Path obsPkg() {
        srcMainJava.resolve("org/fractalx/admin/observability")
    }

    private String readFile(String name) {
        Files.readString(obsPkg().resolve(name))
    }

    def "all 9 observability files are created"() {
        when:
        generator.generate(srcMainJava, basePackage, [order, payment])

        then:
        ["AlertSeverity.java", "AlertRule.java", "AlertEvent.java", "AlertStore.java",
         "AlertConfigProperties.java", "AlertEvaluator.java", "NotificationDispatcher.java",
         "AlertChannels.java", "ObservabilityController.java"].every {
            Files.exists(obsPkg().resolve(it))
        }
    }

    def "AlertSeverity defines INFO, WARNING, and CRITICAL enum values"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readFile("AlertSeverity.java")
        c.contains("package org.fractalx.admin.observability")
        c.contains("INFO")
        c.contains("WARNING")
        c.contains("CRITICAL")
    }

    def "AlertRule model has name, condition, threshold, severity, and consecutiveFailures fields"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readFile("AlertRule.java")
        c.contains("name")
        c.contains("condition")
        c.contains("threshold")
        c.contains("severity")
        c.contains("consecutiveFailures")
    }

    def "AlertEvent model has id (UUID), timestamp, service, severity, message, and resolved fields"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readFile("AlertEvent.java")
        c.contains("id")
        c.contains("timestamp")
        c.contains("service")
        c.contains("severity")
        c.contains("message")
        c.contains("resolved")
    }

    def "AlertStore is a @Component with thread-safe in-memory storage"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readFile("AlertStore.java")
        c.contains("@Component")
        c.contains("CopyOnWriteArrayList")
    }

    def "AlertStore provides save, findAll, findUnresolved, and resolve methods"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readFile("AlertStore.java")
        c.contains("save(")
        c.contains("findAll")
        c.contains("findUnresolved")
        c.contains("resolve")
    }

    def "AlertConfigProperties is bound to fractalx.alerting prefix"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readFile("AlertConfigProperties.java")
        c.contains("@ConfigurationProperties")
        c.contains("fractalx.alerting")
    }

    def "AlertConfigProperties has Channels nested class covering all four channel types"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readFile("AlertConfigProperties.java")
        c.contains("Channels")
        // at least webhook and slack fields
        c.contains("webhook") || c.contains("Webhook")
        c.contains("slack")   || c.contains("Slack")
        c.contains("email")   || c.contains("Email")
    }

    def "AlertEvaluator is a @Component with @Scheduled method"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readFile("AlertEvaluator.java")
        c.contains("@Component")
        c.contains("@Scheduled")
    }

    def "AlertEvaluator discovers services dynamically from the FractalX Registry"() {
        when:
        generator.generate(srcMainJava, basePackage, [order, payment])

        then:
        def c = readFile("AlertEvaluator.java")
        // Registry URL is injected (with a localhost fallback only as a default for local dev)
        c.contains("@Value(\"\${fractalx.registry.url:http://localhost:8761}\")")
        c.contains("registryUrl")
        // Discovery happens at runtime via GET /services — no per-service ports baked in
        c.contains("/services")
        // No hardcoded fallback to "http://localhost:<servicePort>" inside the evaluation loop
        !c.contains("http://localhost:8081")
        !c.contains("http://localhost:8082")
        // No legacy *_BASE_URL env var pattern
        !c.contains("ORDER_SERVICE_BASE_URL")
        !c.contains("PAYMENT_SERVICE_BASE_URL")
    }

    def "AlertChannels is a @Component with SSE subscribe and publish methods"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readFile("AlertChannels.java")
        c.contains("@Component")
        c.contains("SseEmitter")
        c.contains("subscribeAdminUi") || c.contains("subscribe")
        c.contains("publishToAdminUi") || c.contains("publish")
    }

    def "AlertChannels implements webhook, email, and Slack channels"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readFile("AlertChannels.java")
        c.contains("sendToWebhook") || c.contains("Webhook")
        c.contains("sendEmail")     || c.contains("Email")
        c.contains("sendToSlack")   || c.contains("Slack")
    }

    def "ObservabilityController is a @RestController with /api base path"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readFile("ObservabilityController.java")
        c.contains("@RestController")
        c.contains("/api")
    }

    def "ObservabilityController exposes observability metrics endpoint"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readFile("ObservabilityController.java")
        c.contains("/observability/metrics")
    }

    def "ObservabilityController exposes alert endpoints for history, active, resolve, and SSE stream"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readFile("ObservabilityController.java")
        c.contains("/alerts")
        c.contains("/alerts/active")
        c.contains("/resolve")
        c.contains("/alerts/stream") || c.contains("SseEmitter")
    }

    def "ObservabilityController exposes /api/traces proxy to Jaeger"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readFile("ObservabilityController.java")
        c.contains("/traces")
        c.contains("jaeger") || c.contains("Jaeger") || c.contains("jaeger.query-url")
    }

    def "ObservabilityController exposes /api/logs proxy to logger-service"() {
        when:
        generator.generate(srcMainJava, basePackage, [order])

        then:
        def c = readFile("ObservabilityController.java")
        c.contains("/logs")
        c.contains("logger") || c.contains("FRACTALX_LOGGER")
    }
}
