package org.fractalx.core.validation.rules

import org.fractalx.core.model.FractalModule
import org.fractalx.core.validation.ValidationContext
import org.fractalx.core.validation.ValidationSeverity
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class LombokConstructorRuleSpec extends Specification {

    @TempDir Path sourceRoot

    private static final def RULE = new LombokConstructorRule()

    private FractalModule mod(String className, List<String> deps = []) {
        FractalModule.builder()
            .serviceName("order-service")
            .className(className)
            .packageName("com.example.order")
            .port(8081)
            .dependencies(deps)
            .build()
    }

    private void writeSource(String relPath, String content) {
        Path file = sourceRoot.resolve(relPath)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private ValidationContext ctx(List<FractalModule> mods) {
        new ValidationContext(mods, sourceRoot, null)
    }

    def "no issues when source file does not exist (skipped gracefully)"() {
        given:
        def m = mod("com.example.order.OrderModule")

        expect:
        RULE.validate(ctx([m])).isEmpty()
    }

    def "no issues when class uses @RequiredArgsConstructor with no explicit deps"() {
        given:
        writeSource("com/example/order/OrderModule.java", """
            package com.example.order;
            import lombok.RequiredArgsConstructor;
            @RequiredArgsConstructor
            public class OrderModule {
                private final SomeService someService;
            }
        """)
        def m = mod("com.example.order.OrderModule")

        expect:
        RULE.validate(ctx([m])).isEmpty()
    }

    def "WARNING when class has @AllArgsConstructor and no explicit dependencies declared"() {
        given:
        writeSource("com/example/order/OrderModule.java", """
            package com.example.order;
            import lombok.AllArgsConstructor;
            @AllArgsConstructor
            public class OrderModule {
                private SomeService someService;
            }
        """)
        def m = mod("com.example.order.OrderModule") // no explicit deps

        when:
        def issues = RULE.validate(ctx([m]))

        then:
        issues.size() == 1
        issues[0].severity() == ValidationSeverity.WARNING
        issues[0].ruleId() == "LOMBOK_ALL_ARGS"
    }

    def "no issues when @AllArgsConstructor but explicit deps declared in module"() {
        given:
        writeSource("com/example/order/OrderModule.java", """
            package com.example.order;
            import lombok.AllArgsConstructor;
            @AllArgsConstructor
            public class OrderModule {
                private SomeService someService;
            }
        """)
        // Explicit deps declared → warning suppressed
        def m = mod("com.example.order.OrderModule", ["SomeService"])

        expect:
        RULE.validate(ctx([m])).isEmpty()
    }

    def "no issues when class has no annotations"() {
        given:
        writeSource("com/example/order/OrderModule.java", """
            package com.example.order;
            public class OrderModule {
                private SomeService someService;
                public OrderModule(SomeService s) { this.someService = s; }
            }
        """)
        def m = mod("com.example.order.OrderModule")

        expect:
        RULE.validate(ctx([m])).isEmpty()
    }

    def "fix message mentions @RequiredArgsConstructor"() {
        given:
        writeSource("com/example/order/OrderModule.java", """
            package com.example.order;
            import lombok.AllArgsConstructor;
            @AllArgsConstructor
            public class OrderModule {}
        """)
        def m = mod("com.example.order.OrderModule")

        when:
        def issues = RULE.validate(ctx([m]))

        then:
        issues.size() == 1
        issues[0].fix().contains("@RequiredArgsConstructor")
    }

    def "null className is skipped gracefully"() {
        given:
        def m = FractalModule.builder()
            .serviceName("order-service")
            .packageName("com.example.order")
            .port(8081)
            .build()

        expect:
        RULE.validate(ctx([m])).isEmpty()
    }
}
