package org.fractalx.core.gateway

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class GatewayApplicationGeneratorSpec extends Specification {

    @TempDir Path tempDir

    GatewayApplicationGenerator gen = new GatewayApplicationGenerator()

    def "generates FractalXGatewayApplication.java"() {
        given:
        Path srcMainJava = tempDir.resolve("src/main/java")
        Files.createDirectories(srcMainJava)

        when:
        gen.generateApplicationClass(srcMainJava)

        then:
        def files = []
        Files.walk(srcMainJava).filter { it.toString().endsWith("Application.java") }.forEach { files << it }
        !files.isEmpty()
    }

    def "generated application class contains @SpringBootApplication"() {
        given:
        Path srcMainJava = tempDir.resolve("src/main/java")
        Files.createDirectories(srcMainJava)

        when:
        gen.generateApplicationClass(srcMainJava)

        then:
        def files = []
        Files.walk(srcMainJava).filter { it.toString().endsWith(".java") }.forEach { files << it }
        files.any { Files.readString(it).contains("@SpringBootApplication") }
    }

    def "generated application class is in org.fractalx.gateway package"() {
        given:
        Path srcMainJava = tempDir.resolve("src/main/java")
        Files.createDirectories(srcMainJava)

        when:
        gen.generateApplicationClass(srcMainJava)

        then:
        def files = []
        Files.walk(srcMainJava).filter { it.toString().endsWith(".java") }.forEach { files << it }
        files.any { Files.readString(it).contains("package org.fractalx.gateway") }
    }

    def "does not throw when called"() {
        given:
        Path srcMainJava = tempDir.resolve("src/main/java")
        Files.createDirectories(srcMainJava)

        when:
        gen.generateApplicationClass(srcMainJava)

        then:
        noExceptionThrown()
    }
}
