package org.fractalx.core.generator;

import org.fractalx.core.auth.AuthPattern;
import org.fractalx.core.auth.AuthPatternDetector;
import org.fractalx.core.auth.AuthServiceGenerator;
import org.fractalx.core.config.FractalxConfig;
import org.fractalx.core.config.FractalxConfigReader;
import org.fractalx.core.datamanagement.DistributedServiceHelper;
import org.fractalx.core.datamanagement.RepositoryAnalyzer;
import org.fractalx.core.datamanagement.SagaAnalyzer;
import org.fractalx.core.gateway.GatewayGenerator;
import org.fractalx.core.generator.admin.AdminServiceGenerator;
import org.fractalx.core.generator.registry.RegistryServiceGenerator;
import org.fractalx.core.generator.observability.OtelConfigStep;
import org.fractalx.core.generator.observability.HealthMetricsStep;
import org.fractalx.core.generator.resilience.ResilienceConfigStep;
import org.fractalx.core.generator.saga.SagaOrchestratorGenerator;
import org.fractalx.core.generator.service.ApplicationGenerator;
import org.fractalx.core.generator.service.ConfigurationGenerator;
import org.fractalx.core.generator.service.CorrelationIdGenerator;
import org.fractalx.core.generator.service.NetScopeClientGenerator;
import org.fractalx.core.generator.service.NetScopeRegistryBridgeStep;
import org.fractalx.core.generator.service.PomGenerator;
import org.fractalx.core.generator.service.DbSummaryStep;
import org.fractalx.core.generator.service.ServiceRegistrationStep;
import org.fractalx.core.generator.transformation.AnnotationRemover;
import org.fractalx.core.generator.transformation.AuthenticationPrincipalRewriterStep;
import org.fractalx.core.generator.transformation.CodeCopier;
import org.fractalx.core.generator.transformation.CodeTransformer;
import org.fractalx.core.generator.transformation.SharedCodeCopier;
import org.fractalx.core.generator.transformation.ServiceSecurityStep;
import org.fractalx.core.generator.transformation.ValuePropertyDistributorStep;
import org.fractalx.core.generator.transformation.DecompositionHintsStep;
import org.fractalx.core.gateway.SecurityAnalyzer;
import org.fractalx.core.gateway.SecurityProfile;
import org.fractalx.core.generator.transformation.FileCleanupStep;
import org.fractalx.core.generator.transformation.ImportCleaner;
import org.fractalx.core.generator.transformation.ImportPreserver;
import org.fractalx.core.generator.transformation.NetScopeClientWiringStep;
import org.fractalx.core.generator.transformation.NetScopeServerAnnotationStep;
import org.fractalx.core.generator.transformation.SagaMethodTransformer;
import org.fractalx.core.graph.DependencyGraph;
import org.fractalx.core.graph.GraphBuilder;
import org.fractalx.core.model.FractalModule;
import org.fractalx.core.model.SagaDefinition;
import org.fractalx.core.naming.NameResolver;
import org.fractalx.core.naming.NamingValidator;
import org.fractalx.core.observability.LoggerServiceGenerator;
import org.fractalx.core.observability.ObservabilityInjector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/*
 * ███████╗██████╗  █████╗  ██████╗████████╗ █████╗ ██╗     ██╗  ██╗
 * ██╔════╝██╔══██╗██╔══██╗██╔════╝╚══██╔══╝██╔══██╗██║     ╚██╗██╔╝
 * █████╗  ██████╔╝███████║██║        ██║   ███████║██║      ╚███╔╝
 * ██╔══╝  ██╔══██╗██╔══██║██║        ██║   ██╔══██║██║      ██╔██╗
 * ██║     ██║  ██║██║  ██║╚██████╗   ██║   ██║  ██║███████╗██╔╝ ██╗
 * ╚═╝     ╚═╝  ╚═╝╚═╝  ╚═╝ ╚═════╝   ╚═╝   ╚═╝  ╚═╝╚══════╝╚═╝  ╚═╝
 *
 *  Service Generation Engine  ·  fractalx-core
 *  The heart of the FractalX decomposition pipeline.
 *
 *  Copyright (c) 2025 – 2026  Project FractalX  (https://github.com/fractalx-org)
 *  Licensed under the Apache License, Version 2.0
 *
 *  @author  Sathnindu Kottage  <github.com/sathninduk>
 *  @since   0.1.0
 */

/**
 * Orchestrates generation of all microservice projects from analyzed modules.
 *
 * <p>Each generation step implements {@link ServiceFileGenerator} and receives a
 * {@link GenerationContext}. New steps can be added to the pipeline in the
 * constructor without modifying this class.
 */
public class ServiceGenerator {

    private static final Logger log = LoggerFactory.getLogger(ServiceGenerator.class);
    private static final int    GATEWAY_PORT = 9999;
    private static final String GATEWAY_DIR  = "fractalx-gateway";

    private final Path sourceRoot;
    private final Path outputRoot;

    // Ordered list of generation steps — open for extension, closed for modification
    private final List<ServiceFileGenerator> pipeline;

    private final ObservabilityInjector     observabilityInjector;
    private final DistributedServiceHelper  distributedServiceHelper;
    private final AdminServiceGenerator     adminServiceGenerator;
    private final SagaOrchestratorGenerator sagaOrchestratorGenerator;
    private final SagaAnalyzer              sagaAnalyzer;
    private final RepositoryAnalyzer        repositoryAnalyzer;
    private final RegistryServiceGenerator  registryServiceGenerator;
    private final DockerComposeGenerator    dockerComposeGenerator;

    private boolean generateGateway = true;
    private boolean generateAdmin   = true;
    private boolean generateDocker  = true;

    private java.util.function.Consumer<String> onStepStart    = lbl -> {};
    private java.util.function.Consumer<String> onStepComplete = lbl -> {};

    public ServiceGenerator withGateway(boolean v) { this.generateGateway = v; return this; }
    public ServiceGenerator withAdmin(boolean v)   { this.generateAdmin   = v; return this; }
    public ServiceGenerator withDocker(boolean v)  { this.generateDocker  = v; return this; }

    public void setProgressCallbacks(java.util.function.Consumer<String> onStart,
                                     java.util.function.Consumer<String> onComplete) {
        this.onStepStart    = onStart;
        this.onStepComplete = onComplete;
    }

    public ServiceGenerator(Path sourceRoot, Path outputRoot) {
        this.sourceRoot = sourceRoot;
        this.outputRoot = outputRoot;

        this.observabilityInjector     = new ObservabilityInjector();
        this.distributedServiceHelper  = new DistributedServiceHelper();
        this.adminServiceGenerator     = new AdminServiceGenerator();
        this.sagaOrchestratorGenerator = new SagaOrchestratorGenerator();
        this.sagaAnalyzer              = new SagaAnalyzer();
        this.repositoryAnalyzer        = new RepositoryAnalyzer();
        this.registryServiceGenerator  = new RegistryServiceGenerator();
        this.dockerComposeGenerator    = new DockerComposeGenerator();

        this.pipeline = buildPipeline();
    }

    /**
     * Constructs the ordered generation pipeline that transforms a monolithic module into a
     * fully-wired, production-ready microservice.
     *
     * <p>Each element is a {@link ServiceFileGenerator} — a single-method functional interface
     * that receives a {@link GenerationContext} and writes or transforms one logical concern.
     * Steps are intentionally kept small and composable; new capabilities are added here without
     * touching any existing step (Open/Closed Principle).
     *
     * <p><b>Pipeline stages (order is load-bearing — do not reorder without reading the notes):</b>
     *
     * <pre>
     * ── Phase 1 · Scaffolding ────────────────────────────────────────────────
     *    PomGenerator              Build descriptor with correct deps &amp; versions
     *    ApplicationGenerator      Spring Boot entry-point class
     *    ConfigurationGenerator    application.yml skeleton
     *    patchConfigurationFile    Injects observability properties into the yml
     *                              (must run after ConfigurationGenerator writes the file)
     *
     * ── Phase 2 · Source Transplantation ─────────────────────────────────────
     *    CodeCopier                Raw copy of module sources into the service tree
     *    CodeTransformer           Ordered AST-level rewrites applied in one pass:
     *      ├ AnnotationRemover       Strips monolith-only markers (@Module, etc.)
     *      ├ RelationshipDecoupler   Breaks compile-time coupling across module boundaries
     *      ├ ImportPreserver         Retains imports that the decoupler would otherwise drop
     *      └ ImportCleaner           Removes imports made redundant by the steps above
     *    FileCleanupStep           Deletes artefacts that must not exist in the service
     *
     * ── Phase 3 · NetScope Wiring ─────────────────────────────────────────────
     *    NetScopeServerAnnotationStep   Marks service endpoints for the mesh
     *    NetScopeClientGenerator        Emits typed HTTP clients for each dependency
     *    NetScopeClientWiringStep       Injects generated clients into call-sites
     *
     * ── Phase 4 · Distributed Systems ────────────────────────────────────────
     *    SagaMethodTransformer     Rewrites cross-service calls → outboxPublisher.publish()
     *    upgradeService            Adds outbox table, event models, and publisher bean
     *                              (depends on SagaMethodTransformer having identified sites)
     *
     * ── Phase 5 · Observability ───────────────────────────────────────────────
     *    CorrelationIdGenerator    Writes logback-spring.xml with %X{correlationId} pattern
     *    OtelConfigStep            OpenTelemetry agent config and span export settings
     *    HealthMetricsStep         Actuator + Micrometer custom health indicators
     *    DbSummaryStep             Datasource-level metrics and slow-query logging
     *
     * ── Phase 6 · Service Mesh ────────────────────────────────────────────────
     *    ServiceRegistrationStep       Registry self-registration config
     *    NetScopeRegistryBridgeStep    Publishes NetScope metadata to the registry
     *    ResilienceConfigStep          Circuit-breaker, retry, and bulkhead policies
     * </pre>
     *
     * @return an immutable, ordered list of generation steps
     */
    private List<ServiceFileGenerator> buildPipeline() {
        ObservabilityInjector injector = this.observabilityInjector;

        return List.of(
                new PomGenerator(injector),
                new ApplicationGenerator(),
                new ConfigurationGenerator(),
                context -> {
                    if (context.getFractalxConfig().features().observability()) {
                        injector.patchConfigurationFile(context.getSrcMainResources());
                    }
                },
                new CodeCopier(),
                new SharedCodeCopier(),
                new ServiceSecurityStep(),           // Phase 2.5: generate per-service security config
                new ValuePropertyDistributorStep(),  // distribute @Value/${} props from monolith config
                new AuthenticationPrincipalRewriterStep(), // rewrite @AuthenticationPrincipal types → GatewayPrincipal
                new CodeTransformer(
                        new AnnotationRemover(),
                        new org.fractalx.core.datamanagement.RelationshipDecoupler(),
                        new ImportPreserver(),
                        new ImportCleaner()
                ),
                new FileCleanupStep(),
                new NetScopeServerAnnotationStep(),
                new NetScopeClientGenerator(),
                new NetScopeClientWiringStep(),
                new DecompositionHintsStep(),        // detect @Transactional/cache/event/aspect/scheduler patterns
                new SagaMethodTransformer(),    // replaces cross-service calls with outboxPublisher.publish()
                context -> {
                    if (context.getFractalxConfig().features().distributedData()) {
                        distributedServiceHelper.upgradeService(
                                context.getModule(), context.getSourceRoot(), context.getServiceRoot(),
                                context.getSagaDefinitions(), context.servicePackage(),
                                context.getFractalxConfig().springBootVersion());
                    }
                },
                new CorrelationIdGenerator(),    // generates logback-spring.xml with %X{correlationId}
                new OtelConfigStep(),
                new HealthMetricsStep(),
                new DbSummaryStep(),
                new ServiceRegistrationStep(),
                new NetScopeRegistryBridgeStep(),
                new ResilienceConfigStep()
        );
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public void generateServices(List<FractalModule> modules) throws IOException {
        log.debug("Starting code generation for {} modules", modules.size());
        // sourceRoot is typically src/main/java; resolve up to the Maven project root
        // so FractalxConfigReader can find src/main/resources/fractalx-config.yml and pom.xml
        Path projectRoot = resolveProjectRoot(sourceRoot);
        FractalxConfig fractalxConfig = new FractalxConfigReader()
                .read(projectRoot.resolve("src/main/resources"), projectRoot);
        Files.createDirectories(outputRoot);

        // Run repository boundary analysis before generation (warnings only — never fails)
        RepositoryAnalyzer.RepositoryReport repoReport = repositoryAnalyzer.analyze(sourceRoot, modules);
        if (repoReport.hasViolations()) {
            log.warn("Repository boundary violations detected — review DATA_README.md for guidance");
        }

        // Build the NameResolver from config and run pre-pipeline naming validation
        NameResolver nameResolver = new NameResolver(fractalxConfig.naming());
        new NamingValidator(nameResolver).validate(modules);

        // Build the unified DependencyGraph from source (Phase 1 — deterministic, no heuristics)
        DependencyGraph dependencyGraph = new GraphBuilder().build(sourceRoot);
        log.info("DependencyGraph built: {} nodes, {} edges",
                dependencyGraph.allNodes().size(), dependencyGraph.allEdges().size());

        // Detect @DistributedSaga definitions across all modules
        List<SagaDefinition> sagaDefinitions = sagaAnalyzer.analyzeSagas(sourceRoot, modules);

        // Detect monolith Spring Security configuration (used by ServiceSecurityStep + DecompositionHintsStep)
        SecurityProfile securityProfile = SecurityProfile.none();
        try {
            securityProfile = new SecurityAnalyzer().analyze(
                    sourceRoot, sourceRoot.getParent().resolve("resources"), dependencyGraph);
            log.info("Monolith security profile detected: authType={}", securityProfile.authType());
        } catch (Exception e) {
            log.debug("SecurityAnalyzer could not scan monolith — treating as no-security: {}", e.getMessage());
        }

        // Generate the service registry first so it is available for all other generators
        if (fractalxConfig.features().registry()) {
            onStepStart.accept("fractalx-registry");
            registryServiceGenerator.generate(modules, outputRoot, fractalxConfig);
            onStepComplete.accept("fractalx-registry");
        } else {
            log.info("Feature 'registry' disabled — skipping fractalx-registry generation");
        }

        for (FractalModule module : modules) {
            onStepStart.accept(module.getServiceName());
            generateService(module, modules, fractalxConfig, sagaDefinitions, securityProfile, nameResolver, dependencyGraph);
            onStepComplete.accept(module.getServiceName());
        }

        // Detect monolith auth pattern and optionally generate a standalone auth-service
        AuthPattern authPattern = AuthPattern.none();
        try {
            authPattern = new AuthPatternDetector(projectRoot, dependencyGraph).detect();
            if (authPattern.detected()) {
                log.info("Auth pattern detected — generating auth-service");
                onStepStart.accept("auth-service");
                new AuthServiceGenerator().generate(outputRoot, authPattern, fractalxConfig);
                onStepComplete.accept("auth-service");
            }
        } catch (Exception e) {
            log.warn("Auth pattern detection/generation failed — skipping auth-service: {}", e.getMessage());
        }

        if (fractalxConfig.features().logger()) {
            new LoggerServiceGenerator().generate(outputRoot, fractalxConfig);
        } else {
            log.info("Feature 'logger' disabled — skipping logger-service generation");
        }

        if (modules.size() > 1 && generateGateway && fractalxConfig.features().gateway()) {
            onStepStart.accept("fractalx-gateway");
            generateApiGateway(modules, fractalxConfig, authPattern);
            onStepComplete.accept("fractalx-gateway");
        } else if (!fractalxConfig.features().gateway()) {
            log.info("Feature 'gateway' disabled — skipping fractalx-gateway generation");
        }

        if (generateAdmin && fractalxConfig.features().admin()) {
            onStepStart.accept("fractalx-admin");
            adminServiceGenerator.generateAdminService(modules, outputRoot, sourceRoot, fractalxConfig, sagaDefinitions, authPattern);
            onStepComplete.accept("fractalx-admin");
        } else if (!fractalxConfig.features().admin()) {
            log.info("Feature 'admin' disabled — skipping admin-service generation");
        }

        boolean hasSagas = !sagaDefinitions.isEmpty();
        if (hasSagas && fractalxConfig.features().saga()) {
            onStepStart.accept("fractalx-saga-orchestrator");
            sagaOrchestratorGenerator.generateOrchestratorService(modules, sagaDefinitions, outputRoot, fractalxConfig);
            onStepComplete.accept("fractalx-saga-orchestrator");
        } else if (!fractalxConfig.features().saga()) {
            log.info("Feature 'saga' disabled — skipping fractalx-saga-orchestrator generation");
        }

        if (generateDocker && fractalxConfig.features().docker()) {
            onStepStart.accept("docker-compose + scripts");
            dockerComposeGenerator.generate(modules, outputRoot, hasSagas, sagaDefinitions, fractalxConfig);
            generateStartScripts(modules, sagaDefinitions, authPattern);
            onStepComplete.accept("docker-compose + scripts");
        } else {
            if (!fractalxConfig.features().docker()) {
                log.info("Feature 'docker' disabled — skipping docker-compose generation");
            }
            onStepStart.accept("start scripts");
            generateStartScripts(modules, sagaDefinitions, authPattern);
            onStepComplete.accept("start scripts");
        }

        log.debug("Code generation complete!");
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void generateService(FractalModule module, List<FractalModule> allModules,
                                  FractalxConfig fractalxConfig,
                                  List<SagaDefinition> sagaDefinitions,
                                  SecurityProfile securityProfile,
                                  NameResolver nameResolver,
                                  DependencyGraph dependencyGraph) throws IOException {
        log.debug("Generating service: {}", module.getServiceName());

        Path serviceRoot = outputRoot.resolve(module.getServiceName());
        Files.createDirectories(serviceRoot.resolve("src/main/java"));
        Files.createDirectories(serviceRoot.resolve("src/main/resources"));
        Files.createDirectories(serviceRoot.resolve("src/test/java"));

        GenerationContext context = new GenerationContext(
                module, sourceRoot, serviceRoot, allModules, fractalxConfig, sagaDefinitions,
                securityProfile, nameResolver, dependencyGraph);

        for (ServiceFileGenerator step : pipeline) {
            step.generate(context);
        }

        log.debug("Generated: {}", module.getServiceName());
    }

    private void generateApiGateway(List<FractalModule> modules,
                                     FractalxConfig fractalxConfig) throws IOException {
        generateApiGateway(modules, fractalxConfig, AuthPattern.none());
    }

    private void generateApiGateway(List<FractalModule> modules,
                                     FractalxConfig fractalxConfig,
                                     AuthPattern authPattern) throws IOException {
        log.debug("Generating API Gateway for {} services", modules.size());
        try {
            new GatewayGenerator(sourceRoot, outputRoot, fractalxConfig).generateGateway(modules, authPattern);
            log.debug("Generated API Gateway — http://localhost:{}", GATEWAY_PORT);
            updateReadmeWithGatewayInfo(modules);
        } catch (Exception e) {
            log.error("Failed to generate API Gateway: {}", e.getMessage(), e);
            log.warn("Continuing without API Gateway...");
        }
    }

    private void generateStartScripts(List<FractalModule> modules,
                                       List<SagaDefinition> sagaDefinitions) throws IOException {
        generateStartScripts(modules, sagaDefinitions, AuthPattern.none());
    }

    private void generateStartScripts(List<FractalModule> modules,
                                       List<SagaDefinition> sagaDefinitions,
                                       AuthPattern authPattern) throws IOException {
        Path gatewayPath = outputRoot.resolve(GATEWAY_DIR);
        boolean hasSaga  = !sagaDefinitions.isEmpty();
        boolean hasAuth  = authPattern != null && authPattern.detected();

        generateStartScript(modules, gatewayPath, hasSaga, hasAuth);
        generateStopScript(gatewayPath, hasSaga);
        generateWindowsStartScript(modules, gatewayPath, hasSaga);
        generateWindowsStopScript(hasSaga);
        generateReadme(modules, hasSaga);

        log.debug("Generated start/stop scripts (Unix + Windows)");
    }

    private void generateStartScript(List<FractalModule> modules, Path gatewayPath,
                                      boolean hasSagaOrchestrator) throws IOException {
        generateStartScript(modules, gatewayPath, hasSagaOrchestrator, false);
    }

    private void generateStartScript(List<FractalModule> modules, Path gatewayPath,
                                      boolean hasSagaOrchestrator, boolean hasAuthService) throws IOException {
        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n\n");
        script.append("echo \"Starting all FractalX microservices...\"\n\n");
        script.append("# Start fractalx-registry first\n");
        script.append("echo \"Starting fractalx-registry on port 8761...\"\n");
        script.append("cd fractalx-registry && mvn spring-boot:run > ../fractalx-registry.log 2>&1 &\n");
        script.append("cd ..\n");
        script.append("echo \"Waiting 5s for registry to become ready...\"\n");
        script.append("sleep 5\n\n");
        script.append("# Start all microservices\n");

        for (FractalModule module : modules) {
            script.append(String.format("echo \"Starting %s on port %d...\"\n",
                    module.getServiceName(), module.getPort()));
            script.append(String.format("cd %s && mvn spring-boot:run > ../%s.log 2>&1 &\n",
                    module.getServiceName(), module.getServiceName()));
            script.append("cd ..\n\n");
        }

        if (hasAuthService) {
            script.append("echo \"Starting auth-service on port 8090...\"\n");
            script.append("cd auth-service && mvn spring-boot:run > ../auth-service.log 2>&1 &\n");
            script.append("cd ..\n\n");
        }

        if (hasSagaOrchestrator) {
            script.append("echo \"Starting Saga Orchestrator...\"\n");
            script.append("cd fractalx-saga-orchestrator && mvn spring-boot:run > ../fractalx-saga-orchestrator.log 2>&1 &\n");
            script.append("cd ..\n\n");
        }

        if (Files.exists(gatewayPath)) {
            script.append("echo \"All services and gateway started successfully!\"\n");
            script.append(String.format("echo \"Gateway URL: http://localhost:%d\"\n", GATEWAY_PORT));
        } else {
            script.append("echo \"All services started. Check logs in *.log files\"\n");
        }

        script.append("echo \"To stop all services, run: ./stop-all.sh\"\n\n");
        script.append("echo \"Service URLs:\"\n");
        if (hasAuthService) {
            script.append(String.format("echo \"  %-20s http://localhost:8090\"\n", "auth-service:"));
        }
        for (FractalModule module : modules) {
            script.append(String.format("echo \"  %-20s http://localhost:%d\"\n",
                    module.getServiceName() + ":", module.getPort()));
        }
        if (hasSagaOrchestrator) {
            script.append(String.format("echo \"  %-20s http://localhost:8099\"\n", "saga-orchestrator:"));
        }

        Path scriptPath = outputRoot.resolve("start-all.sh");
        Files.writeString(scriptPath, script.toString());
        scriptPath.toFile().setExecutable(true);
    }

    private void generateStopScript(Path gatewayPath, boolean hasSagaOrchestrator) throws IOException {
        StringBuilder script = new StringBuilder();
        script.append("#!/bin/bash\n\n");
        script.append("echo \"Stopping all FractalX microservices...\"\n\n");

        if (Files.exists(gatewayPath)) {
            script.append("echo \"Stopping API Gateway...\"\n");
            script.append(String.format("pkill -f \"%s\"\n", GATEWAY_DIR));
            script.append("sleep 2\n\n");
        }

        if (hasSagaOrchestrator) {
            script.append("echo \"Stopping Saga Orchestrator...\"\n");
            script.append("pkill -f \"fractalx-saga-orchestrator\" || true\n");
            script.append("sleep 1\n\n");
        }

        script.append("echo \"Stopping all services...\"\n");
        script.append("pkill -f \"spring-boot:run\"\n\n");
        script.append("echo \"All services stopped.\"\n");

        Path scriptPath = outputRoot.resolve("stop-all.sh");
        Files.writeString(scriptPath, script.toString());
        scriptPath.toFile().setExecutable(true);
    }

    private void generateWindowsStartScript(List<FractalModule> modules, Path gatewayPath,
                                             boolean hasSagaOrchestrator) throws IOException {
        StringBuilder bat = new StringBuilder();
        bat.append("@echo off\r\n");
        bat.append("setlocal\r\n\r\n");
        bat.append("echo Starting all FractalX microservices...\r\n\r\n");

        bat.append("echo Starting fractalx-registry on port 8761...\r\n");
        bat.append("start \"fractalx-registry\" /d \"%~dp0fractalx-registry\" cmd /c ");
        bat.append("\"mvn spring-boot:run > ..\\fractalx-registry.log 2>&1\"\r\n");
        bat.append("echo Waiting 5 seconds for registry to become ready...\r\n");
        bat.append("timeout /t 5 /nobreak > nul\r\n\r\n");

        bat.append("echo Starting microservices...\r\n");
        for (FractalModule module : modules) {
            bat.append(String.format("echo Starting %s on port %d...%n",
                    module.getServiceName(), module.getPort()).replace("\n", "\r\n"));
            bat.append(String.format(
                    "start \"%s\" /d \"%%~dp0%s\" cmd /c \"mvn spring-boot:run > ..\\%s.log 2>&1\"\r\n",
                    module.getServiceName(), module.getServiceName(), module.getServiceName()));
        }

        if (hasSagaOrchestrator) {
            bat.append("\r\necho Starting Saga Orchestrator...\r\n");
            bat.append("start \"fractalx-saga-orchestrator\" /d \"%~dp0fractalx-saga-orchestrator\" ");
            bat.append("cmd /c \"mvn spring-boot:run > ..\\fractalx-saga-orchestrator.log 2>&1\"\r\n");
        }

        bat.append("\r\necho.\r\n");
        if (Files.exists(gatewayPath)) {
            bat.append(String.format("echo All services started. Gateway: http://localhost:%d%n", GATEWAY_PORT)
                    .replace("\n", "\r\n"));
        } else {
            bat.append("echo All services started. Check *.log files for output.\r\n");
        }
        bat.append("echo To stop all services, run: stop-all.bat\r\n");
        bat.append("echo.\r\n");
        bat.append("echo Service URLs:\r\n");
        for (FractalModule module : modules) {
            bat.append(String.format("echo   %-22s http://localhost:%d%n",
                    module.getServiceName() + ":", module.getPort()).replace("\n", "\r\n"));
        }
        if (hasSagaOrchestrator) {
            bat.append(String.format("echo   %-22s http://localhost:8099%n", "saga-orchestrator:")
                    .replace("\n", "\r\n"));
        }

        Files.writeString(outputRoot.resolve("start-all.bat"), bat.toString());
    }

    private void generateWindowsStopScript(boolean hasSagaOrchestrator) throws IOException {
        StringBuilder bat = new StringBuilder();
        bat.append("@echo off\r\n\r\n");
        bat.append("echo Stopping all FractalX microservices...\r\n\r\n");

        // Use PowerShell to find and kill java processes started by spring-boot:run.
        // Get-CimInstance is available on PowerShell 3+ (Windows 7 SP1 and later).
        bat.append("powershell -NoProfile -Command ^\r\n");
        bat.append("  \"Get-CimInstance Win32_Process ^\r\n");
        bat.append("   | Where-Object { $_.CommandLine -like '*spring-boot:run*' } ^\r\n");
        bat.append("   | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }\"\r\n");
        bat.append("\r\necho All services stopped.\r\n");

        Files.writeString(outputRoot.resolve("stop-all.bat"), bat.toString());
    }

    private void generateReadme(List<FractalModule> modules, boolean hasSagaOrchestrator) throws IOException {
        StringBuilder readme = new StringBuilder();
        readme.append("# FractalX Generated Microservices\n\n");
        readme.append("This directory contains microservices generated by FractalX.\n\n");

        readme.append("## Services\n\n");
        for (FractalModule module : modules) {
            readme.append(String.format("- **%s**: http://localhost:%d\n",
                    module.getServiceName(), module.getPort()));
        }
        if (hasSagaOrchestrator) {
            readme.append("- **fractalx-saga-orchestrator**: http://localhost:8099\n");
        }

        readme.append("\n## Quick Start\n\n");
        readme.append("**Unix / macOS / WSL**\n```bash\n./start-all.sh\n```\n\n");
        readme.append("**Windows (cmd / PowerShell)**\n```bat\nstart-all.bat\n```\n\n");

        readme.append("### Start services individually\n\n");
        for (int i = 0; i < modules.size(); i++) {
            FractalModule module = modules.get(i);
            readme.append(String.format("```bash\n# Terminal %d - Start %s\ncd %s\nmvn spring-boot:run\n```\n\n",
                    i + 1, module.getServiceName(), module.getServiceName()));
        }

        readme.append("## Testing\n\n");
        for (FractalModule module : modules) {
            readme.append(String.format("```bash\n# Test %s health\ncurl http://localhost:%d/actuator/health\n```\n\n",
                    module.getServiceName(), module.getPort()));
        }

        readme.append("## Stopping Services\n\n");
        readme.append("**Unix / macOS / WSL**\n```bash\n./stop-all.sh\n```\n\n");
        readme.append("**Windows**\n```bat\nstop-all.bat\n```\n\n");

        readme.append("## Service Architecture\n\n```\n");
        readme.append("Standalone Services (No Service Discovery)\n");
        readme.append("└── Static Gateway Routing\n");
        for (FractalModule module : modules) {
            readme.append(String.format("    ├── %s (:%d)\n", module.getServiceName(), module.getPort()));
            if (!module.getDependencies().isEmpty()) {
                readme.append(String.format("    │   └─ Dependencies: %s\n",
                        String.join(", ", module.getDependencies())));
            }
        }
        readme.append(String.format("    └── Gateway → http://localhost:%d\n", GATEWAY_PORT));
        readme.append("```\n");

        Files.writeString(outputRoot.resolve("README.md"), readme.toString());
        log.debug("Generated README.md");
    }

    private void updateReadmeWithGatewayInfo(List<FractalModule> modules) throws IOException {
        Path readmePath = outputRoot.resolve("README.md");
        if (!Files.exists(readmePath)) {
            log.warn("README.md not found, skipping gateway info update");
            return;
        }

        StringBuilder gatewaySection = new StringBuilder();
        gatewaySection.append("\n## API Gateway (Static Routing)\n\n");
        gatewaySection.append("FractalX generates a simple API Gateway providing a unified entry point.\n\n");
        gatewaySection.append(String.format(
                "- **Unified API**: Single entry point at `http://localhost:%d`\n", GATEWAY_PORT));
        gatewaySection.append("- **Static Routing**: Direct routing based on configured port numbers\n\n");

        gatewaySection.append("**Examples:**\n\n");
        for (FractalModule module : modules) {
            String servicePath = module.getServiceName().replace("-service", "");
            gatewaySection.append(String.format("- **%s**\n", module.getServiceName()));
            gatewaySection.append(String.format("  - Direct:  `http://localhost:%d/api/%s/health`\n",
                    module.getPort(), servicePath));
            gatewaySection.append(String.format("  - Gateway: `http://localhost:%d/api/%s/health`\n\n",
                    GATEWAY_PORT, servicePath));
        }

        String existing = Files.readString(readmePath);
        String updated  = existing.replace(
                "## Services\n\n",
                "## Services\n\n" + gatewaySection + "\n"
        );
        Files.writeString(readmePath, updated);
        log.debug("Updated README with gateway information");
    }

    /**
     * Resolves the Maven project root from a source path.
     *
     * <p>When the plugin passes {@code ${project.build.sourceDirectory}} (typically
     * {@code src/main/java}), we navigate up three levels to reach the project root
     * where {@code pom.xml} and {@code src/main/resources} live.
     * If the resolved path does not look like a project root (no {@code pom.xml}),
     * we fall back to {@code sourceRoot} itself so callers that already pass the
     * project root continue to work correctly.
     */
    private static Path resolveProjectRoot(Path sourceRoot) {
        // Try walking up 3 levels (src/main/java → src/main → src → project root)
        Path candidate = sourceRoot;
        for (int i = 0; i < 3; i++) {
            Path parent = candidate.getParent();
            if (parent == null) break;
            candidate = parent;
            if (Files.exists(candidate.resolve("pom.xml"))) {
                return candidate;
            }
        }
        // Fallback: caller may have already passed the project root
        return sourceRoot;
    }
}
