package org.fractalx.core.generator.service

import org.fractalx.core.config.FractalxConfig
import org.fractalx.core.generator.GenerationContext
import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class ApplicationGeneratorSpec extends Specification {

    @TempDir Path serviceRoot

    ApplicationGenerator gen = new ApplicationGenerator()

    FractalxConfig cfg = FractalxConfig.defaults().withBasePackage("com.example.generated")

    private GenerationContext ctx(FractalModule module) {
        new GenerationContext(module, serviceRoot, serviceRoot, [module], cfg)
    }

    private FractalModule mod(String name, String pkg, int port, List<String> deps = []) {
        FractalModule.builder()
            .serviceName(name)
            .packageName(pkg)
            .port(port)
            .dependencies(deps)
            .build()
    }

    def "generates Application class file in the correct package directory"() {
        given:
        def module = mod("order-service", "com.example.order", 8081)

        when:
        gen.generate(ctx(module))

        then:
        // Package: com.example.generated.orderservice
        // Class name: OrderServiceApplication
        def expected = serviceRoot.resolve("src/main/java/com/example/generated/orderservice/OrderServiceApplication.java")
        Files.exists(expected)
    }

    def "application class contains @SpringBootApplication"() {
        given:
        def module = mod("order-service", "com.example.order", 8081)

        when:
        gen.generate(ctx(module))

        then:
        def content = Files.readString(serviceRoot.resolve(
            "src/main/java/com/example/generated/orderservice/OrderServiceApplication.java"))
        content.contains("@SpringBootApplication")
    }

    def "application class contains correct port in waitForPort"() {
        given:
        def module = mod("order-service", "com.example.order", 8081)

        when:
        gen.generate(ctx(module))

        then:
        def content = Files.readString(serviceRoot.resolve(
            "src/main/java/com/example/generated/orderservice/OrderServiceApplication.java"))
        content.contains("8081")
    }

    def "application class has @EnableNetScopeClient when module has dependencies"() {
        given:
        def module = mod("payment-service", "com.example.payment", 8082, ["OrderService"])

        when:
        gen.generate(ctx(module))

        then:
        def content = Files.readString(serviceRoot.resolve(
            "src/main/java/com/example/generated/paymentservice/PaymentServiceApplication.java"))
        content.contains("@EnableNetScopeClient")
        content.contains("EnableNetScopeClient")
    }

    def "application class has no @EnableNetScopeClient when module has no dependencies"() {
        given:
        def module = mod("order-service", "com.example.order", 8081)

        when:
        gen.generate(ctx(module))

        then:
        def content = Files.readString(serviceRoot.resolve(
            "src/main/java/com/example/generated/orderservice/OrderServiceApplication.java"))
        !content.contains("@EnableNetScopeClient")
    }

    def "application class name uses PascalCase from kebab-case service name"() {
        given:
        def module = mod("my-special-service", "com.example.special", 8083)

        when:
        gen.generate(ctx(module))

        then:
        Files.exists(serviceRoot.resolve(
            "src/main/java/com/example/generated/myspecialservice/MySpecialServiceApplication.java"))
    }

    def "application class contains @EnableNetScopeServer"() {
        given:
        def module = mod("order-service", "com.example.order", 8081)

        when:
        gen.generate(ctx(module))

        then:
        def content = Files.readString(serviceRoot.resolve(
            "src/main/java/com/example/generated/orderservice/OrderServiceApplication.java"))
        content.contains("@EnableNetScopeServer")
    }

    def "application class contains @ComponentScan"() {
        given:
        def module = mod("order-service", "com.example.order", 8081)

        when:
        gen.generate(ctx(module))

        then:
        def content = Files.readString(serviceRoot.resolve(
            "src/main/java/com/example/generated/orderservice/OrderServiceApplication.java"))
        content.contains("@ComponentScan")
    }
}
