package org.fractalx.core.gateway;

import org.fractalx.core.config.FractalxConfig;
import org.fractalx.core.model.FractalModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates pom.xml for API Gateway
 */
public class GatewayPomGenerator {
    private static final Logger log = LoggerFactory.getLogger(GatewayPomGenerator.class);

    public void generatePom(Path gatewayRoot, List<FractalModule> modules, FractalxConfig config) throws IOException {
        log.debug("Generating gateway pom.xml");

        String dependencies = generateServiceDependencies(modules, config);

        String pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
                     http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
            
                <groupId>org.fractalx.gateway</groupId>
                <artifactId>fractalx-api-gateway</artifactId>
                <version>__SVC_VERSION__</version>
                <packaging>jar</packaging>
            
                <name>FractalX API Gateway</name>
                <description>Auto-generated API Gateway for FractalX microservices</description>
            
                <properties>
                    <java.version>__JAVA_VERSION__</java.version>
                    <spring-boot.version>__SB_VERSION__</spring-boot.version>
                    <spring-cloud.version>__SC_VERSION__</spring-cloud.version>
                    <maven.compiler.source>__JAVA_VERSION__</maven.compiler.source>
                    <maven.compiler.target>__JAVA_VERSION__</maven.compiler.target>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                </properties>
            
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-dependencies</artifactId>
                            <version>${spring-boot.version}</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.cloud</groupId>
                            <artifactId>spring-cloud-dependencies</artifactId>
                            <version>${spring-cloud.version}</version>
                            <type>pom</type>
                            <scope>import</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            
                <dependencies>
                    <!-- Spring Cloud Gateway -->
                    <dependency>
                        <groupId>org.springframework.cloud</groupId>
                        <artifactId>__GATEWAY_ARTIFACT__</artifactId>
                    </dependency>
                    
                    <!-- Actuator for health checks -->
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-actuator</artifactId>
                    </dependency>

                    <!-- Security: OAuth2 Resource Server + JWT validation -->
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-security</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
                    </dependency>
                    <!-- JJWT for symmetric Bearer JWT validation -->
                    <dependency>
                        <groupId>io.jsonwebtoken</groupId>
                        <artifactId>jjwt-api</artifactId>
                        <version>0.11.5</version>
                    </dependency>
                    <dependency>
                        <groupId>io.jsonwebtoken</groupId>
                        <artifactId>jjwt-impl</artifactId>
                        <version>0.11.5</version>
                        <scope>runtime</scope>
                    </dependency>
                    <dependency>
                        <groupId>io.jsonwebtoken</groupId>
                        <artifactId>jjwt-jackson</artifactId>
                        <version>0.11.5</version>
                        <scope>runtime</scope>
                    </dependency>

                    <!-- Resilience4j circuit breaker for gateway -->
                    <dependency>
                        <groupId>org.springframework.cloud</groupId>
                        <artifactId>spring-cloud-starter-circuitbreaker-resilience4j</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>io.github.resilience4j</groupId>
                        <artifactId>resilience4j-reactor</artifactId>
                    </dependency>

                    <!-- API documentation aggregation -->
                    <dependency>
                        <groupId>org.springdoc</groupId>
                        <artifactId>springdoc-openapi-starter-webflux-ui</artifactId>
                        <version>2.3.0</version>
                    </dependency>

                    <!-- Distributed tracing: Micrometer OTel bridge creates spans + auto-propagates
                         traceparent to downstream services via Reactor Netty HTTP client -->
                    <dependency>
                        <groupId>io.micrometer</groupId>
                        <artifactId>micrometer-tracing-bridge-otel</artifactId>
                    </dependency>
                    __OTEL_DEPS__
                </dependencies>
            
                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-maven-plugin</artifactId>
                            <version>${spring-boot.version}</version>
                            <executions>
                                <execution>
                                    <goals>
                                        <goal>repackage</goal>
                                    </goals>
                                </execution>
                            </executions>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """;

        boolean isBoot4Plus = config.springBootVersion() != null
                && !config.springBootVersion().isBlank()
                && Character.getNumericValue(config.springBootVersion().charAt(0)) >= 4;
        String otelDeps = isBoot4Plus ? "" : """
                <!-- OTLP gRPC exporter ships spans to Jaeger on port 4317 -->
                    <dependency>
                        <groupId>io.opentelemetry</groupId>
                        <artifactId>opentelemetry-exporter-otlp</artifactId>
                        <version>1.32.0</version>
                    </dependency>
                    <!-- OTel semantic conventions (ResourceAttributes.SERVICE_NAME etc.) -->
                    <dependency>
                        <groupId>io.opentelemetry.semconv</groupId>
                        <artifactId>opentelemetry-semconv</artifactId>
                        <version>1.21.0-alpha</version>
                    </dependency>""";
        // Spring Cloud Gateway 4.3.x (2025.0.x) has a binary incompatibility with
        // Spring Framework 7.0.3+ (HttpHeaders.containsKey(Object) removed in Boot 4.x).
        // Pin the gateway to Spring Boot 3.4.x + Spring Cloud 2024.0.0 (Gateway 4.2.x /
        // Spring Framework 6.x) which is unaffected. The gateway is a pure HTTP proxy
        // so mixed-version routing to Boot 4.x services is safe.
        String gatewayBootVersion  = isBoot4Plus ? "3.4.13" : config.springBootVersion();
        String gatewayCloudVersion = isBoot4Plus ? "2024.0.0" : config.springCloudVersion();
        pomContent = pomContent
                .replace("__SVC_VERSION__", config.initialServiceVersion())
                .replace("__JAVA_VERSION__", config.javaVersion())
                .replace("__SB_VERSION__", gatewayBootVersion)
                .replace("__SC_VERSION__", gatewayCloudVersion)
                .replace("__OTEL_DEPS__", otelDeps)
                .replace("__GATEWAY_ARTIFACT__", "spring-cloud-starter-gateway");
        Path pomPath = gatewayRoot.resolve("pom.xml");
        Files.writeString(pomPath, pomContent);
        log.info("✓ Generated gateway pom.xml");
    }

    /**
     * Generate dependencies for all services
     */
    private String generateServiceDependencies(List<FractalModule> modules, FractalxConfig config) {
        StringBuilder deps = new StringBuilder();

        for (FractalModule module : modules) {
            deps.append(String.format("""
                    <dependency>
                        <groupId>%s</groupId>
                        <artifactId>%s</artifactId>
                        <version>%s</version>
                        <scope>provided</scope>
                    </dependency>
                    """, config.effectiveBasePackage(), module.getServiceName(), config.initialServiceVersion()));
        }

        return deps.toString();
    }
}