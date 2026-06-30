package org.fractalx.core.auth

import org.fractalx.core.config.FractalxConfig
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class AuthServiceGeneratorSpec extends Specification {

    @TempDir Path outputDir

    AuthServiceGenerator gen = new AuthServiceGenerator()

    FractalxConfig cfg = FractalxConfig.defaults()

    private AuthPattern basicPattern() {
        new AuthPattern(
            true,                          // detected
            "super-secret-jwt-key-32chars", // jwtSecret
            86_400_000L,                   // expirationMs
            "com.example.auth",            // userPackage
            "com.example.auth",            // authPackage
            ["email": "String"],           // domainFields
            null,                          // registerLinkedService
            null,                          // registerLinkedPath
            null                           // registerLinkedIdField
        )
    }

    def "generates auth-service directory"() {
        when:
        gen.generate(outputDir, basicPattern(), cfg)

        then:
        Files.isDirectory(outputDir.resolve("auth-service"))
    }

    def "generates pom.xml in auth-service"() {
        when:
        gen.generate(outputDir, basicPattern(), cfg)

        then:
        Files.exists(outputDir.resolve("auth-service/pom.xml"))
    }

    def "generates Application class in auth-service src"() {
        when:
        gen.generate(outputDir, basicPattern(), cfg)

        then:
        def javaDir = outputDir.resolve("auth-service/src/main/java")
        Files.isDirectory(javaDir)
        def javaFiles = []
        Files.walk(javaDir).filter { it.toString().endsWith("Application.java") }
            .forEach { javaFiles << it }
        !javaFiles.isEmpty()
    }

    def "generates application.yml in auth-service"() {
        when:
        gen.generate(outputDir, basicPattern(), cfg)

        then:
        Files.exists(outputDir.resolve("auth-service/src/main/resources/application.yml"))
    }

    def "generates Flyway migration SQL in auth-service"() {
        when:
        gen.generate(outputDir, basicPattern(), cfg)

        then:
        def migDir = outputDir.resolve("auth-service/src/main/resources/db/migration")
        Files.isDirectory(migDir)
        def sqlFiles = []
        Files.list(migDir).filter { it.toString().endsWith(".sql") }.forEach { sqlFiles << it }
        !sqlFiles.isEmpty()
    }

    def "pom.xml contains jjwt dependency"() {
        when:
        gen.generate(outputDir, basicPattern(), cfg)

        then:
        def pom = Files.readString(outputDir.resolve("auth-service/pom.xml"))
        pom.contains("jjwt")
    }

    def "pom.xml contains spring-boot-starter-security"() {
        when:
        gen.generate(outputDir, basicPattern(), cfg)

        then:
        def pom = Files.readString(outputDir.resolve("auth-service/pom.xml"))
        pom.contains("spring-boot-starter-security")
    }

    def "auth-service port constant is 8090"() {
        expect:
        AuthServiceGenerator.AUTH_PORT == 8090
    }

    def "auth-service gRPC port constant is 18090"() {
        expect:
        AuthServiceGenerator.AUTH_GRPC_PORT == 18090
    }

    def "service name constant is auth-service"() {
        expect:
        AuthServiceGenerator.SERVICE_NAME == "auth-service"
    }

    def "AuthPattern.none() returns not-detected pattern"() {
        expect:
        !AuthPattern.none().detected()
        AuthPattern.none().jwtSecret() == null
    }

    def "AuthPattern.effectiveSecret() falls back to default when jwtSecret is null"() {
        given:
        def pattern = AuthPattern.none()

        expect:
        pattern.effectiveSecret() != null
        !pattern.effectiveSecret().isBlank()
    }

    def "AuthPattern.effectiveSecret() returns jwtSecret when set"() {
        given:
        def pattern = basicPattern()

        expect:
        pattern.effectiveSecret() == "super-secret-jwt-key-32chars"
    }
}
