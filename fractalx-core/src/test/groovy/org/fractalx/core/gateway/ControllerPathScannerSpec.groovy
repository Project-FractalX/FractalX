package org.fractalx.core.gateway

import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class ControllerPathScannerSpec extends Specification {

    @TempDir Path monolithSrc

    ControllerPathScanner scanner = new ControllerPathScanner()

    private FractalModule mod(String pkg) {
        FractalModule.builder()
            .serviceName("order-service")
            .packageName(pkg)
            .port(8081)
            .build()
    }

    private void writeJava(String pkg, String cls, String content) {
        Path dir = monolithSrc.resolve(pkg.replace('.', '/'))
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("${cls}.java"), content)
    }

    def "returns empty set when package directory does not exist"() {
        given:
        def module = mod("com.example.nonexistent")

        expect:
        scanner.scan(monolithSrc, module).isEmpty()
    }

    def "returns empty set when null src"() {
        given:
        def module = mod("com.example.order")

        expect:
        scanner.scan(null, module).isEmpty()
    }

    def "scans @GetMapping endpoints from @RestController"() {
        given:
        writeJava("com.example.order", "OrderController", """
            package com.example.order;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/api/orders")
            public class OrderController {
                @GetMapping("/{id}")
                public String getOrder(@PathVariable Long id) { return "ok"; }
            }
        """)

        when:
        def endpoints = scanner.scan(monolithSrc, mod("com.example.order"))

        then:
        endpoints.any { it.method() == "GET" }
    }

    def "does not return endpoints from non-RestController classes"() {
        given:
        writeJava("com.example.order", "OrderService", """
            package com.example.order;
            import org.springframework.stereotype.Service;
            @Service
            public class OrderService {
                public void createOrder() {}
            }
        """)

        when:
        def endpoints = scanner.scan(monolithSrc, mod("com.example.order"))

        then:
        endpoints.isEmpty()
    }

    def "EndpointInfo.gatewayPath() replaces path variables with asterisk"() {
        given:
        def ep = new ControllerPathScanner.EndpointInfo("GET", "/api/orders/{id}/items")

        expect:
        ep.gatewayPath() == "/api/orders/*/items"
    }

    def "EndpointInfo.hasPathVar() returns true for path variable routes"() {
        expect:
        new ControllerPathScanner.EndpointInfo("GET", "/api/{id}").hasPathVar()
        !new ControllerPathScanner.EndpointInfo("GET", "/api/list").hasPathVar()
    }

    def "normalizePathVars replaces named path variables with {id}"() {
        expect:
        ControllerPathScanner.normalizePathVars("/api/orders/{orderId}/items/{itemId}") ==
            "/api/orders/{id}/items/{id}"
    }

    def "scans @PostMapping endpoints"() {
        given:
        writeJava("com.example.order", "OrderController", """
            package com.example.order;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/api/orders")
            public class OrderController {
                @PostMapping
                public String createOrder() { return "created"; }
            }
        """)

        when:
        def endpoints = scanner.scan(monolithSrc, mod("com.example.order"))

        then:
        endpoints.any { it.method() == "POST" }
    }
}
