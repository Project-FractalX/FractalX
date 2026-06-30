package org.fractalx.core.verifier

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class EndpointScannerSpec extends Specification {

    @TempDir Path serviceDir

    EndpointScanner scanner = new EndpointScanner()

    private void writeController(String pkg, String cls, String code) {
        Path dir = serviceDir.resolve("src/main/java").resolve(pkg.replace('.', '/'))
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("${cls}.java"), code)
    }

    def "returns empty list when no controller files exist"() {
        given:
        Files.createDirectories(serviceDir.resolve("src/main/java"))

        expect:
        scanner.scan(serviceDir).isEmpty()
    }

    def "returns empty list when service directory has no src/main/java"() {
        expect:
        scanner.scan(serviceDir).isEmpty()
    }

    def "scans GET endpoint from @GetMapping"() {
        given:
        writeController("com.example.order", "OrderController", """
            package com.example.order;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/orders")
            public class OrderController {
                @GetMapping("/{id}")
                public String getOrder(@PathVariable Long id) { return "order"; }
            }
        """)

        when:
        def endpoints = scanner.scan(serviceDir)

        then:
        endpoints.any { it.httpMethod() == "GET" }
    }

    def "scans POST endpoint from @PostMapping"() {
        given:
        writeController("com.example.order", "OrderController", """
            package com.example.order;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/orders")
            public class OrderController {
                @PostMapping
                public String createOrder(@RequestBody String body) { return "created"; }
            }
        """)

        when:
        def endpoints = scanner.scan(serviceDir)

        then:
        endpoints.any { it.httpMethod() == "POST" }
    }

    def "detects hasPathVars=true when path contains {variable}"() {
        given:
        writeController("com.example.order", "OrderController", """
            package com.example.order;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/orders")
            public class OrderController {
                @GetMapping("/{id}")
                public String get() { return "ok"; }
            }
        """)

        when:
        def endpoints = scanner.scan(serviceDir)

        then:
        endpoints.any { it.hasPathVars() }
    }

    def "detects hasRequestBody=true when @RequestBody parameter exists"() {
        given:
        writeController("com.example.order", "OrderController", """
            package com.example.order;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class OrderController {
                @PostMapping("/orders")
                public String create(@RequestBody Object body) { return "ok"; }
            }
        """)

        when:
        def endpoints = scanner.scan(serviceDir)

        then:
        endpoints.any { it.hasRequestBody() }
    }

    def "EndpointSpec.toString() includes HTTP method and path"() {
        given:
        def spec = new EndpointScanner.EndpointSpec("GET", "/orders/{id}", true, false, "OrderController", "getOrder")

        expect:
        spec.toString().contains("GET")
        spec.toString().contains("/orders/{id}")
    }

    def "multiple controllers are scanned"() {
        given:
        writeController("com.example", "OrderController", """
            package com.example;
            import org.springframework.web.bind.annotation.*;
            @RestController public class OrderController {
                @GetMapping("/orders") public String list() { return "ok"; }
            }
        """)
        writeController("com.example", "PaymentController", """
            package com.example;
            import org.springframework.web.bind.annotation.*;
            @RestController public class PaymentController {
                @PostMapping("/payments") public String pay(@RequestBody Object b) { return "ok"; }
            }
        """)

        when:
        def endpoints = scanner.scan(serviceDir)

        then:
        endpoints.size() >= 2
    }
}
