package org.fractalx.core.generator.service;

import org.fractalx.core.generator.GenerationContext;
import org.fractalx.core.generator.ServiceFileGenerator;
import org.fractalx.core.model.FractalModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pipeline step that generates the self-registration client in each generated
 * microservice. On startup the service POSTs its coordinates to fractalx-registry;
 * on shutdown it sends a deregister request. A heartbeat runs every 30 seconds.
 */
public class ServiceRegistrationStep implements ServiceFileGenerator {

    private static final Logger log = LoggerFactory.getLogger(ServiceRegistrationStep.class);
    @Override
    public void generate(GenerationContext context) throws IOException {
        FractalModule module = context.getModule();
        log.debug("Generating service registration for {}", module.getServiceName());

        String generatedPkg = context.servicePackage();
        Path pkgPath = resolvePackage(context.getSrcMainJava(), generatedPkg);

        generateRegistryClient(pkgPath, generatedPkg, module);
        generateAutoConfig(pkgPath, generatedPkg, module);
        generateGrpcFaultTolerance(pkgPath, generatedPkg);

        log.debug("Generated service registration for {}", module.getServiceName());
    }

    private void generateRegistryClient(Path pkgPath, String pkg, FractalModule module) throws IOException {
        String content = """
                package %s;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.stereotype.Component;
                import org.springframework.web.client.RestTemplate;

                import java.util.Map;

                @Component
                public class FractalRegistryClient {

                    private static final Logger log = LoggerFactory.getLogger(FractalRegistryClient.class);

                    @Value("${fractalx.registry.url:http://localhost:8761}")
                    private String registryUrl;

                    private final RestTemplate restTemplate = new RestTemplate();

                    public void register(String name, String host, int port, int grpcPort, String healthUrl) {
                        try {
                            Map<String, Object> payload = Map.of(
                                    "name", name,
                                    "host", host,
                                    "port", port,
                                    "grpcPort", grpcPort,
                                    "healthUrl", healthUrl
                            );
                            restTemplate.postForObject(registryUrl + "/services", payload, Object.class);
                            log.info("Registered with fractalx-registry: {} at {}:{}", name, host, port);
                        } catch (Exception e) {
                            log.warn("Could not register with fractalx-registry at '{}': {} "
                                    + "— verify that 'fractalx.registry.url' is correctly set in application.yml "
                                    + "and that fractalx-registry is running.", registryUrl, e.getMessage());
                        }
                    }

                    public void deregister(String name) {
                        try {
                            restTemplate.delete(registryUrl + "/services/" + name + "/deregister");
                            log.info("Deregistered from fractalx-registry: {}", name);
                        } catch (Exception e) {
                            log.warn("Could not deregister from fractalx-registry: {}", e.getMessage());
                        }
                    }

                    public boolean heartbeat(String name) {
                        try {
                            restTemplate.postForObject(registryUrl + "/services/" + name + "/heartbeat",
                                    null, Void.class);
                            return true;
                        } catch (Exception e) {
                            log.trace("Heartbeat failed for {}: {}", name, e.getMessage());
                            return false;
                        }
                    }
                }
                """.formatted(pkg);
        Files.writeString(pkgPath.resolve("FractalRegistryClient.java"), content);
    }

    private void generateAutoConfig(Path pkgPath, String pkg, FractalModule module) throws IOException {
        int grpcPort = module.grpcPort();
        String content = """
                package %s;

                import jakarta.annotation.PostConstruct;
                import jakarta.annotation.PreDestroy;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
                import org.springframework.scheduling.annotation.EnableScheduling;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                @EnableScheduling
                @ConditionalOnProperty(name = "fractalx.registry.enabled", havingValue = "true", matchIfMissing = true)
                public class ServiceRegistrationAutoConfig {

                    private final FractalRegistryClient registryClient;

                    @Value("${spring.application.name:%s}")
                    private String serviceName;

                    @Value("${fractalx.registry.host:localhost}")
                    private String serviceHost;

                    @Value("${server.port:%d}")
                    private int httpPort;

                    public ServiceRegistrationAutoConfig(FractalRegistryClient registryClient) {
                        this.registryClient = registryClient;
                    }

                    @PostConstruct
                    public void onStartup() {
                        String healthUrl = "http://" + serviceHost + ":" + httpPort + "/actuator/health";
                        registryClient.register(serviceName, serviceHost, httpPort, %d, healthUrl);
                    }

                    @Scheduled(fixedDelay = 5_000)
                    public void sendHeartbeat() {
                        boolean ok = registryClient.heartbeat(serviceName);
                        if (!ok) {
                            onStartup();
                        }
                    }

                    @PreDestroy
                    public void onShutdown() {
                        registryClient.deregister(serviceName);
                    }
                }
                """.formatted(pkg, module.getServiceName(), module.getPort(), grpcPort);
        Files.writeString(pkgPath.resolve("ServiceRegistrationAutoConfig.java"), content);
    }

    /**
     * Generates a {@code BeanDefinitionRegistryPostProcessor} that silently removes the
     * {@code netScopeGrpcServer} bean if its port is already in use.
     *
     * <p>Root cause: the smoke-test runner leaves per-service instances alive when it starts
     * the integration phase. Both instances try to bind the same gRPC port, the second one
     * throws {@code java.net.BindException}, which propagates as {@code BeanCreationException}
     * and crashes the entire application context. This guard prevents that crash by removing
     * the gRPC server bean before any beans are instantiated, allowing the service to start
     * cleanly, self-register, and serve HTTP traffic without NetScope gRPC.
     */
    private void generateGrpcFaultTolerance(Path pkgPath, String pkg) throws IOException {
        String content = """
                package %s;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
                import org.springframework.beans.factory.support.BeanDefinitionRegistry;
                import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
                import org.springframework.context.EnvironmentAware;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.core.env.Environment;

                import java.io.IOException;
                import java.net.ServerSocket;

                /**
                 * Removes the {@code netScopeGrpcServer} bean if its gRPC port is already bound.
                 * Prevents {@code BindException} from crashing the context when another instance
                 * of this service is still alive (e.g. leftover from a per-service smoke-test run).
                 */
                @Configuration(proxyBeanMethods = false)
                public class NetScopeGrpcFaultTolerance
                        implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

                    private static final Logger log =
                            LoggerFactory.getLogger(NetScopeGrpcFaultTolerance.class);
                    private static final String GRPC_BEAN = "netScopeGrpcServer";

                    private Environment environment;

                    @Override
                    public void setEnvironment(Environment environment) {
                        this.environment = environment;
                    }

                    @Override
                    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                        int port = environment.getProperty(
                                "netscope.server.grpc.port", Integer.class, 0);
                        if (port <= 0 || isPortFree(port)) return;

                        log.warn("gRPC port {} already in use — disabling {} bean to avoid " +
                                 "BindException. This instance will serve HTTP only.", port, GRPC_BEAN);
                        if (registry.containsBeanDefinition(GRPC_BEAN)) {
                            registry.removeBeanDefinition(GRPC_BEAN);
                        }
                    }

                    @Override
                    public void postProcessBeanFactory(ConfigurableListableBeanFactory bf) {
                        // no-op
                    }

                    private static boolean isPortFree(int port) {
                        try (ServerSocket ss = new ServerSocket(port)) {
                            ss.setReuseAddress(true);
                            return true;
                        } catch (IOException e) {
                            return false;
                        }
                    }
                }
                """.formatted(pkg);
        Files.writeString(pkgPath.resolve("NetScopeGrpcFaultTolerance.java"), content);
    }

    private Path resolvePackage(Path base, String pkg) throws IOException {
        Path p = base;
        for (String part : pkg.split("\\.")) p = p.resolve(part);
        Files.createDirectories(p);
        return p;
    }

    private String toJavaId(String serviceName) {
        StringBuilder sb = new StringBuilder();
        for (String part : serviceName.split("-")) {
            if (!part.isEmpty()) sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
