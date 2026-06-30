package org.fractalx.core.verifier

import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class DecompositionVerifierSpec extends Specification {

    @TempDir Path outputDir

    DecompositionVerifier verifier = new DecompositionVerifier()

    private FractalModule mod(String name, int port) {
        FractalModule.builder()
            .serviceName(name)
            .packageName("com.example")
            .port(port)
            .build()
    }

    def "VerificationReport hasFailures() returns true when no directories exist"() {
        given:
        def modules = [mod("order-service", 8081)]

        when:
        def report = verifier.verify(outputDir, modules)

        then:
        // Missing infrastructure directories → FAIL checks
        report.hasFailures()
        report.fail() > 0
    }

    def "VerificationReport hasFailures() returns false when all infra dirs and docker references exist"() {
        given:
        ["fractalx-registry", "fractalx-gateway", "admin-service", "logger-service"].each {
            Files.createDirectories(outputDir.resolve(it))
        }
        // docker-compose must contain references to Jaeger and logger-service
        Files.writeString(outputDir.resolve("docker-compose.yml"),
            "version: '3'\nservices:\n  logger-service:\n    image: ...\n  jaeger:\n    image: jaegertracing/all-in-one:1.53\n")
        Files.writeString(outputDir.resolve("start-all.sh"), "#!/bin/bash")
        Files.writeString(outputDir.resolve("stop-all.sh"), "#!/bin/bash")
        def modules = [] // no user modules to check

        when:
        def report = verifier.verify(outputDir, modules)

        then:
        !report.hasFailures()
    }

    def "report contains results for each infrastructure check"() {
        given:
        def modules = [mod("order-service", 8081)]

        when:
        def report = verifier.verify(outputDir, modules)

        then:
        !report.results().isEmpty()
    }

    def "pass count + warn count + fail count equals total results"() {
        given:
        def modules = [mod("order-service", 8081)]

        when:
        def report = verifier.verify(outputDir, modules)

        then:
        report.pass() + report.warn() + report.fail() == report.results().size()
    }

    def "per-module check produces FAIL when service directory is missing"() {
        given:
        def modules = [mod("order-service", 8081)]

        when:
        def report = verifier.verify(outputDir, modules)

        then:
        report.results().any { r ->
            r.status() == DecompositionVerifier.Status.FAIL &&
            r.category().contains("order-service")
        }
    }

    def "per-module check passes when service directory and pom.xml exist"() {
        given:
        def svcDir = outputDir.resolve("order-service")
        Files.createDirectories(svcDir)
        Files.writeString(svcDir.resolve("pom.xml"), "<project/>")
        def modules = [mod("order-service", 8081)]

        when:
        def report = verifier.verify(outputDir, modules)

        then:
        // At least the directory and pom checks should pass
        report.results().any { r ->
            r.status() == DecompositionVerifier.Status.PASS &&
            r.category() == "Service: order-service"
        }
    }

    def "CheckResult model has all fields accessible"() {
        given:
        def check = new DecompositionVerifier.CheckResult(
            "Infrastructure", "registry directory", DecompositionVerifier.Status.PASS, "")

        expect:
        check.category()    == "Infrastructure"
        check.description() == "registry directory"
        check.status()      == DecompositionVerifier.Status.PASS
    }
}
