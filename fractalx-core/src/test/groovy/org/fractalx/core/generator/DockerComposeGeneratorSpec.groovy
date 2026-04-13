package org.fractalx.core.generator

import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import org.fractalx.core.config.FractalxConfig

/**
 * Verifies that DockerComposeGenerator produces a correctly structured
 * docker-compose.yml with registry health-checks, service dependencies,
 * environment variables, and per-service Dockerfiles.
 */
class DockerComposeGeneratorSpec extends Specification {

    @TempDir
    Path outputRoot

    DockerComposeGenerator generator = new DockerComposeGenerator()

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

    private String compose() { Files.readString(outputRoot.resolve("docker-compose.yml")) }

    def "docker-compose.yml is created at the output root"() {
        when:
        generator.generate([order], outputRoot, false, FractalxConfig.defaults())

        then:
        Files.exists(outputRoot.resolve("docker-compose.yml"))
    }

    def "docker-compose.yml is version 3.9"() {
        when:
        generator.generate([order], outputRoot, false, FractalxConfig.defaults())

        then:
        compose().startsWith("version: '3.9'")
    }

    def "fractalx-registry service is included with a health check"() {
        when:
        generator.generate([order], outputRoot, false, FractalxConfig.defaults())

        then:
        def c = compose()
        c.contains("fractalx-registry:")
        c.contains("healthcheck:")
        c.contains("/services/health")
    }

    def "registry is exposed on port 8761"() {
        when:
        generator.generate([order], outputRoot, false, FractalxConfig.defaults())

        then:
        compose().contains("8761:8761")
    }

    def "generated services depend on fractalx-registry being healthy"() {
        when:
        generator.generate([order, payment], outputRoot, false, FractalxConfig.defaults())

        then:
        def c = compose()
        c.contains("depends_on:")
        c.contains("condition: service_healthy")
    }

    def "each service exposes both HTTP port and gRPC port (HTTP + 10000)"() {
        when:
        generator.generate([order], outputRoot, false, FractalxConfig.defaults())

        then:
        def c = compose()
        c.contains("8081:8081")
        c.contains("18081:18081")
    }

    def "services have SPRING_PROFILES_ACTIVE=docker environment variable"() {
        when:
        generator.generate([order], outputRoot, false, FractalxConfig.defaults())

        then:
        compose().contains("SPRING_PROFILES_ACTIVE=docker")
    }

    def "services have FRACTALX_REGISTRY_URL environment variable"() {
        when:
        generator.generate([order], outputRoot, false, FractalxConfig.defaults())

        then:
        compose().contains("FRACTALX_REGISTRY_URL=http://fractalx-registry:8761")
    }

    def "saga orchestrator service is included when hasSagaOrchestrator is true"() {
        when:
        generator.generate([order], outputRoot, true, FractalxConfig.defaults())

        then:
        compose().contains("fractalx-saga-orchestrator:")
    }

    def "saga orchestrator service is NOT included when hasSagaOrchestrator is false"() {
        when:
        generator.generate([order], outputRoot, false, FractalxConfig.defaults())

        then:
        !compose().contains("fractalx-saga-orchestrator:")
    }

    def "admin-service and fractalx-gateway are always included"() {
        when:
        generator.generate([order], outputRoot, false, FractalxConfig.defaults())

        then:
        def c = compose()
        c.contains("admin-service:")
        c.contains("fractalx-gateway:")
    }

    def "Dockerfile is created for known service directories that exist"() {
        given:
        Files.createDirectories(outputRoot.resolve("order-service"))

        when:
        generator.generate([order], outputRoot, false, FractalxConfig.defaults())

        then:
        Files.exists(outputRoot.resolve("order-service/Dockerfile"))
    }

    def "Dockerfile uses multi-stage build from maven:3.9-eclipse-temurin-21"() {
        given:
        Files.createDirectories(outputRoot.resolve("order-service"))

        when:
        generator.generate([order], outputRoot, false, FractalxConfig.defaults())

        then:
        def dockerfile = Files.readString(outputRoot.resolve("order-service/Dockerfile"))
        dockerfile.contains("FROM maven:3.9-eclipse-temurin-21 AS build")
        dockerfile.contains("FROM eclipse-temurin:21-jre-jammy")
        dockerfile.contains("RUN mvn -B dependency:resolve dependency:resolve-plugins")
        dockerfile.contains("RUN mvn -B package -DskipTests -q")
        dockerfile.contains("mkdir -p /app/logs && chown fractalx:fractalx /app/logs")
        dockerfile.contains("COPY --from=build --chown=fractalx:fractalx /app/target/*.jar app.jar")
        dockerfile.contains("USER fractalx")
    }

    def "Jaeger all-in-one service is included with OTLP enabled"() {
        when:
        generator.generate([order], outputRoot, false, FractalxConfig.defaults())

        then:
        def c = compose()
        c.contains("jaeger:")
        c.contains("jaegertracing/all-in-one")
        c.contains("COLLECTOR_OTLP_ENABLED=true")
    }

    def "Jaeger exposes UI port 16686 and OTLP gRPC port 4317"() {
        when:
        generator.generate([order], outputRoot, false, FractalxConfig.defaults())

        then:
        def c = compose()
        c.contains("16686:16686")
        c.contains("4317:4317")
    }

    def "logger-service is included in docker-compose"() {
        when:
        generator.generate([order], outputRoot, false, FractalxConfig.defaults())

        then:
        def c = compose()
        c.contains("logger-service:")
        c.contains("9099:9099") || c.contains("9099")
    }

    def "each microservice has OTEL_EXPORTER_OTLP_ENDPOINT pointing to jaeger"() {
        when:
        generator.generate([order], outputRoot, false, FractalxConfig.defaults())

        then:
        compose().contains("OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4317")
    }

    def "each microservice has OTEL_SERVICE_NAME set to its service name"() {
        when:
        generator.generate([order], outputRoot, false, FractalxConfig.defaults())

        then:
        compose().contains("OTEL_SERVICE_NAME=order-service")
    }

    def "each microservice has FRACTALX_LOGGER_URL pointing to logger-service"() {
        when:
        generator.generate([order], outputRoot, false, FractalxConfig.defaults())

        then:
        compose().contains("FRACTALX_LOGGER_URL=http://logger-service:9099/api/logs")
    }

    def "admin-service has JAEGER_QUERY_URL environment variable"() {
        when:
        generator.generate([order], outputRoot, false, FractalxConfig.defaults())

        then:
        compose().contains("JAEGER_QUERY_URL=http://jaeger:")
    }
}
