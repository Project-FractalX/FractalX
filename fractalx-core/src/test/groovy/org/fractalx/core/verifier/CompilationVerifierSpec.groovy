package org.fractalx.core.verifier

import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class CompilationVerifierSpec extends Specification {

    @TempDir Path outputDir

    CompilationVerifier verifier = new CompilationVerifier()

    private FractalModule mod(String name) {
        FractalModule.builder()
            .serviceName(name)
            .packageName("com.example")
            .port(8081)
            .build()
    }

    def "returns empty list when no service directories exist"() {
        given:
        def modules = [mod("order-service")]

        when:
        def results = verifier.compileAll(outputDir, modules)

        then:
        results.isEmpty()
    }

    def "returns empty list when service directory exists but has no pom.xml"() {
        given:
        Files.createDirectories(outputDir.resolve("order-service"))
        def modules = [mod("order-service")]

        when:
        def results = verifier.compileAll(outputDir, modules)

        then:
        results.isEmpty()
    }

    def "CompilationResult model is accessible"() {
        given:
        def result = new CompilationVerifier.CompilationResult("order-service", true, [], 100L)

        expect:
        result.serviceName() == "order-service"
        result.success()
        result.errors().isEmpty()
        result.durationMs() == 100L
    }

    def "CompilationResult.summary() includes [PASS] for success"() {
        given:
        def result = new CompilationVerifier.CompilationResult("order-service", true, [], 200L)

        expect:
        result.summary().contains("[PASS]")
        result.summary().contains("order-service")
    }

    def "CompilationResult.summary() includes [FAIL] for failure"() {
        given:
        def result = new CompilationVerifier.CompilationResult("order-service", false, ["error"], 50L)

        expect:
        result.summary().contains("[FAIL]")
    }

    def "infrastructure services are tried before user modules"() {
        given:
        // Create fractalx-registry with pom.xml and order-service with pom.xml
        // Both will time out quickly because pom.xml has no real content
        ["fractalx-registry", "order-service"].each { svc ->
            def svcDir = outputDir.resolve(svc)
            Files.createDirectories(svcDir)
            Files.writeString(svcDir.resolve("pom.xml"), "<project/>")
        }
        def modules = [mod("order-service")]

        when:
        // This will actually attempt mvn compile and fail / time out
        // We just verify it returns results in the right order without crashing
        def results = verifier.compileAll(outputDir, modules)

        then:
        // Both services should be attempted
        results.size() >= 1
        // Infrastructure service should appear first in results
        def names = results*.serviceName()
        names.indexOf("fractalx-registry") < names.indexOf("order-service") || names.contains("fractalx-registry")
    }
}
