package org.fractalx.core.generator.registry;

import org.fractalx.core.config.FractalxConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Generates pom.xml for the fractalx-registry service. */
class RegistryPomGenerator {

    private static final Logger log = LoggerFactory.getLogger(RegistryPomGenerator.class);

    void generate(Path registryRoot, FractalxConfig config) throws IOException {
        String content = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <groupId>org.fractalx</groupId>
                    <artifactId>fractalx-registry</artifactId>
                    <version>__SVC_VERSION__</version>
                    <packaging>jar</packaging>

                    <name>FractalX Service Registry</name>
                    <description>Lightweight service registry for FractalX microservices</description>

                    <properties>
                        <java.version>__JAVA_VERSION__</java.version>
                        <spring-boot.version>__SB_VERSION__</spring-boot.version>
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
                        </dependencies>
                    </dependencyManagement>

                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-actuator</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-validation</artifactId>
                        </dependency>
                    </dependencies>

                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <version>3.11.0</version>
                                <configuration>
                                    <parameters>true</parameters>
                                </configuration>
                            </plugin>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>${spring-boot.version}</version>
                                <executions>
                                    <execution>
                                        <goals><goal>repackage</goal></goals>
                                    </execution>
                                </executions>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """;

        content = content.replace("__SVC_VERSION__", config.initialServiceVersion())
                .replace("__JAVA_VERSION__", config.javaVersion())
                .replace("__SB_VERSION__", config.springBootVersion());
        Files.writeString(registryRoot.resolve("pom.xml"), content);
        log.debug("Generated registry pom.xml");
    }
}
