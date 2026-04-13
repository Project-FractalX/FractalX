package org.fractalx.core.generator.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Generates the InMemoryRegistryService with scheduled health polling. */
class RegistryServiceClassGenerator {

    private static final Logger log = LoggerFactory.getLogger(RegistryServiceClassGenerator.class);

    void generate(Path srcMainJava) throws IOException {
        Path pkg = createPkg(srcMainJava, "org/fractalx/registry/service");

        String content = """
                package org.fractalx.registry.service;

                import org.fractalx.registry.model.ServiceRegistration;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Service;
                import org.springframework.web.client.RestTemplate;

                import java.time.Instant;
                import java.util.ArrayList;
                import java.util.Collection;
                import java.util.Optional;
                import java.util.concurrent.ConcurrentHashMap;

                @Service
                public class RegistryService {

                    private static final Logger log = LoggerFactory.getLogger(RegistryService.class);
                    private static final long EVICT_AFTER_MS = 90_000L;

                    private final ConcurrentHashMap<String, ServiceRegistration> registry = new ConcurrentHashMap<>();
                    private final RestTemplate restTemplate = new RestTemplate();

                    public ServiceRegistration register(ServiceRegistration reg) {
                        reg.setStatus("UP");
                        reg.setLastSeen(Instant.now());
                        registry.put(reg.getName(), reg);
                        log.info("Registered service: {} at {}:{}", reg.getName(), reg.getHost(), reg.getPort());
                        return reg;
                    }

                    public Optional<ServiceRegistration> findByName(String name) {
                        return Optional.ofNullable(registry.get(name));
                    }

                    public Collection<ServiceRegistration> findAll() {
                        return new ArrayList<>(registry.values());
                    }

                    public void deregister(String name) {
                        ServiceRegistration removed = registry.remove(name);
                        if (removed != null) log.info("Deregistered service: {}", name);
                    }

                    public void heartbeat(String name) {
                        ServiceRegistration reg = registry.get(name);
                        if (reg != null) {
                            reg.setLastSeen(Instant.now());
                            reg.setStatus("UP");
                        }
                    }

                    @Scheduled(fixedDelay = 15_000)
                    public void pollHealth() {
                        registry.forEach((name, reg) -> {
                            boolean healthy = checkHealth(reg.getHealthUrl());
                            reg.setStatus(healthy ? "UP" : "DOWN");
                            if (healthy) {
                                reg.setLastSeen(Instant.now());
                            } else {
                                long silentMs = Instant.now().toEpochMilli() - reg.getLastSeen().toEpochMilli();
                                if (silentMs > EVICT_AFTER_MS) {
                                    log.warn("Evicting unresponsive service: {} (silent {}ms)", name, silentMs);
                                    registry.remove(name);
                                }
                            }
                        });
                    }

                    @SuppressWarnings("unchecked")
                    private boolean checkHealth(String healthUrl) {
                        if (healthUrl == null || healthUrl.isBlank()) return false;
                        try {
                            java.util.Map<String, Object> body =
                                    restTemplate.getForObject(healthUrl, java.util.Map.class);
                            return body != null && "UP".equalsIgnoreCase(String.valueOf(body.get("status")));
                        } catch (Exception e) {
                            return false;
                        }
                    }
                }
                """;

        Files.writeString(pkg.resolve("RegistryService.java"), content);
        log.debug("Generated RegistryService");
    }

    private Path createPkg(Path base, String pkg) throws IOException {
        Path p = base;
        for (String part : pkg.split("/")) p = p.resolve(part);
        Files.createDirectories(p);
        return p;
    }
}
