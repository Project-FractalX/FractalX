package org.fractalx.core.generator.transformation

import org.fractalx.core.config.FractalxConfig
import org.fractalx.core.generator.GenerationContext
import org.fractalx.core.gateway.SecurityProfile
import org.fractalx.core.model.FractalModule
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class AuthenticationPrincipalRewriterStepSpec extends Specification {

    @TempDir
    Path serviceRoot

    AuthenticationPrincipalRewriterStep step = new AuthenticationPrincipalRewriterStep()

    FractalModule module = FractalModule.builder()
        .serviceName("order-service")
        .packageName("com.example.order")
        .port(8081)
        .build()

    private GenerationContext securedContext() {
        def profile = new SecurityProfile(
            SecurityProfile.AuthType.BEARER_JWT, Set.of(SecurityProfile.AuthType.BEARER_JWT),
            true, null, null, "secret", null, null, [], [])
        new GenerationContext(module, serviceRoot, serviceRoot, [module],
            FractalxConfig.defaults(), profile)
    }

    private GenerationContext unsecuredContext() {
        new GenerationContext(module, serviceRoot, serviceRoot, [module],
            FractalxConfig.defaults())
    }

    private Path writeJava(String pkg, String className, String content) {
        Path dir = serviceRoot.resolve("src/main/java").resolve(pkg.replace('.', '/'))
        Files.createDirectories(dir)
        Path file = dir.resolve("${className}.java")
        Files.writeString(file, content)
        return file
    }

    def "rewrites @AuthenticationPrincipal User to GatewayPrincipal"() {
        given:
        writeJava("com.example.order", "OrderController", """
            package com.example.order;
            import org.springframework.security.core.annotation.AuthenticationPrincipal;
            import org.springframework.web.bind.annotation.*;
            import com.example.model.User;
            @RestController
            @RequestMapping("/api/orders")
            public class OrderController {
                @GetMapping
                public Object list(@AuthenticationPrincipal User user) {
                    return null;
                }
            }
        """)

        when:
        step.generate(securedContext())

        then:
        def source = Files.readString(serviceRoot.resolve(
            "src/main/java/com/example/order/OrderController.java"))
        source.contains("@AuthenticationPrincipal GatewayPrincipal user")
        source.contains("import org.fractalx.runtime.GatewayPrincipal;")
        !source.contains("@AuthenticationPrincipal User user")
    }

    def "rewrites @AuthenticationPrincipal UserDetails to GatewayPrincipal"() {
        given:
        writeJava("com.example.order", "OrderController", """
            package com.example.order;
            import org.springframework.security.core.annotation.AuthenticationPrincipal;
            import org.springframework.security.core.userdetails.UserDetails;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class OrderController {
                @GetMapping("/orders")
                public Object list(@AuthenticationPrincipal UserDetails principal) {
                    return null;
                }
            }
        """)

        when:
        step.generate(securedContext())

        then:
        def source = Files.readString(serviceRoot.resolve(
            "src/main/java/com/example/order/OrderController.java"))
        source.contains("@AuthenticationPrincipal GatewayPrincipal principal")
        source.contains("import org.fractalx.runtime.GatewayPrincipal;")
    }

    def "does not modify files without @AuthenticationPrincipal"() {
        given:
        def file = writeJava("com.example.order", "OrderService", """
            package com.example.order;
            public class OrderService {
                public Object findAll() { return null; }
            }
        """)
        def originalContent = Files.readString(file)

        when:
        step.generate(securedContext())

        then:
        Files.readString(file) == originalContent
    }

    def "skips already-rewritten GatewayPrincipal parameters"() {
        given:
        writeJava("com.example.order", "OrderController", """
            package com.example.order;
            import org.springframework.security.core.annotation.AuthenticationPrincipal;
            import org.fractalx.runtime.GatewayPrincipal;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class OrderController {
                @GetMapping("/orders")
                public Object list(@AuthenticationPrincipal GatewayPrincipal principal) {
                    return null;
                }
            }
        """)
        def originalContent = Files.readString(serviceRoot.resolve(
            "src/main/java/com/example/order/OrderController.java"))

        when:
        step.generate(securedContext())

        then:
        Files.readString(serviceRoot.resolve(
            "src/main/java/com/example/order/OrderController.java")) == originalContent
    }

    def "is a no-op when security is not enabled"() {
        given:
        def file = writeJava("com.example.order", "OrderController", """
            package com.example.order;
            import org.springframework.security.core.annotation.AuthenticationPrincipal;
            import com.example.model.User;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class OrderController {
                @GetMapping("/orders")
                public Object list(@AuthenticationPrincipal User user) {
                    return null;
                }
            }
        """)
        def originalContent = Files.readString(file)

        when:
        step.generate(unsecuredContext())

        then:
        Files.readString(file) == originalContent
    }

    def "handles multiple @AuthenticationPrincipal parameters across methods"() {
        given:
        writeJava("com.example.order", "OrderController", """
            package com.example.order;
            import org.springframework.security.core.annotation.AuthenticationPrincipal;
            import org.springframework.web.bind.annotation.*;
            import com.example.model.User;
            @RestController
            @RequestMapping("/api")
            public class OrderController {
                @GetMapping("/orders")
                public Object list(@AuthenticationPrincipal User user) { return null; }
                @PostMapping("/orders")
                public Object create(@AuthenticationPrincipal User user, @RequestBody Object body) { return null; }
            }
        """)

        when:
        step.generate(securedContext())

        then:
        def source = Files.readString(serviceRoot.resolve(
            "src/main/java/com/example/order/OrderController.java"))
        !source.contains("@AuthenticationPrincipal User")
        source.contains("import org.fractalx.runtime.GatewayPrincipal;")
    }
}
