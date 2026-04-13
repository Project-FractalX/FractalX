package org.fractalx.core.gateway;

import org.fractalx.core.model.FractalModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Generates gateway-level Resilience4j circuit breaker config and fallback controller. */
public class GatewayCircuitBreakerGenerator {

    private static final Logger log = LoggerFactory.getLogger(GatewayCircuitBreakerGenerator.class);

    public void generate(Path srcMainJava, List<FractalModule> modules) throws IOException {
        Path pkg = createPkg(srcMainJava, "org/fractalx/gateway/resilience");

        generateFallbackController(pkg, modules);
        generateResilienceConfig(pkg, modules);

        log.info("Generated gateway circuit breaker config");
    }

    private void generateFallbackController(Path pkg, List<FractalModule> modules) throws IOException {
        StringBuilder methods = new StringBuilder();
        for (FractalModule m : modules) {
            String camel = toCamelCase(m.getServiceName());
            String serviceName = m.getServiceName();
            String body = """
                            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                    .body(Map.of(
                                            "error", "Service Unavailable",
                                            "service", "%s",
                                            "message", "The service is temporarily unavailable. Please retry shortly.",
                                            "timestamp", System.currentTimeMillis()
                                    ));""".formatted(serviceName);
            methods.append("""

                        @GetMapping("/%s")
                        public ResponseEntity<Map<String, Object>> %sFallbackGet() {
                    %s
                        }

                        @PostMapping("/%s")
                        public ResponseEntity<Map<String, Object>> %sFallbackPost() {
                    %s
                        }
                    """.formatted(serviceName, camel, body, serviceName, camel, body));
        }

        String content = """
                package org.fractalx.gateway.resilience;

                import org.springframework.http.HttpStatus;
                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                import java.util.Map;

                @RestController
                @RequestMapping("/fallback")
                public class GatewayFallbackController {
                    %s
                }
                """.formatted(methods.toString());

        Files.writeString(pkg.resolve("GatewayFallbackController.java"), content);
    }

    private void generateResilienceConfig(Path pkg, List<FractalModule> modules) throws IOException {
        StringBuilder cbBeans = new StringBuilder();
        for (FractalModule m : modules) {
            cbBeans.append("""

                        @Bean
                        public ReactiveCircuitBreaker %sCb(ReactiveCircuitBreakerFactory<?, ?> factory) {
                            return factory.create("%s");
                        }
                    """.formatted(toCamelCase(m.getServiceName()), m.getServiceName()));
        }

        String content = """
                package org.fractalx.gateway.resilience;

                import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
                import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class GatewayResilienceConfig {
                    %s
                }
                """.formatted(cbBeans.toString());

        Files.writeString(pkg.resolve("GatewayResilienceConfig.java"), content);
    }

    private String toCamelCase(String name) {
        String[] parts = name.split("-");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty())
                sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return sb.toString();
    }

    private Path createPkg(Path base, String pkg) throws IOException {
        Path p = base;
        for (String part : pkg.split("/")) p = p.resolve(part);
        Files.createDirectories(p);
        return p;
    }
}
