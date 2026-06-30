package org.fractalx.core.generator.transformation

import org.fractalx.core.config.FractalxConfig
import org.fractalx.core.generator.GenerationContext
import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies FileCleanupStep's AST-based stub detection:
 * files whose class implements/extends a cross-module dependency type are deleted;
 * all others are left intact.
 */
class FileCleanupStepSpec extends Specification {

    @TempDir
    Path serviceRoot

    FractalModule moduleWithDep = FractalModule.builder()
            .serviceName("order-service")
            .packageName("com.example.order")
            .port(8081)
            .dependencies(["PaymentClient"])
            .build()

    FractalModule moduleNoDeps = FractalModule.builder()
            .serviceName("payment-service")
            .packageName("com.example.payment")
            .port(8082)
            .build()

    FileCleanupStep step = new FileCleanupStep()

    private GenerationContext ctx(FractalModule m) {
        new GenerationContext(m, serviceRoot, serviceRoot, [m], FractalxConfig.defaults())
    }

    private Path write(String name, String content) {
        Path file = serviceRoot.resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        file
    }

    // ── deletion ──────────────────────────────────────────────────────────────

    def "file whose class implements a dependency type is deleted"() {
        given:
        def stub = write("PaymentClientImpl.java", """
            package com.example.order;
            public class PaymentClientImpl implements PaymentClient {
                public void pay() {}
            }
        """)

        when:
        step.generate(ctx(moduleWithDep))

        then:
        !Files.exists(stub)
    }

    def "file whose class extends a dependency type is deleted"() {
        given:
        def stub = write("PaymentClientAdapter.java", """
            package com.example.order;
            public class PaymentClientAdapter extends PaymentClient {
                public void pay() {}
            }
        """)

        when:
        step.generate(ctx(moduleWithDep))

        then:
        !Files.exists(stub)
    }

    def "stub in a subdirectory is detected and deleted"() {
        given:
        def stub = write("client/PaymentClientImpl.java", """
            package com.example.order.client;
            public class PaymentClientImpl implements PaymentClient {
                public void pay() {}
            }
        """)

        when:
        step.generate(ctx(moduleWithDep))

        then:
        !Files.exists(stub)
    }

    // ── preservation ──────────────────────────────────────────────────────────

    def "file that does NOT implement any dependency type is preserved"() {
        given:
        def keeper = write("OrderRepository.java", """
            package com.example.order;
            public interface OrderRepository {}
        """)

        when:
        step.generate(ctx(moduleWithDep))

        then:
        Files.exists(keeper)
    }

    def "file implementing an unrelated interface is preserved"() {
        given:
        def keeper = write("OrderService.java", """
            package com.example.order;
            public class OrderService implements Serializable {
                public void process() {}
            }
        """)

        when:
        step.generate(ctx(moduleWithDep))

        then:
        Files.exists(keeper)
    }

    def "module with no dependencies causes no files to be deleted"() {
        given:
        def file = write("PaymentService.java", """
            package com.example.payment;
            public class PaymentService {}
        """)

        when:
        step.generate(ctx(moduleNoDeps))

        then:
        Files.exists(file)
    }

    def "empty service directory does not throw"() {
        when:
        step.generate(ctx(moduleNoDeps))

        then:
        noExceptionThrown()
    }

    // ── explicit-list mode (backwards compat) ─────────────────────────────────

    def "explicit-list constructor still deletes the specified files"() {
        given:
        def target = write("LegacyStub.java", "public class LegacyStub {}")
        def kept   = write("OrderService.java", "public class OrderService {}")
        def explicitStep = new FileCleanupStep(["LegacyStub.java"])

        when:
        explicitStep.generate(ctx(moduleNoDeps))

        then:
        !Files.exists(target)
        Files.exists(kept)
    }
}
