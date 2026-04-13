package org.fractalx.core.datamanagement

import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies fix #59: FlywayMigrationGenerator must NOT emit '_TODO' column names
 * or invalid SQL for unresolvable @Embedded types.
 */
class FlywayEmbeddedTodoFixSpec extends Specification {

    @TempDir
    Path serviceRoot

    FlywayMigrationGenerator generator = new FlywayMigrationGenerator()

    FractalModule module = FractalModule.builder()
        .serviceName("order-service")
        .packageName("org.fractalx.test.order")
        .port(8081)
        .build()

    private void writeEntity(String relativePath, String content) {
        Path file = serviceRoot.resolve("src/main/java").resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private String sql() {
        Files.readString(serviceRoot.resolve("src/main/resources/db/migration/V1__init.sql"))
    }

    def "embedded type found in scanned sources is inlined correctly"() {
        given:
        writeEntity("org/fractalx/test/order/Address.java", """
            package org.fractalx.test.order;
            import jakarta.persistence.Embeddable;
            @Embeddable
            public class Address {
                private String street;
                private String city;
            }
        """)
        writeEntity("org/fractalx/test/order/Order.java", """
            package org.fractalx.test.order;
            import jakarta.persistence.*;
            @Entity
            public class Order {
                @Id private Long id;
                @Embedded private Address address;
            }
        """)

        when:
        generator.generateMigration(module, serviceRoot)

        then:
        def content = sql()
        content.contains("address_street")
        content.contains("address_city")
        !content.contains("_TODO")
    }

    def "unresolvable embedded type does NOT produce _TODO column names"() {
        given:
        writeEntity("org/fractalx/test/order/Order.java", """
            package org.fractalx.test.order;
            import jakarta.persistence.*;
            @Entity
            public class Order {
                @Id private Long id;
                private String customerId;
                @Embedded private ExternalAddress billingAddress;
            }
        """)

        when:
        generator.generateMigration(module, serviceRoot)

        then: "no _TODO column names in the SQL"
        def content = sql()
        !content.contains("_TODO")

        and: "a FIXME comment block is emitted instead"
        content.contains("FIXME")
        content.contains("ExternalAddress")
    }

    def "unresolvable embedded type produces valid SQL that can be parsed"() {
        given:
        writeEntity("org/fractalx/test/order/Order.java", """
            package org.fractalx.test.order;
            import jakarta.persistence.*;
            @Entity
            public class Order {
                @Id private Long id;
                @Embedded private MissingType payload;
            }
        """)

        when:
        generator.generateMigration(module, serviceRoot)

        then: "SQL is parseable — no column defs with _TODO as a column name"
        def content = sql()
        !content.contains("payload_TODO VARCHAR")
        content.contains("CREATE TABLE IF NOT EXISTS")
    }
}
