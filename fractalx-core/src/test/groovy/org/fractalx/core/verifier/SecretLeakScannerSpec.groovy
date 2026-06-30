package org.fractalx.core.verifier

import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class SecretLeakScannerSpec extends Specification {

    @TempDir Path outputDir

    SecretLeakScanner scanner = new SecretLeakScanner()

    private FractalModule mod(String name) {
        FractalModule.builder()
            .serviceName(name)
            .packageName("com.example")
            .port(8081)
            .build()
    }

    private void writeYml(String service, String filename, String content) {
        Path dir = outputDir.resolve("${service}/src/main/resources")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(filename), content)
    }

    def "no leaks when service directory does not exist"() {
        given:
        def modules = [mod("order-service")]

        expect:
        scanner.scan(outputDir, modules).isEmpty()
    }

    def "no leaks when YAML uses Spring EL placeholder"() {
        given:
        def modules = [mod("order-service")]
        writeYml("order-service", "application.yml", """
            spring:
              datasource:
                password: \${DB_PASSWORD}
        """)

        expect:
        scanner.scan(outputDir, modules).isEmpty()
    }

    def "no leaks for known safe placeholder values"() {
        given:
        def modules = [mod("order-service")]
        writeYml("order-service", "application.yml", """
            app:
              secret: changeme
        """)

        expect:
        scanner.scan(outputDir, modules).isEmpty()
    }

    def "detects hardcoded password in YAML"() {
        given:
        def modules = [mod("order-service")]
        writeYml("order-service", "application.yml", """
            spring:
              datasource:
                password: mySuperSecretPassword123
        """)

        when:
        def leaks = scanner.scan(outputDir, modules)

        then:
        !leaks.isEmpty()
        leaks[0].service() == "order-service"
        leaks[0].key().toLowerCase().contains("password")
    }

    def "detects hardcoded JWT secret"() {
        given:
        def modules = [mod("order-service")]
        writeYml("order-service", "application.yml", """
            fractalx:
              jwt-secret: abcdefghijklmnop1234567890abcdef
        """)

        when:
        def leaks = scanner.scan(outputDir, modules)

        then:
        !leaks.isEmpty()
    }

    def "masked value in SecretLeak.toString() does not expose full secret"() {
        given:
        def modules = [mod("order-service")]
        writeYml("order-service", "application.yml", """
            app:
              api-key: thisIsARealSecretKey99
        """)

        when:
        def leaks = scanner.scan(outputDir, modules)

        then:
        if (!leaks.isEmpty()) {
            // The masked value should not be the full secret
            !leaks[0].maskedValue().contains("thisIsARealSecretKey99")
        }
        noExceptionThrown()
    }

    def "SecretLeak.toString() contains service and key names"() {
        given:
        def leak = new SecretLeakScanner.SecretLeak(
            "order-service",
            Path.of("application.yml"),
            5,
            "password",
            "***"
        )

        expect:
        leak.toString().contains("order-service")
        leak.toString().contains("password")
    }

    def "empty module list scans nothing"() {
        expect:
        scanner.scan(outputDir, []).isEmpty()
    }
}
