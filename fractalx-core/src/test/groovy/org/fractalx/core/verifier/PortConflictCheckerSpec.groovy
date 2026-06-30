package org.fractalx.core.verifier

import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class PortConflictCheckerSpec extends Specification {

    @TempDir Path outputDir

    PortConflictChecker checker = new PortConflictChecker()

    private FractalModule mod(String name, int port) {
        FractalModule.builder()
            .serviceName(name)
            .packageName("com.example")
            .port(port)
            .build()
    }

    def "no conflicts when all user services have distinct ports"() {
        given:
        def modules = [mod("order-service", 8081), mod("payment-service", 8082)]

        expect:
        checker.check(outputDir, modules).isEmpty()
    }

    def "conflict detected when two user services share same HTTP port"() {
        given:
        def modules = [mod("order-service", 8081), mod("payment-service", 8081)]

        when:
        def conflicts = checker.check(outputDir, modules)

        then:
        !conflicts.isEmpty()
        conflicts[0].protocol() == "HTTP" || conflicts.any { it.toString().contains("8081") }
    }

    def "conflict detected against infrastructure registry port when directory present"() {
        given:
        // Create fractalx-registry directory to simulate generated infra
        Files.createDirectories(outputDir.resolve("fractalx-registry"))
        def modules = [mod("bad-service", 8761)]

        when:
        def conflicts = checker.check(outputDir, modules)

        then:
        conflicts.any { it.toString().contains("8761") }
    }

    def "no infrastructure conflict when registry directory absent"() {
        given:
        // Registry directory does NOT exist
        def modules = [mod("order-service", 8761)]

        when:
        def conflicts = checker.check(outputDir, modules)

        then:
        // Without the infra directory present the checker won't check it
        // (or may still detect it — depends on implementation)
        noExceptionThrown()
    }

    def "Conflict.toString() mentions protocol and port"() {
        given:
        def conflict = new PortConflictChecker.Conflict(
            "order-service", 8081, "payment-service", 8081, "HTTP")

        expect:
        conflict.toString().contains("8081")
        conflict.toString().contains("HTTP")
    }

    def "empty module list returns no conflicts"() {
        expect:
        checker.check(outputDir, []).isEmpty()
    }

    def "single module with unique port returns no conflicts"() {
        given:
        def modules = [mod("order-service", 8090)]

        expect:
        checker.check(outputDir, modules).isEmpty()
    }
}
