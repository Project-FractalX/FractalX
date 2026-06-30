package org.fractalx.core.verifier

import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class SqlSchemaValidatorSpec extends Specification {

    @TempDir Path outputDir

    SqlSchemaValidator validator = new SqlSchemaValidator()

    private FractalModule mod(String name) {
        FractalModule.builder()
            .serviceName(name)
            .packageName("com.example")
            .port(8081)
            .build()
    }

    private void writeSql(String service, String filename, String content) {
        Path dir = outputDir.resolve("${service}/src/main/resources/db/migration")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(filename), content)
    }

    def "no findings when service has no migrations directory"() {
        given:
        def modules = [mod("order-service")]

        expect:
        validator.validate(outputDir, modules).isEmpty()
    }

    def "bad naming convention produces BAD_NAMING finding"() {
        given:
        def modules = [mod("order-service")]
        writeSql("order-service", "001_bad_name.sql", "CREATE TABLE orders (id BIGINT PRIMARY KEY);")

        when:
        def findings = validator.validate(outputDir, modules)

        then:
        findings.any { it.kind() == SqlSchemaValidator.SqlFindingKind.BAD_NAMING }
    }

    def "valid naming V1__description.sql produces no BAD_NAMING finding"() {
        given:
        def modules = [mod("order-service")]
        writeSql("order-service", "V1__create_orders.sql", "CREATE TABLE orders (id BIGINT PRIMARY KEY);")

        when:
        def findings = validator.validate(outputDir, modules)

        then:
        findings.every { it.kind() != SqlSchemaValidator.SqlFindingKind.BAD_NAMING }
    }

    def "empty SQL file produces EMPTY_MIGRATION finding"() {
        given:
        def modules = [mod("order-service")]
        writeSql("order-service", "V1__create_orders.sql", "   ")

        when:
        def findings = validator.validate(outputDir, modules)

        then:
        findings.any { it.kind() == SqlSchemaValidator.SqlFindingKind.EMPTY_MIGRATION }
    }

    def "DROP TABLE in versioned migration produces DANGEROUS_STATEMENT finding"() {
        given:
        def modules = [mod("order-service")]
        writeSql("order-service", "V1__create_orders.sql",
            "DROP TABLE orders;\nCREATE TABLE orders (id BIGINT PRIMARY KEY);")

        when:
        def findings = validator.validate(outputDir, modules)

        then:
        findings.any { it.kind() == SqlSchemaValidator.SqlFindingKind.DANGEROUS_STATEMENT }
    }

    def "TRUNCATE TABLE in versioned migration produces DANGEROUS_STATEMENT finding"() {
        given:
        def modules = [mod("order-service")]
        writeSql("order-service", "V2__add_column.sql",
            "TRUNCATE TABLE orders;")

        when:
        def findings = validator.validate(outputDir, modules)

        then:
        findings.any { it.kind() == SqlSchemaValidator.SqlFindingKind.DANGEROUS_STATEMENT }
    }

    def "SqlFinding.isCritical() returns true for BAD_NAMING"() {
        given:
        def finding = new SqlSchemaValidator.SqlFinding(
            SqlSchemaValidator.SqlFindingKind.BAD_NAMING, "svc", Path.of("001_bad.sql"), "bad name")

        expect:
        finding.isCritical()
    }

    def "SqlFinding.isCritical() returns false for DANGEROUS_STATEMENT"() {
        given:
        def finding = new SqlSchemaValidator.SqlFinding(
            SqlSchemaValidator.SqlFindingKind.DANGEROUS_STATEMENT, "svc", Path.of("V1__x.sql"), "DROP TABLE")

        expect:
        !finding.isCritical()
    }

    def "SqlFinding.toString() includes service name and finding kind"() {
        given:
        def finding = new SqlSchemaValidator.SqlFinding(
            SqlSchemaValidator.SqlFindingKind.BAD_NAMING, "order-service", Path.of("001.sql"), "bad")

        expect:
        finding.toString().contains("order-service")
        finding.toString().contains("[FAIL]")
    }

    def "empty module list produces no findings"() {
        expect:
        validator.validate(outputDir, []).isEmpty()
    }
}
