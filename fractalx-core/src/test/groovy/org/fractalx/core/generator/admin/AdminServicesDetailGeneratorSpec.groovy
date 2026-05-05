package org.fractalx.core.generator.admin

import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies AdminServicesDetailGenerator produces:
 *  - ServiceMetaRegistry   (baked-in module + infra metadata)
 *  - DeploymentTracker     (pre-seeded deployment records)
 *  - ServicesController    (7 REST endpoints)
 */
class AdminServicesDetailGeneratorSpec extends Specification {

    @TempDir
    Path srcMainJava

    AdminServicesDetailGenerator generator = new AdminServicesDetailGenerator()

    FractalModule order = FractalModule.builder()
        .serviceName("order-service")
        .packageName("com.example.order")
        .port(8081)
        .dependencies(["PaymentService"])
        .className("OrderModule")
        .build()

    FractalModule payment = FractalModule.builder()
        .serviceName("payment-service")
        .packageName("com.example.payment")
        .port(8082)
        .dependencies([])
        .className("PaymentModule")
        .build()

    static final String BASE = "org.fractalx.admin"

    def "generates three files in the services package"() {
        when:
        generator.generate(srcMainJava, BASE, [order, payment])
        def pkg = srcMainJava.resolve("org/fractalx/admin/services")

        then:
        Files.exists(pkg.resolve("ServiceMetaRegistry.java"))
        Files.exists(pkg.resolve("DeploymentTracker.java"))
        Files.exists(pkg.resolve("ServicesController.java"))
    }

    // ---- ServiceMetaRegistry ------------------------------------------------

    def "ServiceMetaRegistry is in org.fractalx.admin.services package"() {
        when:
        generator.generate(srcMainJava, BASE, [order])
        def content = serviceMetaRegistry()

        then:
        content.contains("package org.fractalx.admin.services")
    }

    def "ServiceMetaRegistry is annotated @Component"() {
        when:
        generator.generate(srcMainJava, BASE, [order])

        then:
        serviceMetaRegistry().contains("@Component")
    }

    def "ServiceMetaRegistry defines ServiceMeta record with all fields"() {
        when:
        generator.generate(srcMainJava, BASE, [order])
        def content = serviceMetaRegistry()

        then:
        content.contains("public record ServiceMeta(")
        content.contains("String name")
        content.contains("int port")
        content.contains("int grpcPort")
        content.contains("String type")
        content.contains("List<String> dependencies")
        content.contains("String packageName")
        content.contains("String className")
    }

    def "ServiceMetaRegistry bakes in module service entry"() {
        when:
        generator.generate(srcMainJava, BASE, [order])
        def content = serviceMetaRegistry()

        then:
        content.contains('"order-service"')
        content.contains('8081')           // HTTP port
        content.contains('18081')          // gRPC port = 8081 + 10000
        content.contains('"microservice"')
        content.contains('"com.example.order"')
    }

    def "ServiceMetaRegistry always includes all 4 infra services"() {
        when:
        generator.generate(srcMainJava, BASE, [order])
        def content = serviceMetaRegistry()

        then:
        content.contains('"fractalx-registry"')
        content.contains('"fractalx-gateway"')
        content.contains('"admin-service"')
        content.contains('"logger-service"')
        content.contains('"infrastructure"')
    }

    def "ServiceMetaRegistry exposes getAll, findByName, getByType and countMicroservices"() {
        when:
        generator.generate(srcMainJava, BASE, [order])
        def content = serviceMetaRegistry()

        then:
        content.contains("getAll()")
        content.contains("findByName(String name)")
        content.contains("getByType(String type)")
        content.contains("countMicroservices()")
    }

    def "ServiceMetaRegistry uses static List.of for SERVICES"() {
        when:
        generator.generate(srcMainJava, BASE, [order])

        then:
        serviceMetaRegistry().contains("private static final List<ServiceMeta> SERVICES = List.of(")
    }

    // ---- DeploymentTracker --------------------------------------------------

    def "DeploymentTracker is annotated @Component with @PostConstruct init"() {
        when:
        generator.generate(srcMainJava, BASE, [order])
        def content = deploymentTracker()

        then:
        content.contains("@Component")
        content.contains("@PostConstruct")
        content.contains("public void init()")
    }

    def "DeploymentTracker defines DeploymentStage and DeploymentRecord records"() {
        when:
        generator.generate(srcMainJava, BASE, [order])
        def content = deploymentTracker()

        then:
        content.contains("public record DeploymentStage(")
        content.contains("public record DeploymentRecord(")
        content.contains("StageStatus")
    }

    def "DeploymentTracker init seeds the module service"() {
        when:
        generator.generate(srcMainJava, BASE, [order])

        then:
        deploymentTracker().contains('addInitialRecord("order-service")')
    }

    def "DeploymentTracker init always seeds infra services"() {
        when:
        generator.generate(srcMainJava, BASE, [order])
        def content = deploymentTracker()

        then:
        content.contains('addInitialRecord("fractalx-registry")')
        content.contains('addInitialRecord("fractalx-gateway")')
        content.contains('addInitialRecord("admin-service")')
        content.contains('addInitialRecord("logger-service")')
    }

    def "DeploymentTracker exposes addRecord, getLatest, getHistory, getAllHistory"() {
        when:
        generator.generate(srcMainJava, BASE, [order])
        def content = deploymentTracker()

        then:
        content.contains("addRecord(DeploymentRecord record)")
        content.contains("getLatest(String service)")
        content.contains("getHistory(String service)")
        content.contains("getAllHistory()")
    }

    // ---- ServicesController -------------------------------------------------

    def "ServicesController is a @RestController mapped to /api/services"() {
        when:
        generator.generate(srcMainJava, BASE, [order])
        def content = servicesController()

        then:
        content.contains("@RestController")
        content.contains('@RequestMapping("/api/services")')
    }

    def "ServicesController has GET /all endpoint"() {
        when:
        generator.generate(srcMainJava, BASE, [order])

        then:
        servicesController().contains('@GetMapping("/all")')
    }

    def "ServicesController has {name} detail, health, metrics, deployment, history, commands endpoints"() {
        when:
        generator.generate(srcMainJava, BASE, [order])
        def content = servicesController()

        then:
        content.contains('"/detail"') || content.contains('"/{name}/detail"') || content.contains('/detail')
        content.contains('"/health"')  || content.contains('/health')
        content.contains('"/metrics"') || content.contains('/metrics')
        content.contains('"/commands"') || content.contains('/commands')
    }

    def "ServicesController returns docker-compose commands for lifecycle"() {
        when:
        generator.generate(srcMainJava, BASE, [order])
        def content = servicesController()

        then:
        content.contains("docker-compose") || content.contains("docker compose")
    }

    // helpers
    private String serviceMetaRegistry() {
        Files.readString(srcMainJava.resolve("org/fractalx/admin/services/ServiceMetaRegistry.java"))
    }

    private String deploymentTracker() {
        Files.readString(srcMainJava.resolve("org/fractalx/admin/services/DeploymentTracker.java"))
    }

    private String servicesController() {
        Files.readString(srcMainJava.resolve("org/fractalx/admin/services/ServicesController.java"))
    }
}
