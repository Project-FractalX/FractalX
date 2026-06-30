package org.fractalx.core.generator.transformation

import org.fractalx.core.config.FractalxConfig
import org.fractalx.core.generator.GenerationContext
import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies ImportCleaner removes unused imports while preserving:
 *  - imports that are actually referenced in the file
 *  - all org.springframework.* imports (kept conservatively)
 *  - wildcard imports
 */
class ImportCleanerSpec extends Specification {

    @TempDir
    Path serviceRoot

    ImportCleaner cleaner = new ImportCleaner()

    FractalModule module = FractalModule.builder()
            .serviceName("order-service")
            .packageName("com.example.order")
            .port(8081)
            .build()

    private GenerationContext ctx() {
        new GenerationContext(module, serviceRoot, serviceRoot, [module], FractalxConfig.defaults())
    }

    private Path write(String name, String content) {
        Path file = serviceRoot.resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        file
    }

    private String read(Path file) { Files.readString(file) }

    // ── removal ───────────────────────────────────────────────────────────────

    def "unused import is removed"() {
        given:
        def file = write("OrderService.java", """\
            package com.example.order;
            import java.util.UUID;
            import com.example.payment.PaymentService;
            public class OrderService {
                public UUID createOrder() { return UUID.randomUUID(); }
            }
        """)

        when:
        cleaner.generate(ctx())

        then:
        def content = read(file)
        !content.contains("import com.example.payment.PaymentService")
    }

    // ── preservation ──────────────────────────────────────────────────────────

    def "import that is referenced as a field type is preserved"() {
        given:
        def file = write("OrderService.java", """\
            package com.example.order;
            import java.util.List;
            public class OrderService {
                private List<String> orders;
            }
        """)

        when:
        cleaner.generate(ctx())

        then:
        read(file).contains("import java.util.List")
    }

    def "import that is referenced as a return type is preserved"() {
        given:
        def file = write("OrderService.java", """\
            package com.example.order;
            import java.util.Optional;
            public class OrderService {
                public Optional<String> findOrder() { return Optional.empty(); }
            }
        """)

        when:
        cleaner.generate(ctx())

        then:
        read(file).contains("import java.util.Optional")
    }

    def "org.springframework imports are preserved even if not referenced"() {
        given:
        def file = write("OrderConfig.java", """\
            package com.example.order;
            import org.springframework.context.annotation.Configuration;
            import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
            public class OrderConfig {}
        """)

        when:
        cleaner.generate(ctx())

        then:
        def content = read(file)
        content.contains("import org.springframework.context.annotation.Configuration")
        content.contains("import org.springframework.boot.autoconfigure.EnableAutoConfiguration")
    }

    def "wildcard import is preserved"() {
        given:
        def file = write("OrderService.java", """\
            package com.example.order;
            import java.util.*;
            public class OrderService {
                private List<String> orders;
            }
        """)

        when:
        cleaner.generate(ctx())

        then:
        read(file).contains("import java.util.*")
    }

    def "annotation import that is used is preserved"() {
        given:
        def file = write("OrderService.java", """\
            package com.example.order;
            import org.springframework.stereotype.Service;
            import jakarta.transaction.Transactional;
            @Service
            public class OrderService {
                @Transactional
                public void process() {}
            }
        """)

        when:
        cleaner.generate(ctx())

        then:
        def content = read(file)
        content.contains("import jakarta.transaction.Transactional")
    }

    // ── multiple files ────────────────────────────────────────────────────────

    def "all java files in the service root are processed"() {
        given:
        def f1 = write("OrderService.java", """\
            package com.example.order;
            import com.example.unused.Unused;
            public class OrderService {}
        """)
        def f2 = write("OrderRepository.java", """\
            package com.example.order;
            import com.example.also.Unused;
            public class OrderRepository {}
        """)

        when:
        cleaner.generate(ctx())

        then:
        !read(f1).contains("import com.example.unused.Unused")
        !read(f2).contains("import com.example.also.Unused")
    }
}
