package org.fractalx.core.verifier

import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class SmokeTestGeneratorSpec extends Specification {

    @TempDir Path outputDir

    SmokeTestGenerator gen = new SmokeTestGenerator()

    private FractalModule mod(String name, String pkg) {
        FractalModule.builder()
            .serviceName(name)
            .packageName(pkg)
            .port(8081)
            .build()
    }

    def "result is failure when service directory does not exist"() {
        given:
        def modules = [mod("order-service", "com.example.order")]

        when:
        def results = gen.generate(outputDir, modules)

        then:
        results.size() == 1
        !results[0].success()
        results[0].serviceName() == "order-service"
    }

    def "generates FractalXSmokeTest.java when service directory exists"() {
        given:
        def svcDir = outputDir.resolve("order-service")
        Files.createDirectories(svcDir)
        def modules = [mod("order-service", "com.example.order")]

        when:
        def results = gen.generate(outputDir, modules)

        then:
        results[0].success()
        // Find the generated test file
        def testFiles = []
        Files.walk(svcDir).filter { it.toString().endsWith("FractalXSmokeTest.java") }
            .forEach { testFiles << it }
        !testFiles.isEmpty()
    }

    def "generated test contains @SpringBootTest"() {
        given:
        def svcDir = outputDir.resolve("order-service")
        Files.createDirectories(svcDir)
        def modules = [mod("order-service", "com.example.order")]

        when:
        gen.generate(outputDir, modules)

        then:
        def testFiles = []
        Files.walk(svcDir).filter { it.toString().endsWith("FractalXSmokeTest.java") }
            .forEach { testFiles << it }
        def content = Files.readString(testFiles[0])
        content.contains("@SpringBootTest")
    }

    def "generate with custom basePackage uses that package"() {
        given:
        def svcDir = outputDir.resolve("order-service")
        Files.createDirectories(svcDir)
        def modules = [mod("order-service", "com.acme.order")]

        when:
        def results = gen.generate(outputDir, modules, "com.acme.generated")

        then:
        results[0].success()
    }

    def "multiple modules each get their own smoke test result"() {
        given:
        ["order-service", "payment-service"].each {
            Files.createDirectories(outputDir.resolve(it))
        }
        def modules = [
            mod("order-service",   "com.example.order"),
            mod("payment-service", "com.example.payment")
        ]

        when:
        def results = gen.generate(outputDir, modules)

        then:
        results.size() == 2
    }

    def "GenerationResult.toString()-equivalent fields are accessible"() {
        given:
        def modules = [mod("order-service", "com.example.order")]

        when:
        def results = gen.generate(outputDir, modules)

        then:
        results[0].serviceName() != null
        results[0].detail() != null
    }
}
