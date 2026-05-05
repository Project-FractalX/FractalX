package org.fractalx.core.generator;

import org.fractalx.core.config.FractalxConfig;
import org.fractalx.core.gateway.SecurityProfile;
import org.fractalx.core.graph.DependencyGraph;
import org.fractalx.core.model.FractalModule;
import org.fractalx.core.model.SagaDefinition;
import org.fractalx.core.naming.NameResolver;

import java.nio.file.Path;
import java.util.List;

/**
 * Carries all inputs needed by a {@link ServiceFileGenerator} step.
 * Provides convenience accessors for common sub-directories.
 */
public final class GenerationContext {

    private final FractalModule module;
    private final Path sourceRoot;
    private final Path serviceRoot;
    private final List<FractalModule> allModules;
    private final FractalxConfig fractalxConfig;
    private final List<SagaDefinition> sagaDefinitions;
    private final SecurityProfile securityProfile;  // nullable — null means no security detected
    private final NameResolver nameResolver;
    private final DependencyGraph dependencyGraph;  // nullable — null when graph is not yet built

    /** Full constructor. */
    public GenerationContext(FractalModule module,
                             Path sourceRoot,
                             Path serviceRoot,
                             List<FractalModule> allModules,
                             FractalxConfig fractalxConfig,
                             List<SagaDefinition> sagaDefinitions,
                             SecurityProfile securityProfile,
                             NameResolver nameResolver,
                             DependencyGraph dependencyGraph) {
        this.module = module;
        this.sourceRoot = sourceRoot;
        this.serviceRoot = serviceRoot;
        this.allModules = List.copyOf(allModules);
        this.fractalxConfig = fractalxConfig;
        this.sagaDefinitions = List.copyOf(sagaDefinitions);
        this.securityProfile = securityProfile;
        this.nameResolver = nameResolver != null ? nameResolver
                : new NameResolver(fractalxConfig.naming());
        this.dependencyGraph = dependencyGraph;
    }

    /** Constructor without DependencyGraph — backward compatible. */
    public GenerationContext(FractalModule module,
                             Path sourceRoot,
                             Path serviceRoot,
                             List<FractalModule> allModules,
                             FractalxConfig fractalxConfig,
                             List<SagaDefinition> sagaDefinitions,
                             SecurityProfile securityProfile,
                             NameResolver nameResolver) {
        this(module, sourceRoot, serviceRoot, allModules, fractalxConfig, sagaDefinitions,
                securityProfile, nameResolver, null);
    }

    /** Backward-compatible 7-arg constructor (securityProfile supplied, nameResolver derived from config). */
    public GenerationContext(FractalModule module,
                             Path sourceRoot,
                             Path serviceRoot,
                             List<FractalModule> allModules,
                             FractalxConfig fractalxConfig,
                             List<SagaDefinition> sagaDefinitions,
                             SecurityProfile securityProfile) {
        this(module, sourceRoot, serviceRoot, allModules, fractalxConfig, sagaDefinitions,
                securityProfile, null);
    }

    /** Backward-compatible 6-arg constructor (for tests). Passes null for securityProfile. */
    public GenerationContext(FractalModule module,
                             Path sourceRoot,
                             Path serviceRoot,
                             List<FractalModule> allModules,
                             FractalxConfig fractalxConfig,
                             List<SagaDefinition> sagaDefinitions) {
        this(module, sourceRoot, serviceRoot, allModules, fractalxConfig, sagaDefinitions, null);
    }

    public FractalModule getModule()                   { return module; }
    public Path getSourceRoot()                        { return sourceRoot; }
    public Path getServiceRoot()                       { return serviceRoot; }
    public List<FractalModule> getAllModules()          { return allModules; }
    public FractalxConfig getFractalxConfig()          { return fractalxConfig; }
    public List<SagaDefinition> getSagaDefinitions()   { return sagaDefinitions; }
    public SecurityProfile getSecurityProfile()        { return securityProfile; }
    public NameResolver getNameResolver()              { return nameResolver; }
    public DependencyGraph getDependencyGraph()         { return dependencyGraph; }

    /**
     * Returns {@code true} if the monolith had Spring Security enabled
     * (i.e. a non-NONE auth type was detected by {@link org.fractalx.core.gateway.SecurityAnalyzer}).
     */
    public boolean isSecurityEnabled() {
        return securityProfile != null
                && securityProfile.authType() != SecurityProfile.AuthType.NONE;
    }

    /** Resolves {@code src/main/java} under the service root. */
    public Path getSrcMainJava()      { return serviceRoot.resolve("src/main/java"); }

    /** Resolves {@code src/main/resources} under the service root. */
    public Path getSrcMainResources() { return serviceRoot.resolve("src/main/resources"); }

    /**
     * Returns the base Java package for generated infrastructure classes,
     * derived from the monolith's groupId or explicit fractalx-config.yml setting.
     * Example: {@code "com.acme.generated"}
     */
    public String basePackage() {
        return fractalxConfig.effectiveBasePackage();
    }

    /**
     * Returns the package for infrastructure classes specific to this service.
     * Example: if basePackage is {@code "com.acme.generated"} and the service is
     * {@code "order-service"}, returns {@code "com.acme.generated.orderservice"}.
     */
    public String servicePackage() {
        return basePackage() + "." + module.getServiceName().replace("-", "");
    }
}
