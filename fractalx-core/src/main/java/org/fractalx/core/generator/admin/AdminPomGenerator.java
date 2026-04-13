package org.fractalx.core.generator.admin;

import org.fractalx.core.FractalxVersion;
import org.fractalx.core.util.SpringBootVersionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Generates pom.xml for the admin service. */
class AdminPomGenerator {

    private static final Logger log = LoggerFactory.getLogger(AdminPomGenerator.class);

    void generate(Path serviceRoot, org.fractalx.core.config.FractalxConfig config) throws IOException {
        String content = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <groupId>%s</groupId>
                    <artifactId>admin-service</artifactId>
                    <version>%s</version>
                    <packaging>jar</packaging>

                    <name>FractalX Admin Service</name>
                    <description>Admin dashboard for FractalX microservices</description>

                    <properties>
                        <java.version>%s</java.version>
                        <spring-boot.version>%s</spring-boot.version>
                        <maven.compiler.source>%s</maven.compiler.source>
                        <maven.compiler.target>%s</maven.compiler.target>
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
                            <artifactId>spring-boot-starter-thymeleaf</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-security</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-actuator</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.fractalx</groupId>
                            <artifactId>fractalx-runtime</artifactId>
                            <version>%s</version>%s
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-mail</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-configuration-processor</artifactId>
                            <optional>true</optional>
                        </dependency>
                        <dependency>
                            <groupId>org.webjars</groupId>
                            <artifactId>bootstrap</artifactId>
                            <version>5.3.0</version>
                        </dependency>
                        <dependency>
                            <groupId>org.webjars</groupId>
                            <artifactId>jquery</artifactId>
                            <version>3.7.0</version>
                        </dependency>
                        <dependency>
                            <groupId>org.webjars</groupId>
                            <artifactId>font-awesome</artifactId>
                            <version>6.4.0</version>
                        </dependency>

                        <!-- ── Database (active when spring.profiles.active=db) ── -->
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-data-jpa</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.flywaydb</groupId>
                            <artifactId>flyway-core</artifactId>
                        </dependency>

                        <!-- H2 embedded — default database for quick start (no setup required) -->
                        <dependency>
                            <groupId>com.h2database</groupId>
                            <artifactId>h2</artifactId>
                            <scope>runtime</scope>
                        </dependency>

                        <!--
                          MySQL — uncomment + add flyway-mysql for MySQL databases:
                        <dependency>
                            <groupId>com.mysql</groupId>
                            <artifactId>mysql-connector-j</artifactId>
                            <scope>runtime</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.flywaydb</groupId>
                            <artifactId>flyway-mysql</artifactId>
                        </dependency>
                        -->

                        <!--
                          PostgreSQL — uncomment for PostgreSQL databases:
                        <dependency>
                            <groupId>org.postgresql</groupId>
                            <artifactId>postgresql</artifactId>
                            <scope>runtime</scope>
                        </dependency>
                        -->
                    </dependencies>

                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <version>3.11.0</version>
                                <configuration>
                                    <!-- Preserve parameter names in bytecode.
                                         Required by Spring MVC for @PathVariable name resolution. -->
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
                """.formatted(config.effectiveBasePackage(), config.initialServiceVersion(), config.javaVersion(), config.springBootVersion(), config.javaVersion(), config.javaVersion(), FractalxVersion.get(),
                        SpringBootVersionUtil.isBoot4Plus(config.springBootVersion()) ? """

                            <exclusions>
                                <exclusion>
                                    <groupId>org.springframework.cloud</groupId>
                                    <artifactId>spring-cloud-context</artifactId>
                                </exclusion>
                                <exclusion>
                                    <groupId>org.springframework.cloud</groupId>
                                    <artifactId>spring-cloud-commons</artifactId>
                                </exclusion>
                            </exclusions>""" : "");
        Files.writeString(serviceRoot.resolve("pom.xml"), content);
        log.debug("Generated admin pom.xml");
    }

}
