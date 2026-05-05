# FractalX

[![Maven Central](https://img.shields.io/maven-central/v/org.fractalx/fractalx-annotations)](https://central.sonatype.com/artifact/org.fractalx/fractalx-annotations)
[![License](https://img.shields.io/badge/license-Apache%202.0-green)](LICENSE)
[![Java](https://img.shields.io/badge/java-21%2B-orange)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/spring--boot-3.2%2B%20%7C%204.x-brightgreen)](https://spring.io/projects/spring-boot)

**Static decomposition framework — converts modular monolithic Spring Boot applications into production-ready microservice deployments via AST analysis and code generation.**

> No runtime agent. No bytecode manipulation. No manual wiring. Add three annotations, run one command, get a fully operational microservice platform.

---

## Why FractalX?

Manual microservices migrations are slow, error-prone, and expensive — requiring weeks of boilerplate wiring, container setup, and cross-cutting concerns like observability, resilience, and service discovery. FractalX eliminates that entirely:

| Capability | Without FractalX | With FractalX |
|---|---|---|
| Service scaffolding | Days per service | Seconds — fully generated |
| Inter-service communication | Write gRPC stubs manually | Auto-generated `@NetScopeClient` interfaces |
| Distributed tracing | Instrument every service | Auto-injected `OtelConfig.java` per service |
| Service discovery | Configure Eureka/Consul | Lightweight `fractalx-registry` auto-generated |
| Circuit breaking & retry | Configure Resilience4j per service | Auto-generated per dependency |
| Database isolation | Manually split schemas | `DataIsolationGenerator` + Flyway scaffolds |
| Distributed sagas | Write orchestrator from scratch | Full saga service from `@DistributedSaga` |
| Docker deployment | Write Dockerfiles + Compose | Generated `Dockerfile` + `docker-compose.yml` |
| Admin dashboard | Build your own | 15-section ops dashboard auto-generated |

---

## Table of Contents

1. [Quick Start -- 5 Minutes](#1-quick-start----5-minutes)
2. [How It Works](#2-how-it-works)
3. [Prerequisites](#3-prerequisites)
4. [Build the Framework](#4-build-the-framework)
5. [Annotate Your Monolith](#5-annotate-your-monolith)
6. [Pre-Decomposition Configuration](#6-pre-decomposition-configuration)
    - [Naming Conventions](#naming-conventions)
    - [Feature Flags](#feature-flags)
7. [Running FractalX](#7-running-fractalx)
8. [Generated Output](#8-generated-output)
9. [Start Generated Services](#9-start-generated-services)
10. [Docker Deployment](#10-docker-deployment)
11. [API Gateway](#11-api-gateway)
12. [Service Discovery](#12-service-discovery)
13. [Gateway Security](#13-gateway-security)
14. [Resilience](#14-resilience)
15. [Data Consistency](#15-data-consistency)
16. [Observability & Monitoring](#16-observability--monitoring)
17. [Admin Dashboard](#17-admin-dashboard)
18. [Static Verification](#18-static-verification)
    - [Decomposition Hints](#decomposition-hints)
19. [Test Endpoints](#19-test-endpoints)
20. [Configuration Reference](#20-configuration-reference)
21. [Troubleshooting](#21-troubleshooting)
22. [Contributing](#22-contributing)
23. [Security Architecture](#23-security-architecture)
24. [Known Limitations](#24-known-limitations)
25. [JPA Data Layer and Entity Relationships](#25-jpa-data-layer-and-entity-relationships)
26. [API Gateway, Registry, and Service Communication](#26-api-gateway-registry-and-service-communication)
27. [`fractalx-initializr-core` — Scaffold New Projects](#27-fractalx-initializr-core--scaffold-new-projects)
28. [Full Monolith Example — Annotated Code](#28-full-monolith-example--annotated-code)
29. [License](#29-license)

---

## 1. Quick Start -- 5 Minutes

### Step 1 -- Add the dependency

```xml
<properties>
    <fractalx.version>LATEST</fractalx.version><!-- replace with version from Maven Central badge above -->
</properties>

<dependency>
    <groupId>org.fractalx</groupId>
    <artifactId>fractalx-annotations</artifactId>
    <version>${fractalx.version}</version>
</dependency>
```

### Step 2 -- Mark each module boundary

```java
// src/main/java/com/myapp/order/OrderModule.java
@DecomposableModule(serviceName = "order-service", port = 8081)
public class OrderModule {
    private PaymentService paymentService; // cross-module dep -- auto-detected
}

// src/main/java/com/myapp/payment/PaymentModule.java
@DecomposableModule(serviceName = "payment-service", port = 8082)
public class PaymentModule { }
```

That is the only source change required. No other annotations are needed in your service classes.

### Step 3 -- Add the Maven plugin to your `pom.xml` (the invocation layer)

```xml
<plugin>
    <groupId>org.fractalx</groupId>
    <artifactId>fractalx-maven-plugin</artifactId>
    <version>${fractalx.version}</version>
</plugin>
```

### Step 4 -- Decompose

```bash
mvn fractalx:decompose
```

```
[INFO] FractalX x.y.z -- starting decomposition
[INFO] Phase 1: Parsing 22 source files ...
[INFO] Phase 2: Detected 2 modules, 1 cross-module dependency
[INFO] Generating order-service   (port 8081) ...
[INFO] Generating payment-service (port 8082) ...
[INFO] Generating fractalx-gateway, fractalx-registry, admin-service, logger-service
[INFO] Generating docker-compose.yml, start-all.sh
[INFO] Done in 0.61s
```

### Step 5 -- Start everything

```bash
cd microservices
./start-all.sh
```

In under a minute you have:

| Service | URL |
|---|---|
| order-service | http://localhost:8081 |
| payment-service | http://localhost:8082 |
| API Gateway | http://localhost:9999 |
| Service Registry | http://localhost:8761 |
| Admin Dashboard | http://localhost:9090 |
| Jaeger Traces | http://localhost:16686 |

---

## 2. How It Works

FractalX is a **multi-module framework** composed of a core decomposition engine, an annotations library, a runtime support library, a Spring Initializr integration, and a Maven plugin as the invocation layer. It reads your monolith's source tree with JavaParser, constructs a deterministic dependency graph, and generates everything needed to run your bounded contexts as independent Spring Boot services.

### Three-layer deterministic architecture

FractalX enforces a strict three-layer pipeline. No layer reaches into a higher layer's concerns.

```
Layer 1 — Graph Construction (deterministic)
  GraphBuilder performs a two-phase AST walk over all source files:
    Phase 1: collect all class/interface nodes with their annotations,
             implemented interfaces, superclass, and method metadata
    Phase 2: resolve typed edges — FIELD_REFERENCE, METHOD_CALL,
             EXTENDS, IMPLEMENTS — from actual AST type resolution,
             not name matching. Zero heuristics.
  Output: DependencyGraph — a single queryable structural model of
          the entire monolith. All downstream logic consumes this graph.

Layer 2 — Decomposition Rules (deterministic)
  Graph partitioning from @DecomposableModule boundary declarations.
  Cross-module dependency detection uses Spring stereotype annotations
  (@Service, @Component, @Repository, @Controller) indexed from the
  graph — never class name suffixes. Transaction boundary detection uses
  @NetScopeClient structural markers. Auth detection uses graph queries
  (nodesWithAnnotation, nodesWithMethodCall, callArgumentsFor).

Layer 3 — Code Generation (technology-specific)
  ServiceGenerator pipeline consumes the graph and module boundaries
  to emit Spring Boot services, gateway, registry, sagas, and infra.
  This is the only layer that knows about Spring Boot, Docker, etc.
```

Cross-module dependencies are detected **automatically** from Spring stereotype annotations and field injection — no explicit declaration required.

### Generation pipeline (per service)

```
GraphBuilder                  -> DependencyGraph (nodes + typed edges from full AST walk)
ModuleAnalyzer                -> FractalModule boundaries from @DecomposableModule + graph
PomGenerator                  -> pom.xml (netscope-server, netscope-client, resilience4j)
ApplicationGenerator          -> Main class (@EnableNetScopeServer, @EnableNetScopeClient)
ConfigurationGenerator        -> application.yml + application-dev.yml + application-docker.yml
CodeCopier                    -> copy source files into the new project
SharedCodeCopier              -> copy shared utilities / DTOs referenced by module code
ValuePropertyDistributorStep  -> migrate @Value("${...}") keys from monolith application.yml
CodeTransformer               -> AST rewrites (package, imports, cross-boundary FK decoupling)
FileCleanupStep               -> remove cross-boundary types from each service
NetScopeServerAnnotationStep  -> @NetworkPublic on methods called by other modules
NetScopeClientGenerator       -> @NetScopeClient interfaces for each dependency
NetScopeClientWiringStep      -> rewire service fields to generated *Client interfaces
DecompositionHintsStep        -> detect @Transactional/cache/event/aspect patterns -> DECOMPOSITION_HINTS.md
DistributedServiceHelper      -> DB isolation, Flyway scaffold, outbox, saga support
OtelConfigStep                -> OtelConfig.java (OTLP/gRPC -> Jaeger)
HealthMetricsStep             -> ServiceHealthConfig.java (TCP HealthIndicator per dep)
ServiceRegistrationStep       -> self-registration with fractalx-registry
NetScopeRegistryBridgeStep    -> dynamic gRPC host resolution from registry
ResilienceConfigStep          -> Resilience4j CircuitBreaker + Retry + TimeLimiter per dep
```

Then, after all services are generated:

```
GatewayGenerator           -> fractalx-gateway (Spring Cloud Gateway + security + CORS + metrics)
RegistryServiceGenerator   -> fractalx-registry (lightweight REST-based service registry)
AdminServiceGenerator      -> admin-service (15-section ops dashboard)
LoggerServiceGenerator     -> logger-service (structured log ingestion + query)
SagaOrchestratorGenerator  -> fractalx-saga-orchestrator (when @DistributedSaga is found)
DockerComposeGenerator     -> docker-compose.yml + multi-stage Dockerfiles
```

### System architecture (generated)

```
+---------------------------------------------------------------------+
|                        Your Monolith                                |
|  @DecomposableModule(serviceName="order-service",   port=8081)      |
|  @DecomposableModule(serviceName="payment-service", port=8082)      |
+---------------------------+-----------------------------------------+
                            |  mvn fractalx:decompose
                            v
+---------------------------------------------------------------------+
|                    FractalX Code Generator                          |
|  GraphBuilder -> DependencyGraph -> ServiceGenerator -> Gateway     |
+---------------------------+-----------------------------------------+
                            |  Generated output
                            v
+----------------------------------------------------------------------------+
|  fractalx-registry :8761  <- all services self-register on startup         |
|                                                                            |
|  fractalx-gateway  :9999  <- pulls live routes from registry              |
|    +-- /api/orders/**    -> order-service   :8081                          |
|    +-- /api/payments/**  -> payment-service :8082                          |
|    +-- TracingFilter  (X-Correlation-Id, W3C traceparent)                  |
|    +-- RateLimiterFilter (sliding-window, in-memory)                       |
|    +-- GatewayMetricsFilter (gateway.requests.total / duration)            |
|                                                                            |
|  order-service   :8081  --[NetScope gRPC :18081]--> payment-service :8082  |
|    +-- OtelConfig.java          (OTLP/gRPC -> Jaeger :4317)                |
|    +-- ServiceHealthConfig.java (TCP HealthIndicator per gRPC dep)         |
|    +-- Resilience4j   (CircuitBreaker + Retry + TimeLimiter per dep)       |
|                                                                            |
|  logger-service  :9099  <- structured log ingest (correlationId, spanId)  |
|  jaeger          :16686 / :4317  <- distributed trace store (OTLP)        |
|                                                                            |
|  admin-service   :9090  <- Observability, Alerts, Traces, Logs, Config    |
+----------------------------------------------------------------------------+
```

### Inter-service communication -- NetScope

FractalX uses **NetScope** (gRPC-based) for inter-service communication. The gRPC port is always `HTTP port + 10000`:

| Service | HTTP | gRPC |
|---|---|---|
| order-service | :8081 | :18081 |
| payment-service | :8082 | :18082 |

**Server side** -- FractalX detects which bean methods are called by other modules and adds `@NetworkPublic` to them:

```java
// Generated in payment-service -- auto-detected from order-service dependency
@NetworkPublic
public PaymentResult charge(Long customerId, BigDecimal amount) { ... }
```

**Client side** -- FractalX generates a `@NetScopeClient` interface in order-service:

```java
@NetScopeClient(server = "payment-service", beanName = "paymentService")
public interface PaymentServiceClient {
    PaymentResult charge(Long customerId, BigDecimal amount);
}
```

The host is resolved dynamically from `fractalx-registry` at startup -- no hardcoded hostnames.

---

## 3. Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| Java | 21+ (configurable) | Required |
| Maven | 3.8+ | Required |
| Git | any | Required |
| Docker + Docker Compose | 24+ | Optional -- needed only for container deployment |

---

## 4. Build the Framework

Clone and install all framework modules:

```bash
git clone https://github.com/Project-FractalX/FractalX.git
cd FractalX/fractalx-parent
mvn clean install -DskipTests
```

This installs into your local Maven repository:

| Module | Artifact | Purpose |
|---|---|---|
| `fractalx-annotations` | Annotations jar | `@DecomposableModule`, `@ServiceBoundary`, `@AdminEnabled`, `@DistributedSaga` |
| `fractalx-core` | Core library | AST analyzer + all 30+ code generators |
| `fractalx-maven-plugin` | Maven plugin | `mvn fractalx:decompose` and all plugin goals |
| `fractalx-runtime` | Runtime jar | `FractalServiceRegistry`, `FractalLogAppender`, `TraceContextPropagator` |

---

## 5. Annotate Your Monolith

### `@DecomposableModule` -- mark a bounded context

Place one marker class per module. FractalX uses it as the root of that service's package tree.

```java
@DecomposableModule(
    serviceName           = "order-service",
    port                  = 8081,
    independentDeployment = true,           // generates its own Dockerfile
    ownedSchemas          = {"orders", "order_items"}
)
public class OrderModule {
    // Declare cross-module dependencies as fields.
    // FractalX reads these field types to build the dependency graph.
    private PaymentService paymentService;
}
```

| Attribute | Type | Required | Description |
|---|---|---|---|
| `serviceName` | `String` | Yes | Generated service name and Docker container name |
| `port` | `int` | Yes | HTTP port (1-65535); gRPC port = this + 10000 |
| `independentDeployment` | `boolean` | No | `true` to generate a Dockerfile for this service |
| `ownedSchemas` | `String[]` | No | Database schemas this service owns (Flyway + `@EntityScan`) |

### `@ServiceBoundary` -- expose a method over gRPC

FractalX adds `@NetworkPublic` automatically to methods it detects as cross-module. Use `@ServiceBoundary` to mark methods explicitly when you want to be certain they are exposed.

```java
@ServiceBoundary
public boolean isConfirmed(Long orderId) {
    return repository.existsByIdAndStatus(orderId, CONFIRMED);
}

// This method stays internal -- not exposed to other services.
public void recalculateTotal(Order order) { ... }
```

### `@DistributedSaga` -- generate saga orchestration

FractalX detects methods annotated with `@DistributedSaga`, maps their cross-service call sequence, and generates a complete `fractalx-saga-orchestrator` service with forward steps and automatic compensation.

```java
@DistributedSaga(
    sagaId       = "checkout",
    compensationMethod = "cancelCheckout"
)
public void processCheckout(Long orderId, Long userId) {
    orderService.confirm(orderId);
    paymentService.charge(userId, orderService.getTotal(orderId));
    inventoryService.reserve(orderId);
}

public void cancelCheckout(Long orderId, Long userId) {
    paymentService.refund(userId);
    orderService.cancel(orderId);
}
```

### `@AdminEnabled` -- opt a service into extended admin controls

Applied to a module class. The admin dashboard shows extended metrics and controls for this service.

### Cross-module dependency detection

FractalX detects which beans a module imports from other modules in two ways:

**Explicit declaration (recommended)** -- list the cross-module bean types directly in the annotation:
```java
@DecomposableModule(
    serviceName = "leave-service", port = 8083,
    dependencies = {EmployeeService.class, DepartmentService.class}
)
public class LeaveModule {}
```

**Annotation-based inference** -- when `dependencies` is omitted, FractalX builds a Spring stereotype
index from the parsed source tree. Any field type annotated with `@Service`, `@Component`,
`@Repository`, `@Controller`, `@RestController`, or `@Configuration` that is injected into a
`@DecomposableModule` class is treated as a cross-module dependency. This is fully deterministic —
no name-suffix matching. A `WARN` is logged for each inferred dependency:
```
[WARN] Module 'leave-service': dependencies inferred by Spring annotation index
       ([EmployeeService, DepartmentService]). Declare them explicitly with
       @DecomposableModule(dependencies={...}) for guaranteed coverage.
```

**Lombok `@RequiredArgsConstructor` / `@AllArgsConstructor`** -- when a class carries one of these
Lombok annotations, FractalX also scans all `private final` fields as potential cross-module
dependencies. This covers non-standard naming conventions like `NotificationGateway`,
`PaymentFacade`, or `AuditSink`. JDK and Spring framework types are filtered out automatically.

> **Always use explicit `dependencies=`** for any monolith where cross-module beans are not
> annotated with standard Spring stereotypes. Programmatically registered beans, or types loaded
> via reflection, are not visible to static analysis.
>
> Explicit declaration also drives the dependency graph in `fractalx:verify` -- without it, all
> services will show `⚠ ORPHAN` warnings from the graph analyser.

---

## 6. Pre-Decomposition Configuration

Create `fractalx-config.yml` in `src/main/resources/` alongside your `application.yml` **before** running `mvn fractalx:decompose`. FractalX reads this file at generation time and bakes the values directly into all generated services -- no manual editing of generated files is required.

```yaml
fractalx:
  base-package: com.acme.generated            # defaults to monolith groupId + ".generated"
  java-version: 21                            # defaults to version read from source pom.xml, then 21
  initial-service-version: 1.0.0-SNAPSHOT     # version for generated pom.xml artifacts (can inherit monolith version)
  spring-boot-version: 3.3.2                  # defaults to version read from source pom.xml
  spring-cloud-version: 2023.0.1
  registry:
    url: http://my-registry-host:8761         # baked into FRACTALX_REGISTRY_URL fallback
    port: 8761
  logger:
    url: http://my-logger-host:9099           # baked into FRACTALX_LOGGER_URL fallback
    port: 9099
  saga:
    port: 8099
  otel:
    endpoint: http://my-jaeger-host:4317      # baked into OTEL_EXPORTER_OTLP_ENDPOINT fallback
    jaeger-ui-port: 16686                     # Jaeger UI port in docker-compose
    jaeger-otlp-port: 4317                    # Jaeger OTLP collector port in docker-compose
  gateway:
    port: 9999
    cors:
      allowed-origins: "http://myapp.com,http://localhost:3000"
    security:
      oauth2:
        jwk-set-uri: http://auth-host:8080/realms/fractalx/protocol/openid-connect/certs
  admin:
    port: 9090
    datasource:
      url: jdbc:mysql://localhost:3306/admin_db
      username: admin_user
      password: secret
  docker:
    maven-build-image: maven:3.9-eclipse-temurin-21   # defaults derived from java-version
    jre-runtime-image: eclipse-temurin:21-jre-jammy    # defaults derived from java-version
    jaeger-image: jaegertracing/all-in-one:1.53
  services:
    order-service:
      datasource:
        url: jdbc:mysql://localhost:3306/order_db
        username: root
        password: secret
    payment-service:
      datasource:
        url: jdbc:postgresql://localhost:5432/payment_db
        username: postgres
        password: secret
```

All keys are optional. When absent, FractalX falls back to sensible defaults (e.g., Java 21, registry `http://localhost:8761`, logger `http://localhost:9099`, Jaeger `http://localhost:4317`). Java version, Spring Boot version, and initial service version are also auto-detected from the monolith's `pom.xml`. The source monolith's `application.yml` and `application.properties` are consulted as a secondary fallback.

### `@Value` property migration

FractalX automatically migrates property values from the monolith's `application.yml` / `application.properties` into each generated service's `application-dev.yml`. For every `@Value("${my.property.key}")` and `@ConfigurationProperties(prefix = "my.prefix")` found in the module's source files, the generator looks up the corresponding value in the monolith config and appends it:

```yaml
# application-dev.yml (auto-appended section)
# ---------------------------------------------------------------------------
# Properties migrated from monolith application.yml by FractalX
# ---------------------------------------------------------------------------
payment.gateway.url: https://sandbox.payment-provider.com
payment.gateway.timeout: 5000

# ---------------------------------------------------------------------------
# FRACTALX-WARNING: the following keys were referenced but not found in
# the monolith's application.yml. Add their values manually:
# payment.gateway.api-key: <REQUIRED>
# ---------------------------------------------------------------------------
```

Keys that are present in the monolith config are written as resolved values. Keys that are missing are listed as comments with `<REQUIRED>` so nothing is silently omitted.

After generation, the admin portal exposes the baked-in platform config at `GET /api/config/runtime` and allows in-memory overrides (`PUT /api/config/runtime/{key}`) without restarting.

### Feature Flags

Every FractalX-generated artifact can be individually disabled from `fractalx-config.yml`.
All flags default to `true` — omitting the `features` block leaves all generation behaviour unchanged.

```yaml
fractalx:
  features:
    gateway: true          # fractalx-gateway service
    admin: true            # admin-service dashboard
    registry: true         # fractalx-registry service discovery
    logger: true           # logger-service centralised logging
    saga: true             # fractalx-saga-orchestrator + saga code injection
    docker: true           # docker-compose.yml + per-service Dockerfiles
    observability: true    # OTel tracing, health metrics, structured logging
    resilience: true       # Resilience4j circuit-breaker / retry config per service
    distributed-data: true # Flyway migrations, transactional outbox, DB isolation
```

| Flag | Effect when `false` |
|---|---|
| `gateway` | `fractalx-gateway/` not generated |
| `admin` | `admin-service/` not generated |
| `registry` | `fractalx-registry/` not generated (⚠ services still try to self-register) |
| `logger` | `logger-service/` not generated |
| `saga` | No `fractalx-saga-orchestrator/`; saga code rewrites skipped — no saga code injected into any service |
| `docker` | No `docker-compose.yml` or per-service `Dockerfile` generated |
| `observability` | Skips OTel config, health metrics, structured logging; OTel deps omitted from all service poms; OTel properties not patched into `application.yml` |
| `resilience` | Skips Resilience4j config generation — no circuit-breaker or retry config injected into any service |
| `distributed-data` | Skips `DistributedServiceHelper` entirely — no Flyway migrations, no transactional outbox, no DB isolation, no reference validators |

**Example — core decomposition only (NetScope wiring, nothing else):**

```yaml
fractalx:
  features:
    gateway: false
    admin: false
    registry: false
    logger: false
    saga: false
    docker: false
    observability: false
    resilience: false
    distributed-data: false
```

This produces one microservice directory per `@DecomposableModule` with NetScope client/server wiring only — a minimal starting point before incrementally enabling features.

### Naming Conventions

FractalX derives service names, client interface names, compensation methods, and aggregate names from class names and annotations.
All naming rules are configurable via the `fractalx.naming` block in `fractalx-config.yml`.
Every key has a built-in default so the block can be omitted entirely — existing projects are unaffected.

```yaml
fractalx:
  naming:
    # Prefixes scanned when looking for a compensation/rollback method for a saga step.
    # e.g. forward method "processPayment" is matched against "cancelProcessPayment",
    # "rollbackProcessPayment", "undoProcessPayment", etc.
    compensation-prefixes: [cancel, rollback, undo, revert, release, refund]

    # Class name suffixes that mark a type as infrastructure — never transitively
    # copied into a consuming service as a model class.
    infrastructure-suffixes: [Service, Repository, Controller, Module, Config, Configuration, Application]

    # Field/parameter type suffixes that signal a cross-module dependency when
    # ModuleAnalyzer infers dependencies without an explicit @DecomposableModule declaration.
    dependency-type-suffixes: [Service, Client]

    # Suffixes stripped when deriving the domain aggregate name from the saga owner class.
    # e.g. "OrderService" → "Order", "OrderFacade" → "Order" (when "Facade" is listed).
    # Also used during module name normalisation for fuzzy lookup:
    # "svc1-service" and "svc1" both resolve to module "svc1".
    aggregate-class-suffixes: [Service, Module]

    # Method names recognised as Spring ApplicationEvent publishers during
    # DecompositionHints scanning.
    event-publisher-method-names: [publishEvent]

    # When true, service name comparisons during module lookup are case-insensitive.
    # Allows "Order-Service" and "order-service" to resolve to the same module.
    case-insensitive-service-names: true

    # Irregular English plural → singular mappings for collection-field-to-ID conversion.
    # Key = plural form, value = singular form. Defaults include common irregulars.
    irregular-plurals:
      children: child
      people: person
      data: datum
      media: medium
      criteria: criterion
      indices: index
      vertices: vertex
      matrices: matrix
```

#### How module name normalisation works

When a dependency bean type such as `Svc1Service` is wired to a module named `svc1`, exact name matching would fail.
FractalX uses a two-pass lookup:

1. **Exact match** — fast path; `"svc1-service"` vs `"svc1"` → no match.
2. **Normalized match** — strips separators and every suffix listed in `aggregate-class-suffixes`, then lowercases both sides: `"svc1service"` → strip `"service"` → `"svc1"` == `"svc1"` → match.

Any bean type that resolves to a known module via normalization will have its NetScope client generated and wired correctly.
Dependency names that cannot be resolved (after normalization) are logged as warnings before the pipeline starts — no silent failures, no broken builds.

#### Defaults reference

| Key | Default |
|---|---|
| `compensation-prefixes` | `cancel, rollback, undo, revert, release, refund` |
| `infrastructure-suffixes` | `Service, Repository, Controller, Module, Config, Configuration, Application` |
| `dependency-type-suffixes` | `Service, Client` |
| `aggregate-class-suffixes` | `Service, Module` |
| `event-publisher-method-names` | `publishEvent` |
| `case-insensitive-service-names` | `true` |
| `irregular-plurals` | children/child, people/person, data/datum, media/medium, criteria/criterion, indices/index, vertices/vertex, matrices/matrix |

---

## 7. Running FractalX

FractalX is invoked through its Maven plugin — the plugin is the command-line interface to the core framework engine. Every goal renders the **FRACTALX** ASCII banner. The interactive menu is the recommended entry point.

### Common flags

| Flag | Default | Description |
|---|---|---|
| `-Dfractalx.outputDirectory` | `./microservices` | Output directory for generated services |
| `-Dfractalx.color=false` | `true` | Disable ANSI colors (auto-disabled on non-TTY) |

---

### `fractalx:menu` -- Interactive TUI

Full-screen interactive menu. Use arrow keys to navigate, Enter to run.

```bash
mvn fractalx:menu
```

| Key | Action |
|---|---|
| `Up` / `Down` or `k` / `j` | Navigate |
| `Enter` | Run selected goal |
| `q`, `Ctrl-C`, `ESC` | Quit |

> On dumb terminals (no ANSI) a numbered list is shown instead.

---

### `fractalx:decompose` -- Decompose the monolith

```bash
# Full decomposition (default)
mvn fractalx:decompose

# Analysis only, skip file generation
mvn fractalx:decompose -Dfractalx.generate=false

# Custom output directory
mvn fractalx:decompose -Dfractalx.outputDirectory=/path/to/output
```

#### Pre-decomposition validation rules

Before generating any code, FractalX validates your annotations and module graph. Errors block generation; warnings are advisory.

| Rule ID | Severity | Triggered when | How to fix |
|---|---|---|---|
| `INVALID_SERVICE_NAME` | **Error** | `serviceName` is blank or doesn't match `^[a-z][a-z0-9-]{0,62}$` | Use a lowercase, hyphen-separated name, e.g. `order-service` |
| `DUP_SERVICE_NAME` | **Error** | Two or more modules declare the same `serviceName` | Give each `@DecomposableModule` a unique `serviceName` |
| `DUP_PORT` | **Error** | Two modules share the same HTTP port, the same derived gRPC port (HTTP + 10000), or one module's HTTP port collides with another's gRPC port | Assign unique HTTP ports in `@DecomposableModule(port = ...)` |
| `INFRA_PORT_CONFLICT` | **Error** | A module's HTTP or gRPC port conflicts with a reserved infrastructure port (registry: 8761, gateway: 9999, admin: 9090, logger: 9099, saga orchestrator: 8099) | Change `@DecomposableModule(port = ...)` to an unreserved port |
| `CIRCULAR_DEP` | **Error** | A circular dependency is detected in the service graph, e.g. `order-service → payment-service → order-service` | Extract shared state into a new `@DecomposableModule`, or break the cycle using an event/outbox pattern |
| `REPO_BOUNDARY` | **Error** | A module directly injects a JPA repository owned by a different module — after decomposition that repository lives in a separate JVM and database | Move the data access logic into the owning service and expose it via a service method |
| `SAGA_INTEGRITY` | **Error** | A `@DistributedSaga` method has no `sagaId`, two methods share the same `sagaId`, or a timeout value is ≤ 0 | Add a unique `sagaId`, e.g. `sagaId = "place-order-saga"`, and set a positive `timeout` |
| `UNRESOLVED_DEP` | Warning | A declared dependency type cannot be mapped to any known module's `serviceName` | Annotate the target class with a matching `@DecomposableModule`, or declare the dependency explicitly: `@DecomposableModule(dependencies = {PaymentService.class})` |
| `LOMBOK_ALL_ARGS` | Warning | A `@DecomposableModule` class uses `@AllArgsConstructor` (or has no explicit constructor), which makes dependency detection unreliable | Switch to `@RequiredArgsConstructor` with `private final` fields, or declare dependencies explicitly in `@DecomposableModule(dependencies = {...})` |

Errors are printed before generation begins and the mojo exits without writing any files. Warnings are printed but do not block generation.

---

### `fractalx:verify` -- Verify generated output

Multi-level static analysis on generated services -- no need to start them.

```bash
mvn fractalx:verify
mvn fractalx:verify -Dfractalx.verify.compile=true          # include mvn compile per service
mvn fractalx:verify -Dfractalx.verify.failBuild=true        # fail build on any finding
mvn fractalx:verify -Dfractalx.verify.smokeTests=true       # generate Spring context tests
```

| Level | Checks |
|---|---|
| 1 | Structural -- required files, package structure, Dockerfiles, docker-compose |
| 2 | NetScope compatibility -- `@NetScopeClient` interfaces match `@NetworkPublic` server methods |
| 3 | Port conflicts, cross-boundary imports, REST API conventions |
| 4 | Dependency graph acyclicity, SQL schema validity, `@Transactional` on GET, secret detection, config coverage |
| 5 (opt-in) | Compilation -- `mvn compile` on every generated service |
| 6 (opt-in) | Smoke test generation -- Spring context load test per service |

#### Verifier behaviour notes

**Cross-boundary import checker (Level 3)**

A cross-boundary import is only flagged when the imported class file does **not** exist under the
consuming service's own `src/main/java`. The generator copies shared model / DTO / enum classes
(e.g. `Payment`, `Customer`, `PaymentMethod`) from the source monolith into every service that
needs them. Because those files are physically present under the service tree, they are treated as
local copies and are not violations. Only genuine cross-service direct references -- a service
class imported without a local copy -- are flagged.

**Dependency graph / orphan check (Level 4)**

The graph is built from `DependencyGraph` structural edges — `FIELD_REFERENCE` edges from
`@DecomposableModule` classes to their injected dependencies, resolved from the fully-typed AST.
When `dependencies=` is declared explicitly in `@DecomposableModule`, the mapping is exact.
When omitted, the Spring annotation index (types annotated `@Service`, `@Component`, etc.)
provides the inference — no name-suffix matching. Orphan warnings mean the graph analyser could
not build an edge, most often because a dependency bean is not annotated with a Spring stereotype
and is not declared explicitly (see [Cross-module dependency detection](#cross-module-dependency-detection)).

**Secret scanner (Level 4)**

Values of the form `${ENV_VAR:default}` are Spring EL placeholders and are **never** flagged,
even when the key name looks sensitive (e.g. `api-key`, `token`). Only plain-text values that
are not placeholders, not numeric/boolean, and longer than three characters are reported.
The findings are advisory warnings, not failures -- they indicate dev-only defaults that must
be rotated before production deployment.

---

### `fractalx:smoke-test` -- Build and start verification

Compiles every generated service, starts it, confirms that Spring Boot's HTTP port opened and
that the Actuator health endpoint responded, then shuts it down. Services are tested one at a
time so there are no port conflicts. Unlike `fractalx:verify`, this goal actually runs the code.

```bash
mvn fractalx:smoke-test                                                      # build + start + health all
mvn fractalx:smoke-test -Dfractalx.smoketest.build=false                     # skip build, start-only
mvn fractalx:smoke-test -Dfractalx.smoketest.service=order-service           # single service
mvn fractalx:smoke-test -Dfractalx.smoketest.timeout=180                     # longer startup window
mvn fractalx:smoke-test -Dfractalx.smoketest.failBuild=true                  # fail Maven if any service fails
```

| Parameter | Default | Description |
|---|---|---|
| `fractalx.smoketest.build` | `true` | Run `mvn package -DskipTests` before starting |
| `fractalx.smoketest.timeout` | `120` | Seconds to wait for the HTTP port to open |
| `fractalx.smoketest.health` | `/actuator/health` | Actuator path polled after port opens |
| `fractalx.smoketest.service` | *(blank = all)* | Test a single named service |
| `fractalx.smoketest.failBuild` | `false` | Throw a build failure if any service fails |
| `fractalx.smoketest.skip` | `false` | Skip this goal entirely |

**What "health passed" means:** any HTTP response from `/actuator/health` (even `503 DOWN` due to
a peer service being offline) counts as a pass — it proves that the Spring context loaded and the
embedded server started. A connection refused after the port opened means the context crashed.

Build logs are written to `<service>/logs/smoketest-build.log` and start logs to
`<service>/logs/smoketest-run.log`. The last 20 lines are printed to the console for any failure.

---

### Service lifecycle goals

| Goal | Description |
|---|---|
| `fractalx:smoke-test` | Build, start, and health-check every generated service |
| `fractalx:services` | List generated services with ports and Docker status |
| `fractalx:ps` | Port-based check of which services are currently running |
| `fractalx:start [-Dfractalx.service=<name>]` | Start all or a named service |
| `fractalx:stop [-Dfractalx.service=<name>]` | Stop all or a named service |
| `fractalx:restart [-Dfractalx.service=<name>]` | Stop then start |

---

### Typical workflow

```bash
mvn fractalx:decompose                                   # 1. Generate
mvn fractalx:verify                                      # 2. Verify
mvn fractalx:services                                    # 3. Inspect
mvn fractalx:start                                       # 4. Start all
mvn fractalx:ps                                          # 5. Check running
mvn fractalx:restart -Dfractalx.service=order-service   # 6. Hot-reload one service
mvn fractalx:stop                                        # 7. Stop all
```

---

## 8. Generated Output

```
microservices/
+-- fractalx-registry/              # Service discovery registry -- start first
+-- order-service/
|   +-- pom.xml                     # netscope-server, netscope-client, resilience4j deps
|   +-- Dockerfile                  # Multi-stage build
|   +-- DECOMPOSITION_HINTS.md      # (generated when issues detected -- see below)
|   +-- src/main/
|       +-- java/
|       |   +-- com/example/order/  # Copied + AST-transformed source
|       |   +-- com/example/shared/ # Shared utilities copied from monolith (SharedCodeCopier)
|       |   +-- org/fractalx/generated/orderservice/
|       |       +-- OtelConfig.java              # OTLP/gRPC -> Jaeger
|       |       +-- ServiceHealthConfig.java     # TCP HealthIndicator per gRPC dep
|       +-- resources/
|           +-- application.yml         # Base config
|           +-- application-dev.yml     # Localhost defaults + @Value properties from monolith
|           +-- application-docker.yml  # Full env-var substitution
+-- payment-service/
|   +-- src/main/java/com/example/payment/
|       +-- PaymentServiceClient.java    # @NetScopeClient interface (auto-generated)
+-- fractalx-gateway/               # Spring Cloud Gateway :9999
+-- logger-service/                 # Log ingestion + query :9099
+-- admin-service/                  # Operations dashboard :9090
|   +-- src/main/java/org/fractalx/admin/
|       +-- observability/          # AlertEvaluator, AlertChannels (SSE+Webhook+Email+Slack)
|       +-- topology/               # TopologyController, NetworkMapController
|       +-- config/                 # RuntimeConfigController (live config diff + override)
+-- fractalx-saga-orchestrator/     # (generated when @DistributedSaga is detected) :8099
+-- docker-compose.yml              # All services + Jaeger, logger, OTEL env vars
+-- start-all.sh                    # Ordered startup: registry -> services -> gateway -> admin
+-- stop-all.sh
```

### What every generated microservice includes

| Component | Description |
|---|---|
| `pom.xml` | Spring Boot 3.2+ / 4.x, netscope-server, netscope-client, Resilience4j, Flyway, Actuator, Micrometer |
| Main class | `@EnableNetScopeServer` + `@EnableNetScopeClient` |
| `application.yml` | Registry URL, gRPC port, OTEL endpoint, logger URL (with env-var overrides) |
| `application-dev.yml` | Localhost defaults, H2 DB + `@Value` properties migrated from monolith |
| `application-docker.yml` | Full Docker DNS hostnames via env vars |
| Shared utility classes | Non-module classes referenced by module code (e.g. `shared/`, `common/`) copied by `SharedCodeCopier` |
| `OtelConfig.java` | OpenTelemetry SDK -- OTLP/gRPC to Jaeger, W3C traceparent propagation |
| `ServiceHealthConfig.java` | One Spring `HealthIndicator` + Micrometer gauge per gRPC dependency |
| `ServiceRegistrationAutoConfig` | Self-register on startup; heartbeat every 5s with automatic re-registration on registry restart; deregister on shutdown |
| `NetScopeRegistryBridgeStep` | Dynamic gRPC host resolution from registry at startup; all peers resolved in parallel; configurable retries (`fractalx.registry.max-retries`, default 10); 2 s connect / 3 s read timeout on registry HTTP calls |
| Resilience4j YAML | CircuitBreaker + Retry + TimeLimiter per dependency |
| `Dockerfile` | Multi-stage (build + runtime), non-root user |
| `@NetScopeClient` interfaces | One per outgoing gRPC dependency |
| `DECOMPOSITION_HINTS.md` | (present only when issues detected) — see [Decomposition Hints](#decomposition-hints) |

---

## 9. Start Generated Services

### One command (recommended)

```bash
cd microservices
./start-all.sh
```

The script starts the registry first, waits for it to be healthy, then starts all services in dependency order. Logs go to `<service-name>.log`.

### Manual startup order

Services must start **after** the registry:

```bash
# Terminal 1 -- start first
cd fractalx-registry && mvn spring-boot:run

# Terminals 2, 3, ... -- domain services
cd order-service   && mvn spring-boot:run
cd payment-service && mvn spring-boot:run

# Gateway and support services -- any time after registry
cd fractalx-gateway && mvn spring-boot:run
cd logger-service   && mvn spring-boot:run
cd admin-service    && mvn spring-boot:run
```

### Default port map

| Service | HTTP | gRPC | Role |
|---|---|---|---|
| `fractalx-registry` | 8761 | -- | Service discovery |
| Domain service 1 | 8081 | 18081 | First generated microservice |
| Domain service 2 | 8082 | 18082 | Second generated microservice |
| Domain service N | 808N | 1808N | N-th generated microservice |
| `fractalx-gateway` | 9999 | -- | API gateway (single entry point) |
| `logger-service` | 9099 | -- | Centralized log ingestion |
| `admin-service` | 9090 | -- | Operations dashboard |
| `fractalx-saga-orchestrator` | 8099 | -- | Saga orchestration (if applicable) |
| Jaeger UI | 16686 | 4317 | Distributed trace store |

```bash
./stop-all.sh
```

---

## 10. Docker Deployment

```bash
cd microservices
docker compose up --build
```

Services start in dependency order: Jaeger -> logger-service -> registry -> microservices -> gateway -> admin.

Each container receives these environment variables automatically:

| Variable | Value in Docker |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `docker` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://jaeger:4317` |
| `OTEL_SERVICE_NAME` | Service name |
| `FRACTALX_LOGGER_URL` | `http://logger-service:9099/api/logs` |
| `FRACTALX_REGISTRY_URL` | `http://fractalx-registry:8761` |

Run a single service locally against containerised peers:

```bash
SPRING_PROFILES_ACTIVE=docker \
FRACTALX_REGISTRY_URL=http://localhost:8761 \
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317 \
mvn spring-boot:run
```

---

## 11. API Gateway

The gateway runs at **http://localhost:9999** and is the single entry point for all services.

Routes are resolved **dynamically** from `fractalx-registry` at startup and refreshed periodically. A static YAML fallback is baked in for resilience.

### Per-request filter chain

| Order | Filter | Purpose |
|---|---|---|
| -100 | `RequestLoggingFilter` | Logs method, path, status, duration, correlationId |
| -99 | `TracingFilter` | Propagates `X-Correlation-Id` and W3C `traceparent` |
| -98 | `GatewayMetricsFilter` | `gateway.requests.total{service,method,status}` counter + duration timer |
| -95 | `GatewayApiKeyFilter` | API key validation (disabled by default) |
| -90 | `GatewayBearerJwtFilter` | HMAC-SHA256 JWT validation (disabled by default) |
| -85 | `GatewayBasicAuthFilter` | Basic auth (disabled by default) |
| -80 | `GatewayRateLimiterFilter` | Sliding-window in-memory rate limiter |

### Per-route filters

- **Circuit Breaker** -- opens after sustained failures; returns `503` JSON from `GatewayFallbackController`
- **Retry** -- 2 retries on `502`, `503`, `504`
- **Rate Limiter** -- 100 req/s per IP per service (configurable)

---

## 12. Service Discovery

`fractalx-registry` is a lightweight REST-based service registry generated alongside your services -- no Eureka or Consul dependency required.

### Registry API

| Method | Path | Description |
|---|---|---|
| `POST` | `/services` | Register a service instance |
| `GET` | `/services` | List all registered services |
| `GET` | `/services/{name}` | Get a specific service |
| `POST` | `/services/{name}/heartbeat` | Keep-alive ping |
| `DELETE` | `/services/{name}/deregister` | Deregister |
| `GET` | `/services/health` | Registry health summary |

### Registration lifecycle

Each generated service includes `ServiceRegistrationAutoConfig`:

1. **On startup** -- POST `{name, host, port, grpcPort, healthUrl}` to the registry
2. **Every 5 seconds** -- heartbeat to refresh `lastSeen`; if the heartbeat fails (e.g. registry restarted), the service immediately re-registers
3. **On shutdown** -- deregister gracefully

The registry polls each service's `/actuator/health` every 15 seconds and evicts services unresponsive for 90 seconds.

---

## 13. Gateway Security

All mechanisms are **disabled by default**. Enable selectively in `fractalx-gateway/src/main/resources/application.yml`.

| Mechanism | Enable flag | Description |
|---|---|---|
| Bearer JWT | `fractalx.gateway.security.bearer.enabled: true` | HMAC-SHA256 signed tokens |
| OAuth2 | `fractalx.gateway.security.oauth2.enabled: true` | External IdP via JWK Set URI |
| Basic Auth | `fractalx.gateway.security.basic.enabled: true` | `Authorization: Basic` header |
| API Key | `fractalx.gateway.security.api-key.enabled: true` | `X-Api-Key` header or `?api_key=` param |

<details>
<summary>Full security configuration</summary>

```yaml
fractalx:
  gateway:
    security:
      enabled: true                              # master switch (default: false)
      public-paths: /api/*/public/**, /api/*/auth/**

      bearer:
        enabled: true
        jwt-secret: ${JWT_SECRET:my-secret-min-32-chars}

      oauth2:
        enabled: false
        jwk-set-uri: ${OAUTH2_JWK_URI:http://keycloak:8080/realms/myrealm/protocol/openid-connect/certs}

      basic:
        enabled: false
        username: ${GATEWAY_BASIC_USER:admin}
        password: ${GATEWAY_BASIC_PASS:changeme}

      api-key:
        enabled: false
        valid-keys:
          - ${GATEWAY_API_KEY_1:replace-me}

    cors:
      allowed-origins: ${CORS_ORIGINS:http://localhost:3000,http://localhost:4200}
      allowed-methods: GET,POST,PUT,DELETE,PATCH,OPTIONS
      allow-credentials: true

    rate-limit:
      default-rps: 100
      per-service:
        payment-service: 50
        order-service: 200
```

</details>

On successful authentication, the filter injects downstream headers: `X-User-Id`, `X-User-Roles`, `X-Auth-Method`.

---

## 14. Resilience

### Per-service (generated in each microservice)

Every service gets Resilience4j configuration for each of its gRPC dependencies:

| Pattern | Default |
|---|---|
| CircuitBreaker | 50% failure threshold, 30s wait in OPEN, 5 calls in HALF_OPEN |
| Retry | 3 attempts, 100ms base delay, exponential backoff (x2) |
| TimeLimiter | 2s timeout per gRPC call |

```yaml
# Override per dependency in application.yml
resilience4j:
  circuitbreaker:
    instances:
      payment-service:
        failure-rate-threshold: 60
        wait-duration-in-open-state: 60s
  retry:
    instances:
      payment-service:
        max-attempts: 5
  timelimiter:
    instances:
      payment-service:
        timeout-duration: 3s
```

### Gateway-level

Each route has a named circuit breaker. When open, the gateway returns `503 Service Unavailable` JSON rather than hanging.

---

## 15. Data Consistency

FractalX generates a complete data isolation and consistency stack for each service:

| Concern | Generated component |
|---|---|
| DB per service | `DataIsolationGenerator` -- dual-package `@EntityScan` + `@EnableJpaRepositories` |
| DB configuration | `DbConfigurationGenerator` -- reads `fractalx-config.yml -> fractalx.services.<name>` |
| Schema migration | `FlywayMigrationGenerator` -- `V1__init.sql` scaffold + outbox table |
| Transactional outbox | `OutboxEvent`, `OutboxRepository`, `OutboxPublisher`, `OutboxPoller` |
| Cross-service FK validation | `ReferenceValidator` -- uses NetScope `exists()` calls instead of DB joins |
| Relationship decoupling | `RelationshipDecoupler` -- AST rewrites `@ManyToOne ForeignEntity` to `String foreignEntityId` |
| Multi-schema detection | `DataIsolationGenerator` scans entity classes for `@Table(schema = "...")` -- if multiple schemas are found a `FRACTALX-WARNING` block is emitted in `IsolationConfig.java` listing the affected schemas and remediation options |

### Configuring production databases

```yaml
# fractalx-config.yml
fractalx:
  services:
    order-service:
      datasource:
        url: jdbc:mysql://localhost:3306/order_db
        username: root
        password: secret
    payment-service:
      datasource:
        url: jdbc:postgresql://localhost:5432/payment_db
        username: postgres
        password: secret
```

### Distributed sagas

When `@DistributedSaga` is detected, FractalX generates `fractalx-saga-orchestrator` (HTTP :8099):

- One `<SagaId>SagaService` per `@DistributedSaga` method with forward steps and compensation logic
- `SagaInstance` JPA entity tracking per-execution state: `STARTED -> IN_PROGRESS -> DONE` (or `COMPENSATING -> FAILED`)
- `@NetScopeClient` interfaces for every service called in the saga
- REST: `POST /saga/{sagaId}/start`, `GET /saga/status/{correlationId}`, `GET /saga`

### Saga state machine

```
               ┌─────────────────────────────────────┐
POST /start    │                                     │
──────────► STARTED ──► IN_PROGRESS                  │
                             │                        │
                  step 1 OK ─┤                        │
                  step 2 OK ─┤                        │
                  step N OK ─┴──► DONE ───────────────┘
                                                      │
                  any step FAILS                      │
                             │                        │
                             ▼                        │
                        COMPENSATING                  │
                  (compensate N ... 1 in reverse)     │
                             │                        │
                             ▼                        │
                           FAILED ───────────────────►┘
```

Step-by-step behavior:
1. `POST /saga/{sagaId}/start` persists a `SagaInstance` row with status `STARTED`.
2. The orchestrator calls each `SagaStep` in order via NetScope gRPC. Status advances to `IN_PROGRESS`.
3. On step success: advances to the next step.
4. On step failure: transitions to `COMPENSATING` and calls compensation methods in **reverse order** (newest step first).
5. After all compensations: transitions to `FAILED`.
6. When all steps succeed: transitions to `DONE`.

### Saga completion callbacks

After a saga reaches `DONE` or `FAILED`, the orchestrator calls back the **owner service** via HTTP:

| Outcome | Endpoint called on owner service |
|---|---|
| Success | `POST /internal/saga-complete/{correlationId}` |
| Failure | `POST /internal/saga-failed/{correlationId}` |

These endpoints are implemented in `SagaCompletionController.java` (generated into the owner service's `saga/` package). The controller extracts the aggregate ID from the saga payload (e.g., `orderId`), then marks the entity `CONFIRMED` on success or `CANCELLED` on failure.

The owner URL is configured in the orchestrator's `application.yml`:

```yaml
fractalx:
  saga:
    owner-urls:
      place-order-saga: ${PLACE_ORDER_SAGA_OWNER_URL:http://localhost:8081}
```

### Saga notification retry mechanism

The initial callback is fire-and-forget. If the owner service is down, `SagaInstance` tracks retry state so no notification is permanently lost.

**Three extra columns on `SagaInstance`:**

| Field | Column | Default | Purpose |
|---|---|---|---|
| `ownerNotified` | `owner_notified BOOLEAN` | `false` | Set `true` when callback succeeds |
| `notificationRetryCount` | `notification_retry_count INT` | `0` | Incremented on each failed attempt |
| `lastNotificationAttempt` | `last_notification_attempt TIMESTAMP` | `null` | Timestamp of most recent attempt |

**`retryPendingNotifications()` scheduled method** (generated into each `*SagaService`):

```java
private static final int MAX_NOTIFICATION_RETRIES = 10;

@Scheduled(fixedDelay = 2000)
@Transactional
public void retryPendingNotifications() {
    List<SagaInstance> pending = sagaRepository
        .findByOwnerNotifiedFalseAndStatusIn(List.of(SagaStatus.DONE, SagaStatus.FAILED));
    for (SagaInstance instance : pending) {
        if (instance.getNotificationRetryCount() >= MAX_NOTIFICATION_RETRIES) {
            log.error("Saga notification dead-letter: correlationId={} — manual intervention required", ...);
            continue;
        }
        if (instance.getStatus() == SagaStatus.DONE) notifyOwnerComplete(instance);
        else notifyOwnerFailed(instance);
    }
}
```

| Parameter | Value |
|---|---|
| Poll interval | 2 seconds (`fixedDelay = 2000`) |
| Max retries | 10 |
| Dead-letter action | Log `ERROR` with `correlationId`, stop retrying |
| Recovery on owner restart | Automatic — next poll (≤ 2 s) delivers the notification |

---

## 16. Observability & Monitoring

FractalX generates a complete observability stack automatically -- no instrumentation code required.

### Distributed tracing -- OpenTelemetry + Jaeger

Each service gets three generated OTel configuration classes:

**`OtelConfig.java`** (`@ConditionalOnMissingBean(OpenTelemetry.class)`) configures the OpenTelemetry SDK:
- Exports spans via **OTLP/gRPC** to Jaeger (port 4317)
- Registers **W3C `traceparent`** and **W3C Baggage** propagators
- Tags every span with `service.name`
- Uses `BatchSpanProcessor` for non-blocking export

**`CorrelationTracingConfig.java`** (Spring MVC `HandlerInterceptor`):
- Reads `MDC["correlationId"]` on every HTTP request
- Tags the active OTel/Micrometer span with `correlation.id` (dot-separated key)
- Enables Jaeger search: `/api/traces?tags=correlation.id%3D<value>`

**`TracingExclusionConfig.java`**:
- Suppresses actuator endpoint spans (`/actuator/**`) from being exported
- Suppresses `@Scheduled` task spans that would otherwise pollute Jaeger

To disable tracing for a specific service in `fractalx-config.yml`:

```yaml
fractalx:
  services:
    inventory-service:
      tracing:
        enabled: false
```

Disabling tracing omits all three classes and sets `management.tracing.sampling.probability: 0.0`. Correlation ID propagation and log shipping are **not affected**.

> The **API Gateway** and **Saga Orchestrator** are always traced regardless of any per-service flag.

Jaeger UI: **http://localhost:16686**

### Correlation ID propagation

A **correlation ID** is a UUID that identifies a single logical request chain across all services. It is distinct from an OTel trace ID (though they are linked via the `correlation.id` span tag).

```
HTTP Client
    │  X-Correlation-Id: f47ac10b-...  (optional — generated if missing)
    ▼
TraceFilter (fractalx-runtime, @Order(HIGHEST_PRECEDENCE))
    │  Reads X-Correlation-Id header OR generates UUID
    │  Puts correlationId into MDC("correlationId")
    │  Echoes header back in response
    ▼
HTTP Handler (@RestController)
    │  MDC["correlationId"] = "f47ac10b-..."
    │  FractalLogAppender reads it for every log event
    ▼
NetScopeContextInterceptor (client side, outgoing gRPC call)
    │  Reads MDC["correlationId"]
    │  Injects x-correlation-id gRPC metadata on every outgoing call
    ▼
NetScopeContextInterceptor (server side, receiving service)
    │  Extracts x-correlation-id from gRPC metadata
    │  Populates MDC["correlationId"] in onMessage() and onHalfClose()
    │  Generates a new UUID if header absent
    ▼
Downstream service handler
    │  MDC["correlationId"] = same "f47ac10b-..." as originating request
    │  All logs carry the same correlation ID
```

Key points:
- `TraceFilter` runs at `Ordered.HIGHEST_PRECEDENCE` — before Spring Security, before any `@RestController`.
- `NetScopeContextInterceptor` propagates via `x-correlation-id` gRPC metadata key (lowercase).
- `OutboxPoller` saves the correlation ID to `OutboxEvent` (captured from MDC) and forwards it as `X-Correlation-Id` when calling the saga orchestrator.
- `CorrelationTracingConfig` (generated `HandlerInterceptor`) reads `MDC["correlationId"]` and tags the active OTel span with `correlation.id` — making traces searchable in Jaeger by correlation ID.

**NetScope gRPC wiring internals:**
- `NetScopeGrpcInterceptorConfigurer` — a `BeanPostProcessor` in `fractalx-runtime` that intercepts both the `NetScopeChannelFactory` (client) and `NetScopeGrpcServer` (server) beans to wire `NetScopeContextInterceptor` automatically.
- `CorrelationAwareNetScopeGrpcServer` — overrides the default NetScope gRPC server bean to ensure the interceptor is applied even when the server bean is created before the interceptor.

The same ID flows through MDC (SLF4J), gRPC metadata, W3C `traceparent`, and all log entries.

### Service health metrics

For every service with gRPC dependencies, `ServiceHealthConfig.java` provides:

- One Spring `HealthIndicator` per NetScope peer (2s TCP connect to gRPC port)
- Micrometer gauge `fractalx.service.dependency.up{service=<name>}` (1.0 = UP, 0.0 = DOWN)
- Exposed via `/actuator/health` and `/actuator/metrics`

### Centralized logger service

`logger-service` (port 9099) receives structured logs from all services via `FractalLogAppender`:

| Endpoint | Description |
|---|---|
| `POST /api/logs` | Ingest a log entry |
| `GET /api/logs?correlationId=&service=&level=&page=&size=` | Paginated log search |
| `GET /api/logs/services` | Distinct service names that have sent logs |
| `GET /api/logs/stats` | Per-service log count and error rate |

### Alert system

`admin-service` evaluates alert rules every 30 seconds:

| Rule | Condition | Threshold | Severity |
|---|---|---|---|
| `service-down` | health != UP | 2 consecutive failures | CRITICAL |
| `high-response-time` | p99 response time | > 2000 ms (3 consecutive) | WARNING |
| `error-rate` | HTTP error rate | > 10% (3 consecutive) | WARNING |

**Notification channels:**

| Channel | Default | Config key |
|---|---|---|
| Admin UI (SSE) | Enabled | -- |
| Webhook | Disabled | `ALERT_WEBHOOK_URL` |
| Email (SMTP) | Disabled | `SMTP_HOST`, `SMTP_FROM`, `ALERT_EMAIL_TO` |
| Slack | Disabled | `SLACK_WEBHOOK_URL` |

<details>
<summary>Full alerting.yml reference</summary>

```yaml
fractalx:
  alerting:
    enabled: true
    eval-interval-ms: 30000
    rules:
      - name: service-down
        condition: health
        threshold: 1
        severity: CRITICAL
        enabled: true
        consecutive-failures: 2
      - name: high-response-time
        condition: response-time
        threshold: 2000
        severity: WARNING
        enabled: true
        consecutive-failures: 3
    channels:
      admin-ui:
        enabled: true
      webhook:
        enabled: false
        url: ${ALERT_WEBHOOK_URL:}
      email:
        enabled: false
        smtp-host: ${SMTP_HOST:}
        smtp-port: ${SMTP_PORT:587}
        from: ${SMTP_FROM:}
        to: ${ALERT_EMAIL_TO:}
      slack:
        enabled: false
        webhook-url: ${SLACK_WEBHOOK_URL:}
```

</details>

All generated services expose `/actuator/prometheus` for Prometheus scraping.

---

## 17. Admin Dashboard

Access at **http://localhost:9090** (default credentials: `admin / admin`).

Override credentials at runtime via environment variables (no rebuild required):

```bash
ADMIN_USERNAME=myadmin
ADMIN_PASSWORD=supersecret
```

These can also be set via `@AdminEnabled(username = "...", password = "...")` on your `MonolithApplication` class before decomposition.

### Dashboard sections

| Group | Section | What you get |
|---|---|---|
| Overview | **Overview** | 6 KPI cards + service health table + recent incidents + activity timeline |
| Overview | **Services** | All registered services from registry with health indicators |
| Architecture | **Communication** | NetScope gRPC connection graph; upstream/downstream per service |
| Architecture | **Data Consistency** | Saga state, outbox backlog, cross-service FK validation |
| Architecture | **Network Map** | Canvas force-directed live service mesh with health overlay |
| Monitoring | **Observability** | Per-service metrics: health, p99, error rate, uptime % |
| Monitoring | **Alerts** | Active alert table (one-click resolve) + full history + SSE feed |
| Monitoring | **Traces** | Search by Correlation ID or service; links to Jaeger UI |
| Monitoring | **Logs** | Paginated log viewer: correlationId, service, level filters |
| Monitoring | **Analytics** | Historical charts: RPS trends, CPU/memory, error rates |
| Developer | **API Explorer** | 3-panel: service selector, endpoint list, request/response with auth presets |
| Developer | **gRPC Browser** | Services with gRPC ports, TCP ping tool (latency), dep map |
| Developer | **Config Editor** | Per-service env var table, in-memory override, hot-reload |
| Operations | **Incidents** | P1-P4 severity lifecycle: OPEN -> INVESTIGATING -> RESOLVED |
| Operations | **Settings** | Admin user management, site settings, theme |

### Database persistence (admin service)

By default the admin service uses H2 in-memory. To persist users and settings:

```yaml
# fractalx-config.yml -- bake credentials before decomposition
fractalx:
  admin:
    datasource:
      url: jdbc:mysql://localhost:3306/admin_db?useSSL=false&serverTimezone=UTC
      username: admin_user
      password: secret
```

Then activate the `db` profile:

```bash
cd microservices/admin-service
mvn spring-boot:run -Dspring-boot.run.profiles=db
```

| Database | JDBC URL pattern |
|---|---|
| H2 (embedded, zero config) | `jdbc:h2:./admin-service;DB_CLOSE_DELAY=-1;MODE=MySQL` |
| MySQL 8+ | `jdbc:mysql://localhost:3306/admin_db?useSSL=false&serverTimezone=UTC` |
| PostgreSQL 15+ | `jdbc:postgresql://localhost:5432/admin_db` |

<details>
<summary>Full Admin REST API reference</summary>

#### Topology & health

| Method | Path | Description |
|---|---|---|
| `GET` | `/dashboard` | HTML dashboard |
| `GET` | `/api/topology` | Service dependency graph (nodes + edges) |
| `GET` | `/api/health/summary` | Live health status map |
| `GET` | `/api/services/all` | All registered services (registry proxy) |

#### Observability

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/observability/metrics` | Per-service snapshot: health, p99, error rate, uptime |
| `GET` | `/api/traces?correlationId=&service=&limit=` | Jaeger proxy -- search traces |
| `GET` | `/api/traces/{traceId}` | Jaeger proxy -- single trace |
| `GET` | `/api/logs?correlationId=&service=&level=&page=&size=` | Logger-service proxy |
| `GET` | `/api/analytics/overview` | Platform-wide analytics |
| `GET` | `/api/analytics/service/{name}` | Historical metrics for one service |

#### Alerts

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/alerts?page=&size=&severity=&service=` | Paginated alert history |
| `GET` | `/api/alerts/active` | Unresolved alerts |
| `POST` | `/api/alerts/{id}/resolve` | Manually resolve |
| `GET` | `/api/alerts/stream` | SSE real-time feed |
| `GET` | `/api/alerts/config` | Current alert rules |
| `PUT` | `/api/alerts/config/rules` | Update alert rules |

#### Configuration

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/config/services` | All service configurations |
| `GET` | `/api/config/runtime` | Platform config + current overrides + effective values |
| `PUT` | `/api/config/runtime/{key}` | In-memory override `{"value":"..."}` |
| `DELETE` | `/api/config/runtime/{key}` | Revert to generation-time default |
| `GET` | `/api/config/runtime/diff` | Keys whose effective value differs from default |
| `POST` | `/api/config/editor/reload/{service}` | Trigger `/actuator/refresh` on a service |

#### gRPC Browser

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/grpc/services` | Services with active gRPC ports |
| `POST` | `/api/grpc/ping` | TCP probe `{host, port}` -> `{reachable, latencyMs}` |
| `GET` | `/api/grpc/{service}/deps` | Upstream/downstream gRPC deps |

#### Incidents

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/incidents` | All incidents, sorted by created desc |
| `POST` | `/api/incidents` | Create `{title, description, severity, affectedService}` |
| `PUT` | `/api/incidents/{id}/status` | Update `{status, notes}` |
| `DELETE` | `/api/incidents/{id}` | Delete |

#### Users

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/users` | List all admin users |
| `POST` | `/api/users` | Create a user |
| `PUT` | `/api/users/{id}` | Update a user |
| `DELETE` | `/api/users/{id}` | Delete a user |

#### Topology response example

```json
{
  "nodes": [
    { "id": "order-service",     "port": 8081, "type": "microservice" },
    { "id": "payment-service",   "port": 8082, "type": "microservice" },
    { "id": "fractalx-gateway",  "port": 9999, "type": "gateway" },
    { "id": "fractalx-registry", "port": 8761, "type": "registry" }
  ],
  "edges": [
    { "source": "order-service", "target": "payment-service", "protocol": "grpc" }
  ]
}
```

</details>

---

## 18. Static Verification

```bash
cd your-monolith
mvn fractalx:verify

# One-shot: decompose + full verification
mvn fractalx:decompose fractalx:verify \
    -Dfractalx.verify.compile=true \
    -Dfractalx.verify.failBuild=true
```

Results are printed with `✓` / `✘` / `⚠` markers. `✘` findings are hard failures; `⚠` findings
are advisory warnings. See [Running FractalX](#7-running-fractalx) for level details.

### Understanding `[FAIL]` vs `[WARN]`

| Marker | Meaning | Action required |
|---|---|---|
| `✘ Cross-boundary import` | A generated service directly imports a class from another service's package and that class was not copied locally | Ensure the dep is declared in `@DecomposableModule(dependencies={...})` and re-decompose |
| `⚠ ORPHAN` | The dependency graph analyser could not resolve the edge from this service to its declared deps -- usually because explicit `dependencies=` are absent | Add `dependencies=` to the module's `@DecomposableModule` annotation |
| `⚠ Tx` | A `@Transactional` method calls a NetScope client (remote gRPC call) -- rollback will not propagate across the network | Refactor to use the Saga orchestrator or Outbox pattern |
| `⚠ Secret` | A YAML/properties key with a sensitive name (password, token, api-key…) has a plain-text value | Rotate the value before production; use `${ENV_VAR}` placeholders instead |

### Decomposition Hints

After `NetScopeClientWiringStep` completes, FractalX analyses every generated service for patterns
that **silently break** when code moves from a single JVM to separate processes. When any are
found, a `DECOMPOSITION_HINTS.md` file is written to the service root.

```bash
cat microservices/order-service/DECOMPOSITION_HINTS.md
```

| Gap | What is detected | Why it matters |
|---|---|---|
| **@Transactional crossing service boundary** | `@Transactional` methods that call `*Client` (remote gRPC) methods | The monolith's single DB transaction no longer covers the remote call; a local rollback after a successful remote call leaves inconsistent state |
| **Shared in-memory cache** | `@Cacheable`, `@CacheEvict`, `@CachePut` annotations | Caffeine/EhCache is per-JVM; a cache eviction in Service A is invisible to Service B's copy |
| **Spring events across services** | `publishEvent()` calls and `@EventListener` / `@TransactionalEventListener` methods | Spring events are in-JVM only; cross-service listeners never fire after decomposition |
| **AOP aspect scope** | `@Aspect` classes | Pointcuts only intercept calls within the same JVM; the aspect stops firing for classes moved to other services |
| **@Scheduled jobs calling cross-service clients** | `@Scheduled` methods that invoke `*Client` interfaces | The job runs locally but the data it operates on may have moved to another service's database |

Each detected occurrence includes the file name, method name, and specific fix recommendations (e.g. "convert to `@DistributedSaga`", "use the outbox pattern", "migrate cache to Redis").

`DECOMPOSITION_HINTS.md` is only created when at least one issue is found. Services with no detectable issues produce no file.

### Why re-decompose is required after framework upgrades

`mvn fractalx:verify` runs verifiers against whatever files are currently in the output directory.
If that directory was produced by an older version of FractalX, it reflects the older generator's
behaviour and will show stale failures. Always delete the output directory and re-run
`mvn fractalx:decompose` after upgrading `<fractalx.version>`:

```bash
rm -rf microservices/          # or whatever outputDir is configured
mvn fractalx:decompose
mvn fractalx:verify
```

---

## 19. Test Endpoints

### Health checks

```bash
# Registry
curl http://localhost:8761/services/health

# Via gateway
curl http://localhost:9999/api/orders/actuator/health

# Direct (shows dependency health indicators from ServiceHealthConfig)
curl http://localhost:8081/actuator/health

# Prometheus metrics
curl http://localhost:8081/actuator/prometheus
```

### Correlation ID tracing

```bash
# All gateway responses include X-Correlation-Id -- or pass your own
curl http://localhost:9999/api/orders \
    -H "X-Correlation-Id: my-trace-id"

# Query logs for that ID
curl "http://localhost:9099/api/logs?correlationId=my-trace-id" | jq .

# Search traces in admin
curl "http://localhost:9090/api/traces?correlationId=my-trace-id" | jq .
```

### Alerts

```bash
curl http://localhost:9090/api/alerts/active | jq .
curl -X POST http://localhost:9090/api/alerts/<id>/resolve

# Real-time SSE alert stream
curl -N http://localhost:9090/api/alerts/stream
```

### Example business requests

```bash
# Process a payment
curl -X POST http://localhost:9999/api/payments/process \
    -H "Content-Type: application/json" \
    -d '{"customerId":"CUST001","amount":100.50}'

# Create an order (calls payment-service via NetScope gRPC internally)
curl -X POST http://localhost:9999/api/orders \
    -H "Content-Type: application/json" \
    -d '{"customerId":"CUST001","amount":100.50}'

# With API key (if enabled)
curl -X POST http://localhost:9999/api/orders \
    -H "Content-Type: application/json" \
    -H "X-Api-Key: my-api-key" \
    -d '{"customerId":"CUST001","amount":100.50}'

# With Bearer JWT (if enabled)
curl -X POST http://localhost:9999/api/orders \
    -H "Authorization: Bearer <token>" \
    -H "Content-Type: application/json" \
    -d '{"customerId":"CUST001","amount":100.50}'
```

---

## 20. Configuration Reference

### Environment variables -- all generated services

| Variable | Default | Description |
|---|---|---|
| `FRACTALX_REGISTRY_URL` | `http://localhost:8761` | Registry base URL |
| `FRACTALX_REGISTRY_HOST` | `localhost` | This service's advertised host |
| `SPRING_PROFILES_ACTIVE` | `dev` | `dev` or `docker` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | OpenTelemetry collector endpoint |
| `OTEL_SERVICE_NAME` | service name | Span service name tag |
| `FRACTALX_LOGGER_URL` | `http://localhost:9099/api/logs` | Centralized logger ingest URL |
| `JAEGER_QUERY_URL` | `http://localhost:16686` | Jaeger query API (admin service) |

### Gateway variables

| Variable | Default | Description |
|---|---|---|
| `GATEWAY_SECURITY_ENABLED` | `false` | Enable gateway authentication |
| `JWT_SECRET` | dev default | HMAC secret for Bearer JWT (min 32 chars) |
| `OAUTH2_JWK_URI` | Keycloak default | JWK Set URI for OAuth2 |
| `CORS_ORIGINS` | `http://localhost:3000,...` | Allowed CORS origins |
| `GATEWAY_DEFAULT_RPS` | `100` | Default rate limit (req/s per IP per service) |

### Alert notification variables

| Variable | Description |
|---|---|
| `ALERT_WEBHOOK_URL` | Webhook URL for alert notifications |
| `SLACK_WEBHOOK_URL` | Slack incoming webhook URL |
| `SMTP_HOST` | SMTP server for email alerts |
| `SMTP_PORT` | SMTP port (default: `587`) |
| `SMTP_FROM` | Sender email address |
| `ALERT_EMAIL_TO` | Recipient email address |

### Admin service database variables

| Variable | Default | Description |
|---|---|---|
| `ADMIN_DB_URL` | H2 embedded | JDBC URL (only with `db` profile) |
| `ADMIN_DB_USERNAME` | `sa` | Database username |
| `ADMIN_DB_PASSWORD` | *(empty)* | Database password |
| `ADMIN_DB_DRIVER` | `org.h2.Driver` | JDBC driver class name |

<details>
<summary>Generated application.yml structure</summary>

```yaml
# application.yml (base -- common to all profiles)
fractalx:
  registry:
    url: ${FRACTALX_REGISTRY_URL:http://localhost:8761}
    enabled: true
    host: ${FRACTALX_REGISTRY_HOST:localhost}
  observability:
    tracing: true
    metrics: true
    logger-url: ${FRACTALX_LOGGER_URL:http://localhost:9099/api/logs}
    otel:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4317}

netscope:
  server:
    grpc:
      port: <HTTP_PORT + 10000>

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
      show-components: always
  tracing:
    sampling:
      probability: 1.0
```

`application-dev.yml` -- H2 in-memory DB, localhost for all peers. Active by default.

`application-docker.yml` -- all values from environment variables, Docker DNS hostnames. Activated with `SPRING_PROFILES_ACTIVE=docker`.

</details>

---

## 21. Troubleshooting

### "Could not register with fractalx-registry"

The registry is not yet running. Start `fractalx-registry` first. The `start-all.sh` script handles ordering automatically.

### "Could not resolve X from registry" (NetScope gRPC)

The target service has not registered yet. Verify it started cleanly:

```bash
curl http://localhost:8761/services | jq .
```

Static YAML fallback is used automatically, so calls should continue to work.

### Traces not appearing in Jaeger

Verify Jaeger is running on port 4317:

```bash
docker compose ps jaeger

# Or start standalone
docker run -d --name jaeger \
  -e COLLECTOR_OTLP_ENABLED=true \
  -p 16686:16686 -p 4317:4317 \
  jaegertracing/all-in-one:1.53
```

Verify `OTEL_EXPORTER_OTLP_ENDPOINT` is set correctly in your service.

### Logs not visible in logger service

```bash
curl http://localhost:9099/api/logs/services

# Search by correlation ID from response header
curl "http://localhost:9099/api/logs?correlationId=<id>"
```

Check `FRACTALX_LOGGER_URL` points to the correct host.

### Alert evaluator not firing

Verify `fractalx.alerting.enabled: true` in `alerting.yml`. Reduce interval for testing:

```yaml
fractalx:
  alerting:
    eval-interval-ms: 5000
```

### Gateway returns 503

The circuit breaker has opened. Check `/actuator/health` on the target service. The circuit resets after 30 seconds by default (configurable via `resilience4j.circuitbreaker.instances.<name>.wait-duration-in-open-state`).

### Versioned service beans (`LmsClientV2`, `PaymentServiceV3`) not wiring correctly

FractalX strips a trailing `V<number>` suffix before mapping a bean type to its service name, so `LmsClientV2` and `LmsClient` both resolve to `lms-service`. If a service still fails to wire, declare the dependency explicitly:

```java
@DecomposableModule(
    serviceName = "order-service", port = 8081,
    dependencies = {LmsClientV2.class, PaymentService.class}
)
public class OrderModule {}
```

### Shared utility class not found after decomposition

If a `cannot find symbol` error references a class that lives outside any `@DecomposableModule` package (e.g. `com.myapp.shared.MoneyUtils`), FractalX's `SharedCodeCopier` should have copied it automatically. If it did not:

1. Verify the class is imported with an explicit (non-wildcard) `import` statement in the module's source files.
2. Verify the class is not in another module's package — cross-module classes are handled by NetScope, not copied.
3. Re-run `mvn fractalx:decompose` after correcting the import.

### H2 startup error -- `SET FOREIGN_KEY_CHECKS`

Generated schema contains MySQL-specific syntax. Either switch to MySQL in `application-dev.yml`, or disable SQL init:

```yaml
spring.sql.init.enabled: false
```

### Debug tips

```bash
mvn spring-boot:run -e -X                                    # full Maven debug
mvn spring-boot:run -Dspring-boot.run.arguments="--debug"    # Spring condition report
tail -f microservices/order-service.log                    # service log
curl -v http://localhost:9999/api/orders 2>&1 | grep -i "correlation\|trace"
```

---

## 22. Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-change`
3. Build all modules: `cd fractalx-parent && mvn clean install -DskipTests`
4. Test against `fractalx-test-db-app`: `mvn test`
5. Open a pull request with a clear description of the change and why

Please keep changes focused. Each PR should address a single concern.

---

## 23. Security Architecture

FractalX implements a **three-layer security model** for decomposed microservices. Security is
enforced once at the API Gateway and propagated cryptographically to all downstream services —
including cross-service gRPC calls via NetScope.

### Architecture overview

```
[External Client]
  │  Authorization: Bearer <user-jwt>        (or OAuth2 / Basic / API Key)
  ▼
[API Gateway]  ── validates credential ──────────────────────────────────────────────────────────────────┐
  │  mints short-lived Internal Call Token                                                               │
  │  X-Internal-Token: <jwt>  (sub=userId, roles=..., iss=fractalx-gateway, exp=+30s)                   │
  ▼                                                                                                      │
[Service A  (HTTP)]                                                                          fractalx.gateway.security
  │  GatewayAuthHeaderFilter validates X-Internal-Token signature                                        │
  │  SecurityContextHolder.setAuthentication(UsernamePasswordAuthenticationToken)                        │
  │  @PreAuthorize("hasRole('ADMIN')") ✓ enforced                                                        │
  │                                                                                                      │
  │  makes gRPC call to Service B via NetScope                                                           │
  │  x-internal-token gRPC metadata = same signed token (forwarded by NetScopeContextInterceptor)        │
  ▼
[Service B  (gRPC)]
    NetScopeContextInterceptor validates x-internal-token signature (server side)
    SecurityContextHolder.setAuthentication(...)  ← same principal
    @PreAuthorize ✓ enforced
```

### Layer 1 — API Gateway authentication

The gateway validates the external user's credential (Bearer JWT, OAuth2, Basic Auth, API Key)
and converts it into a short-lived **Internal Call Token** — a HMAC-SHA256 signed JWT minted
by the gateway:

```json
{
  "sub": "user-123",
  "roles": "ADMIN,USER",
  "iss": "fractalx-gateway",
  "iat": 1711300000,
  "exp": 1711300030
}
```

The token is forwarded downstream as the `X-Internal-Token` HTTP header. Raw credentials (Bearer
tokens, passwords) are **never** forwarded to downstream services.

Enable gateway authentication via `fractalx.gateway.security.*`:

```yaml
fractalx:
  gateway:
    security:
      enabled: true
      bearer:
        enabled: true        # HMAC-SHA256 Bearer JWT
        jwt-secret: ${JWT_SECRET:change-me}
      oauth2:
        enabled: false       # JWK Set URI (Keycloak / Auth0)
      basic:
        enabled: false
      api-key:
        enabled: false
      # Internal Call Token secret — must match fractalx.security.internal-jwt-secret
      # on ALL generated services. Set via env var FRACTALX_INTERNAL_JWT_SECRET.
      internal-jwt-secret: ${FRACTALX_INTERNAL_JWT_SECRET:fractalx-internal-secret-change-in-prod-!!}
```

### Layer 2 — Per-service HTTP security (generated)

When FractalX detects Spring Security in the monolith, it auto-generates two classes per service
under `<basePackage>.<serviceName>`:

| Generated class | Purpose |
|-----------------|---------|
| `GatewayAuthHeaderFilter` | Validates `X-Internal-Token` signature; populates `SecurityContextHolder` |
| `ServiceSecurityConfig` | `@EnableWebSecurity` + `@EnableMethodSecurity`; all HTTP endpoints `permitAll()` at the filter chain level (gateway is the auth perimeter); method-level authorization via `@PreAuthorize` |

The service's `application-dev.yml` receives:

```yaml
fractalx:
  security:
    internal-jwt-secret: ${FRACTALX_INTERNAL_JWT_SECRET:fractalx-internal-secret-change-in-prod-!!}
```

### Layer 3 — Secured inter-service gRPC communication

`fractalx-runtime`'s `NetScopeContextInterceptor` automatically:

- **Client side**: reads the `x-internal-token` from `Authentication.getCredentials()` (set by
  `GatewayAuthHeaderFilter`) and injects it into outgoing gRPC metadata
- **Server side**: validates the token from incoming gRPC metadata and restores the `Authentication`
  in `SecurityContextHolder` for the duration of the method call

This means `@PreAuthorize` works identically on HTTP-invoked and gRPC-invoked service methods:

```java
// Works on HTTP (X-Internal-Token from gateway) AND gRPC (x-internal-token metadata)
@PreAuthorize("hasRole('ADMIN')")
public Order cancelOrder(Long orderId) { ... }
```

### User principal flow

```
External JWT claims:   { "sub": "user-123", "roles": "ADMIN" }
         ↓ gateway validates + mints internal token (30s TTL)
X-Internal-Token:      signed JWT { sub, roles, iss=fractalx-gateway, exp }
         ↓ GatewayAuthHeaderFilter validates signature
SecurityContextHolder: UsernamePasswordAuthenticationToken
                       │  principal   = "user-123"
                       │  credentials = <raw internal token> (forwarded via gRPC)
                       │  authorities = [ROLE_ADMIN]
         ↓ @PreAuthorize("hasRole('ADMIN')") → passes
         ↓ service calls another service via NetScope gRPC
x-internal-token:      same signed token in gRPC metadata
         ↓ NetScopeContextInterceptor validates on receiving service
SecurityContextHolder: same Authentication { principal="user-123", ROLE_ADMIN }
         ↓ @PreAuthorize on the gRPC-invoked method → passes
```

### Configuring the shared secret

The Internal Call Token secret **must be identical** across the API Gateway and all generated
services. In production, set the `FRACTALX_INTERNAL_JWT_SECRET` environment variable on all
containers:

```bash
# docker-compose.yml  (or Kubernetes Secret)
environment:
  FRACTALX_INTERNAL_JWT_SECRET: "your-256-bit-production-secret-here"
```

The default value (`fractalx-internal-secret-change-in-prod-!!`) is intentionally weak and must
be replaced before going to production.

### What FractalX does NOT generate (requires manual work)

- **Login / token-issuance endpoint**: FractalX generates the _consumer_ side (validation).
  You need a dedicated auth service or external IdP (Keycloak, Auth0, Okta) that issues the
  external JWT that the gateway validates.
- **User storage**: `UserDetailsService` and `AuthenticationProvider` beans belong only to the
  service that owns the users table. Move them to your auth service; other services receive
  identity via `X-Internal-Token`.
- **mTLS**: For environments requiring cryptographic service-to-service identity beyond signed
  tokens, configure TLS on the NetScope gRPC channels at the infrastructure level (Istio, Linkerd,
  or manual gRPC TLS configuration).

### Decomposition Hints — Gap 8

If the monolith had Spring Security, `DECOMPOSITION_HINTS.md` is generated with **Gap 8** warnings
for patterns that require attention:

| Sub-gap | Pattern | Action |
|---------|---------|--------|
| 8a | `@EnableWebSecurity` class | Superseded by generated `ServiceSecurityConfig`; review original access rules |
| 8b | `@PreAuthorize` / `@Secured` / `@RolesAllowed` | Preserved; works via generated filter + gRPC propagation |
| 8c | Custom `OncePerRequestFilter` (JWT filter) | Remove; gateway consumed the token; service receives `X-Internal-Token` instead |
| 8d | `UserDetailsService` / `AuthenticationProvider` bean | Move to auth service only; other services trust the signed token |

---

## 24. Known Limitations

FractalX is a **static decomposition tool**. It produces a solid starting point for microservice
extraction, but there are inherent constraints in what static AST analysis can achieve. The
following are known limitations to be aware of before and after running a decomposition.

---

### Analysis & detection

**Static analysis only — no runtime coupling detection**
FractalX scans source code with JavaParser. Patterns that only manifest at runtime — such as
`ApplicationContext.getBean(X.class)`, dynamic proxies, reflection-based service location, or
beans registered programmatically — are not detected. Cross-module calls made through these
mechanisms are silently absent from the generated NetScope clients.

**Field injection and Spring stereotype annotations required for inference**
`ModuleAnalyzer` identifies cross-module dependencies from `@Autowired` / `@Inject` fields,
Lombok `@RequiredArgsConstructor` / `@AllArgsConstructor` constructor injection, and Spring
stereotype annotations (`@Service`, `@Component`, `@Repository`, etc.) on the injected types.
Method-level injection (`@Autowired` on a setter), bean factory methods, or constructor injection
without Lombok annotations may be missed. Programmatically registered beans and reflection-based
service location are invisible to static analysis. Always review the generated `*Client` interfaces
against your actual dependency graph, or declare dependencies explicitly with `dependencies=`.

**Wildcard and static imports not resolved by `SharedCodeCopier`**
`SharedCodeCopier` resolves shared classes by tracing explicit single-type imports
(`import com.example.Foo`). Wildcard imports (`import com.example.*`) and static imports
(`import static com.example.Foo.bar`) are skipped. Shared classes reachable only through wildcard
imports must be copied manually to the generated service.

**Circular cross-module dependencies**
If Module A depends on Module B and Module B depends on Module A, FractalX generates both modules
as NetScope clients of each other. This compiles and runs, but the circular gRPC dependency is an
architectural smell that FractalX does not resolve — it mirrors whatever coupling existed in the
monolith. Refactor the circular dependency into a shared event-driven model before decomposing.

**Complex `@PreAuthorize` SpEL not migrated to gateway**
`SecurityAnalyzer` skips `@PreAuthorize` expressions that contain parameter references
(e.g., `#id == principal.id`). These rules are not carried over to the gateway's route security
config. They survive on service methods and work via the generated `GatewayAuthHeaderFilter`, but
equivalent gateway-level enforcement must be added manually if needed.

---

### Framework & stack constraints

**Spring Boot only**
FractalX is built around Spring Boot, Spring Data JPA, and Spring Security idioms. Monoliths using
Jakarta EE, Micronaut, Quarkus, Ktor, or plain Spring (without Boot) are not supported. Key
annotations like `@SpringBootApplication`, `@Component`, `@Service`, `@Repository`, `@Entity` are
assumed throughout.

**Single-module Maven source**
The framework expects a single Maven module as the monolith source (one `pom.xml`, one `src/`
tree). Multi-module Maven projects where domain code is spread across multiple submodules are not
supported without first flattening them into a single source root.

**JPA / Spring Data assumed for data layer**
`DataIsolationGenerator` enforces data boundaries by generating `@EnableJpaRepositories` +
`@EntityScan`. Monoliths that use MyBatis, jOOQ, plain JDBC, or Hibernate without Spring Data
will not have their data layer correctly isolated — you must configure data access manually.

**Servlet-based security (not reactive / WebFlux)**
The generated `GatewayAuthHeaderFilter` extends `OncePerRequestFilter` (servlet stack). If a
decomposed module uses Spring WebFlux / reactive programming, this filter does not apply. Reactive
services need a `WebFilter` implementation instead; adapt the generated filter accordingly.

**gRPC (NetScope) only for inter-service communication**
All cross-service calls are tunnelled through NetScope's gRPC infrastructure. HTTP/REST
inter-service calls (e.g., Feign clients already in the monolith) are not preserved — they are
detected as dependencies and replaced with gRPC client interfaces. If external consumers call
those endpoints directly, ensure the gateway exposes the same REST paths.

---

### Generated code completeness

**Saga orchestrator client stubs require manual completion**
`SagaOrchestratorGenerator` generates `*Client` interfaces for each NetScope dependency of the
saga, but method signatures inside those interfaces are stubs that need to be completed manually
to match the actual service's exposed methods.

**Flyway migration scaffold only**
`FlywayMigrationGenerator` writes a `V1__init.sql` placeholder. It does not reverse-engineer the
monolith's existing database schema. The actual DDL for each service's tables must be written
manually (or extracted from the monolith's `ddl-auto: create` output and split by module).

**No unit/integration tests generated for infrastructure**
Tests for the module's business logic are copied from the monolith if they reside within the
module's package. Infrastructure classes generated by FractalX (sagas, outbox, correlation
filter, registry bridge, etc.) do not come with tests — these must be written by the developer.

**Output is a starting point, not production-ready**
The generated services compile and pass health checks. They are designed to be the starting
structure, not the final artefact. Review all `TODO` and `FRACTALX-WARNING` comments, resolve
all `DECOMPOSITION_HINTS.md` gaps, configure proper datasource URLs, replace placeholder
secrets, and add production-grade monitoring before deploying.

---

### Security constraints

**Auth-service auto-generated when auth pattern is detected**
FractalX scans the monolith for JWT configuration (`jwt.secret` / `jwt-secret` property), a
`UserDetails`-implementing JPA entity, and auth controller endpoints. When all three are found,
it generates a standalone `auth-service` (port 8090) that serves `POST /api/auth/login` and
`POST /api/auth/register`, issues HMAC-SHA256 JWTs, and pre-wires a gateway route to it.
All new registrations are assigned the `USER` role — elevated roles require a separate admin
workflow. If the monolith has no auth pattern, no auth-service is generated and an external IdP
(Keycloak, Auth0, Okta) or a custom auth service must be provided.

> **Security note:** The JWT secret used by the generated auth-service defaults to a placeholder
> value at development time. Set the `JWT_SECRET` environment variable before any production
> deployment — the service will log a loud warning at startup if the default is still in use.

**Internal Call Token has a 30-second TTL**
The signed JWT forwarded from the gateway to downstream services expires in 30 seconds. Long-running
synchronous request chains (deep NetScope call stacks, saga compensation flows) may encounter token
expiry mid-flight. In those cases the receiving service returns 401 and the saga must retry or
compensate. The TTL can be tuned by modifying the generated `JwtBearerFilter` minting code in the
gateway.

**Service perimeter trust is assumed**
`GatewayAuthHeaderFilter` validates the `X-Internal-Token` signature but does not enforce network
perimeter controls (firewall rules, mTLS). If a service is reachable directly (bypassing the
gateway), a caller that knows the internal JWT secret can forge valid tokens. In production,
combine the signed-token model with network-level controls (VPC, service mesh, Kubernetes
`NetworkPolicy`) to prevent direct service access.

---

### Operational constraints

**`fractalx-test-app` module not included**
The original `fractalx-test-app` Maven module was removed from the repository. The parent POM
still references it. This causes a warning during multi-module builds but does not affect
decomposition of other monoliths.

**Hard-coded defaults in generated config**
The `dev` profile uses hard-coded `localhost` addresses. The `docker` profile substitutes env
vars. Neither profile auto-discovers actual production hostnames — these must be configured
explicitly for staging and production deployments.

**No incremental decomposition**
FractalX is designed to be run on a clean `outputDir`. Re-running `mvn fractalx:decompose`
overwrites the entire output directory. Incremental updates (e.g., adding a new `@DecomposableModule`
after partial decomposition) require a full re-run. Any manual changes made to generated files
since the last run will be lost — keep your customisations in a separate overlay directory or
commit them and use diff tooling to re-apply them after a regeneration.

---

### Summary table

| Limitation | Impact | Workaround |
|------------|--------|------------|
| Static analysis only | Runtime-registered beans missed | Review generated clients; add missing deps manually |
| Field injection assumed | Constructor/setter injection missed | Use `@Autowired` field injection or Lombok in monolith |
| Wildcard imports not traced | Shared classes via `*` imports not copied | Add explicit imports or copy files manually |
| Circular module dependencies | Architectural smell preserved | Refactor to events before decomposing |
| Spring Boot / servlet stack only | WebFlux, Quarkus, etc. unsupported | N/A — framework is Spring Boot specific |
| Single Maven module source | Multi-module monoliths unsupported | Flatten source before decomposing |
| JPA/Spring Data assumed | MyBatis/jOOQ data layer not isolated | Configure data access manually |
| Complex SpEL `@PreAuthorize` skipped | Gateway doesn't carry those rules | Add route rules to gateway security config manually |
| Saga client stubs incomplete | gRPC method signatures need filling | Complete stub interfaces manually |
| Flyway scaffold only | No auto-extracted schema DDL | Write V1__init.sql from monolith schema |
| No auth service generated | No login/JWT issuance | Provide external IdP or write auth service |
| 30s internal token TTL | Deep call chains may 401 | Tune TTL in generated `JwtBearerFilter`; add retry |
| Full re-run required | Manual edits overwritten | Commit before re-running; use diff to re-apply |

---

## 25. JPA Data Layer and Entity Relationships

This section explains how FractalX transforms JPA entity relationships during decomposition,
what replaces cross-service JPA joins, and how the saga/outbox pattern maintains eventual
consistency across service boundaries.

---

### 25.1 Relationship Transformation Model

When a `@DecomposableModule` entity has a JPA relationship pointing to an entity in a **different
module**, FractalX automatically transforms it. Relationships between entities **within the same
module** are left untouched.

| Annotation | Same-module target | Cross-module (remote) target |
|---|---|---|
| `@ManyToOne` / `@OneToOne` | kept as-is; FK column in DDL | converted to `String *Id`; commented FK reference in DDL |
| `@OneToMany` | kept as-is | **field removed**; TODO comment inserted explaining NetScope replacement |
| `@ManyToMany` | kept as-is; join table in DDL | converted to `@ElementCollection List<String> *Ids`; element table in DDL |
| `@Embedded` / `@Embeddable` | columns inlined into parent table DDL (with `fieldName_` prefix) | N/A — embeddable objects are always local |
| `@MappedSuperclass` | inherited fields prepended to entity DDL | N/A — superclass hierarchies are always within one service |

**Before / after example:**

```java
// ── BEFORE decomposition (monolith) ──────────────────────────────────────────
@Entity
public class Order {
    // Customer is in the customer module (different @DecomposableModule)
    @ManyToOne
    @JoinColumn(name = "customer_id")
    private Customer customer;

    // OrderItem is in the same module
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    private List<OrderItem> items;

    // Address is an @Embeddable within the order module
    @Embedded
    private Address shippingAddress;
}

// ── AFTER decomposition (order-service) ──────────────────────────────────────
@Entity
public class Order {
    private String customerId;                    // ← @ManyToOne → String ID

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    private List<OrderItem> items;               // ← local @OneToMany → unchanged

    // @Embedded → columns inlined: shipping_address_street, shipping_address_city, ...
    private String shippingAddressStreet;
    private String shippingAddressCity;
    private String shippingAddressZip;
}
```

---

### 25.2 Cross-Service Data Access Patterns

JPA joins across service boundaries are impossible after decomposition. The following patterns
replace them:

#### 1. Referential Integrity
`ReferenceValidator` (generated by FractalX) calls `CustomerExistsClient.exists(customerId)` via
NetScope gRPC **before** persisting an `Order`. This ensures the remote entity actually exists.

```java
// In your @Transactional service method — generated ReferenceValidator usage:
referenceValidator.validateCustomerExists(order.getCustomerId());
orderRepository.save(order);
```

For collection fields, a **batch existence check** is used to avoid N+1 gRPC calls:

```java
// Batch variant (avoids calling exists() once per ID):
referenceValidator.validateProductsExist(order.getProductIds());
```

The remote service must expose `@NetworkPublic existsAll(List<String> ids)` returning the IDs
that do **not** exist.

#### 2. Fetching Related Data
Replace `order.getCustomer().getName()` with an explicit NetScope client call:

```java
// Instead of: String name = order.getCustomer().getName();
CustomerClient customer = customerClient.findById(order.getCustomerId());
String name = customer.getName();
```

#### 3. Replacing Removed @OneToMany Collections
When `Customer.orders` (`@OneToMany → Order`) is removed, access orders from the order-service:

```java
// Instead of: customer.getOrders()
List<Order> orders = orderClient.findOrdersByCustomerId(customer.getId());
```

The remote service must expose `@NetworkPublic List<Order> findOrdersByCustomerId(String id)`.
FractalX inserts a TODO comment in the entity file explaining the required NetScope method.

#### 4. Cross-Service Transactions
ACID transactions spanning two services are not possible. FractalX generates the **transactional
outbox pattern** to achieve eventual consistency:

1. Business state change + `OutboxEvent` are written in the **same local DB transaction**
2. `OutboxPoller` reads unpublished events every second and POSTs to the saga orchestrator
3. The saga orchestrator executes forward steps; compensation steps roll back on failure

---

### 25.3 Saga and Outbox Pattern

FractalX generates these components for services with JPA content and cross-module dependencies:

| Component | Purpose |
|---|---|
| `OutboxEvent` | JPA entity stored in `fractalx_outbox`; written atomically with business state |
| `OutboxRepository` | Spring Data repository; queries unpublished events |
| `OutboxPublisher` | Component to publish events inside `@Transactional` service methods |
| `OutboxPoller` | `@Scheduled(fixedDelay=1000)` — forwards events to saga orchestrator via HTTP |
| `OutboxDlqEvent` | Dead-letter entity for permanently-failed events (`fractalx_outbox_dlq`) |
| `OutboxDlqRepository` | Repository for the DLQ; monitor for saga failures |

**Configuring the saga orchestrator URL** (default: `http://localhost:8099`):

```yaml
# application.yml or environment variable
fractalx:
  saga:
    orchestrator.url: ${FRACTALX_SAGA_ORCHESTRATOR_URL:http://localhost:8099}
```

**Monitoring the dead-letter queue:**

```sql
-- Check for permanently-failed saga events (retryCount ≥ 5)
SELECT * FROM fractalx_outbox_dlq ORDER BY failed_at DESC;
```

Events in the DLQ have a `failed_reason` column. After fixing the root cause, you can replay
them by re-inserting into `fractalx_outbox` with `published = false` and `retry_count = 0`.

---

### 25.4 DDL Generation and Flyway

FractalX generates `src/main/resources/db/migration/V1__init.sql` using entity scanning:

**What is generated automatically:**

| Feature | Generated? |
|---|---|
| `CREATE TABLE` per `@Entity` | ✅ |
| Column types from Java field types | ✅ |
| `@Embedded` fields inlined with `fieldName_` prefix | ✅ |
| `@MappedSuperclass` inherited fields | ✅ |
| `@ElementCollection` table for decoupled `@ManyToMany` | ✅ |
| Join table for same-service `@ManyToMany` | ✅ |
| FK constraints for **within-service** `@ManyToOne`/`@OneToOne` | ✅ |
| `fractalx_outbox` table | ✅ (when JPA + cross-module deps detected) |
| `fractalx_outbox_dlq` table | ✅ |
| FK constraints for **cross-service** relationships | ❌ (enforced by `ReferenceValidator`) |
| `CHECK` constraints / enum types | ❌ (add manually) |
| Indexes beyond outbox | ❌ (add manually) |
| `@NamedQuery` SQL | ❌ (not rewritten) |

**Spring profile behaviour:**

| Profile | `ddl-auto` value | Purpose |
|---|---|---|
| default / dev | `update` | Hibernate auto-creates/alters schema — development convenience |
| docker | `validate` | Hibernate validates schema against entities — production-safe |

> **⚠️ `V1__init.sql` is a scaffold, not a complete production migration.**
> Review it before deploying to any non-development environment. Add missing indexes, column
> constraints (`NOT NULL`, `CHECK`), enum definitions, and enum-to-string mappings manually.

---

### 25.5 What Requires Manual Work After Decomposition

The following JPA patterns are **not automatically handled** by FractalX:

| Pattern | Action Required |
|---|---|
| Business logic reading `entity.getRemoteEntity().getField()` | Rewrite using NetScope client call |
| `@Query` JPQL with cross-service entity types in `FROM`/`JOIN` | Rewrite to use `*Id` String field |
| `@NamedQuery` / `@NamedNativeQuery` | Verify and update each one manually |
| Cascade delete (`cascade = CascadeType.REMOVE`) across services | Implement as saga compensation step |
| `@MappedSuperclass` with complex multi-level hierarchies | Verify DDL includes all inherited columns |
| Read replicas / multiple datasources | Configure manually in `application.yml` |
| Enum `@Column` type | Add `VARCHAR(50)` or database-native enum definition |
| `@Version` optimistic locking | Add `version BIGINT DEFAULT 0` column to DDL |

The `DECOMPOSITION_HINTS.md` generated in each service root lists detected occurrences of these
patterns under **Gap 9**.

---

## 26. API Gateway, Registry, and Service Communication

### 26.1 Generated Infrastructure Overview

FractalX generates four supporting services in addition to the decomposed microservices:

| Service | Default Port | Responsibility |
|---------|-------------|----------------|
| `fractalx-registry` | 8761 | Lightweight service registry — all services self-register on startup; gateway fetches live routes |
| `fractalx-gateway` | 9999 | API Gateway — security filter chain, circuit breaker, rate limiter, CORS, observability |
| `admin-service` | 9090 | Topology viewer, health dashboard, alert engine, live config management |
| `fractalx-saga-orchestrator` | 8099 | Distributed saga coordination (only generated when `@DistributedSaga` is present) |
| `logger-service` | 9099 | Centralized structured log store — all services forward logs via HTTP |

Startup order enforced by Docker Compose `depends_on` / `service_healthy`:
1. `fractalx-registry` (healthcheck: `/services/health`)
2. `logger-service`, `jaeger` (independent of each other, both after registry)
3. All microservices, gateway, admin, saga-orchestrator (after registry healthy)

---

### 26.2 API Gateway Architecture

The generated `fractalx-gateway` is a Spring Cloud Gateway application. Its security filter chain
processes requests in this order (lowest `@Order` runs first):

| Order | Filter | Responsibility |
|-------|--------|---------------|
| -100 | `TracingFilter` | Assigns `X-Request-Id`, propagates/generates `X-Correlation-Id`, tags OTel span |
| -99 | `RequestLoggingFilter` | Structured request/response log line (method, path, status, duration, correlationId) |
| -98 | `GatewayMetricsFilter` | Increments Micrometer counter `fractalx.gateway.requests` per route |
| -90 | `GatewayBearerJwtFilter` | Validates HMAC-SHA256 Bearer JWT; mints `X-Internal-Token` |
| -89 | `GatewayApiKeyFilter` | Validates `X-Api-Key` header or `?api_key=` param; mints `X-Internal-Token` |
| -88 | `GatewayBasicAuthFilter` | Validates HTTP Basic credentials; mints `X-Internal-Token` |
| -80 | `RateLimitFilter` | In-memory token-bucket rate limiter per IP + service |
| -70 | `CircuitBreakerFilter` | Resilience4j circuit breaker per route with fallback endpoint |

`TracingFilter` **must** run before `RequestLoggingFilter` so the correlation ID is set before
the first log line is written. All auth filters are disabled by default and enabled via:

```yaml
fractalx:
  gateway:
    security:
      enabled: true
      bearer.enabled: true    # HMAC-SHA256 JWT
      oauth2.enabled: true    # JWK Set (Keycloak / Auth0)
      basic.enabled: true     # username + password
      api-key.enabled: true   # X-Api-Key header
```

---

### 26.3 Service Registry

`fractalx-registry` is a lightweight REST registry (not Eureka). Each service registers itself
on startup via `ServiceRegistrationBean` (a `@PostConstruct` component) by posting:

```
POST http://fractalx-registry:8761/services/register
{ "name": "order-service", "host": "order-service", "port": 8081, "grpcPort": 18081 }
```

The gateway's `DynamicRouteLocatorConfig` polls the registry every 30 seconds and rebuilds
its in-memory route table. If the registry is unreachable the gateway falls back to the static
routes defined in `application.yml`.

`NetScopeRegistryBridge` (generated in each service that has gRPC dependencies) queries the
registry on startup and overrides `netscope.client.servers.<peer>.host` in the Spring
`Environment`, making container host names dynamic without editing YAML. All peer lookups
run **in parallel** (one thread per dependency) so registry latency does not multiply with
the number of dependencies. Each lookup retries up to `fractalx.registry.max-retries`
(default `10`) times with exponential back-off capped at 5 s, using a 2 s connect / 3 s
read timeout on the underlying HTTP call. If all retries are exhausted the static
`application.yml` hostnames are used as a fallback.

---

### 26.4 NetScope gRPC Communication

NetScope is FractalX's inter-service RPC layer built on gRPC.

**Port convention:** every service listens for gRPC on `HTTP port + 10000`.
Example: HTTP `:8081` → gRPC `:18081`.

**Server side** — methods on beans called by other modules are annotated `@NetworkPublic` by
`NetScopeServerAnnotationStep`. The NetScope runtime starts a gRPC server on the computed port
and exposes annotated methods.

**Client side** — `NetScopeClientGenerator` generates a `@NetScopeClient` interface mirroring
the target bean's public method signatures (including generic return types and `throws` clauses).
The generated interface is used exactly like the original Spring bean — no manual HTTP/gRPC code
required.

**Metadata propagation** — `NetScopeContextInterceptor` (in `fractalx-runtime`) handles both
sides of every gRPC call:

- *Client*: reads `correlationId` from MDC and the current Spring `Authentication` credentials,
  injects them as `x-correlation-id` and `x-internal-token` gRPC metadata headers.
- *Server*: extracts `x-correlation-id` (or generates a new one), re-populates MDC, then
  validates and reconstructs the Spring `Authentication` from `x-internal-token` so that
  `@PreAuthorize` rules work inside gRPC-invoked service methods.

---

### 26.5 Internal Call Token Flow

The **Internal Call Token** is a short-lived HMAC-SHA256 JWT that carries the user's identity
across gRPC hops so backend services can enforce role-based access without talking to an
external auth server.

```
Browser / Client
      │
      │  (Bearer JWT / API Key / Basic)
      ▼
fractalx-gateway
      │  validates external credential
      │  mints X-Internal-Token  ──► setSubject(userId) + claim("roles", ...) +
      │                               setIssuer("fractalx-gateway") + setAudience("fractalx-internal")
      │                               TTL = 30 seconds
      ▼
microservice (HTTP handler)
      │  NetScopeContextInterceptor (client side) reads X-Internal-Token
      │  injects into outgoing gRPC metadata
      ▼
peer microservice (gRPC handler)
      │  NetScopeContextInterceptor (server side) validates token:
      │    • signature (HMAC-SHA256, shared secret)
      │    • issuer == "fractalx-gateway"
      │    • audience == "fractalx-internal"
      │    • exp (auto-enforced by JJWT)
      │  reconstructs Spring Authentication → SecurityContextHolder
      ▼
  @PreAuthorize / method security enforced normally
```

The shared secret is set via env var (identical across all services):

```bash
FRACTALX_INTERNAL_JWT_SECRET=your-256-bit-production-secret-here
```

All three gateway auth paths (Bearer JWT, Basic, API Key) mint the token via the shared
`InternalTokenMinter` utility class (generated in `fractalx-gateway/security/`).

---

### 26.6 Known Limitations

| Area | Limitation | Workaround |
|------|-----------|------------|
| Rate limiter | In-memory, per-instance — does not share state across multiple gateway replicas | Replace `RateLimitFilter` with Redis-backed `spring-cloud-gateway` `RedisRateLimiter` |
| Service registry | No TTL / heartbeat — dead instances stay registered until restarted | Implement a `/services/heartbeat` endpoint and add eviction in the registry |
| Static YAML fallback | If registry is down at startup, `NetScopeRegistryBridge` retries up to `fractalx.registry.max-retries` times (default 10, exponential back-off, cap 5 s) **in parallel** per peer, then falls back to `application.yml` hostnames — these are `localhost` in dev | Lower `fractalx.registry.max-retries` (e.g. `3`) for a faster fallback; ensure registry is healthy before starting dependent services, or use Docker Compose `depends_on` |
| Internal token single-cluster | `aud=fractalx-internal` is a flat string — all services in the deployment share the same audience | For multi-cluster deployments, add a cluster-id claim and validate it in `NetScopeContextInterceptor` |
| gRPC TLS | Generated gRPC channels are plaintext — appropriate for within a private Docker network | Add TLS termination at the ingress or enable `netscope.server.tls` in the runtime config |
| OAuth2 multi-tenant | `SecurityAnalyzer` detects a single JWK Set URI — multiple tenants/issuers not supported | Configure `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` per tenant manually |

---

## 27. `fractalx-initializr-core` — Scaffold New Projects

`fractalx-initializr-core` is a **project scaffolding module** that generates a complete, ready-to-decompose modular monolith from a `fractalx.yaml` specification file. It is the inverse of `fractalx:decompose` — while `decompose` splits a monolith into microservices, `initializr-core` builds the monolith skeleton from scratch.

### When to use it

Use `fractalx-initializr-core` when starting a brand-new project. Instead of hand-writing a Spring Boot application with FractalX annotations, you describe your desired service boundaries in `fractalx.yaml` and the initializer generates all the boilerplate.

### `fractalx.yaml` specification format

```yaml
project:
  groupId: com.example
  artifactId: my-platform
  version: 1.0.0-SNAPSHOT
  javaVersion: "21"
  springBootVersion: 3.3.2
  description: "My FractalX modular monolith"

  services:
    - name: order-service
      port: 8081
      database: postgresql
      schema: order_db
      apiStyle: rest
      adminEnabled: true
      entities:
        - name: Order
          fields:
            - name: customerId
              type: Long
            - name: totalAmount
              type: BigDecimal
            - name: status
              type: String
      dependencies:
        - payment-service
        - inventory-service

    - name: payment-service
      port: 8082
      database: postgresql
      schema: payment_db
      apiStyle: rest
      entities:
        - name: Payment
          fields:
            - name: orderId
              type: Long
            - name: amount
              type: BigDecimal
            - name: status
              type: String

    - name: inventory-service
      port: 8083
      database: h2
      apiStyle: rest+grpc
      entities:
        - name: InventoryItem
          fields:
            - name: productId
              type: Long
            - name: quantity
              type: Integer

  sagas:
    - id: place-order-saga
      owner: order-service
      description: "Coordinates payment and inventory reservation"
      compensationMethod: cancelOrder
      timeoutMs: 30000
      steps:
        - service: payment-service
          method: processPayment
        - service: inventory-service
          method: reserveStock

  infrastructure:
    registry: true
    gateway: true
    adminService: true
    loggerService: true

  security:
    gatewayAuth: jwt
```

### `ProjectSpec` model fields

| Field | Type | Default | Description |
|---|---|---|---|
| `groupId` | `String` | `com.example` | Maven group ID |
| `artifactId` | `String` | `my-platform` | Maven artifact ID |
| `version` | `String` | `1.0.0-SNAPSHOT` | Project version |
| `javaVersion` | `String` | `21` | Java source/target version |
| `springBootVersion` | `String` | `3.3.2` | Spring Boot parent version |
| `packageName` | `String` | derived | Base Java package (derived as `groupId + "." + artifactId` if omitted) |
| `description` | `String` | — | Human-readable description |
| `services` | `List<ServiceSpec>` | — | Service boundary definitions |
| `sagas` | `List<SagaSpec>` | — | Distributed saga definitions |
| `infrastructure` | `InfraSpec` | all enabled | Which infrastructure services to generate |
| `security` | `SecuritySpec` | — | Gateway auth mechanism |

### `ServiceSpec` fields

| Field | Type | Default | Description |
|---|---|---|---|
| `name` | `String` | — | Kebab-case service name (e.g. `order-service`) |
| `port` | `int` | `8080` | HTTP port |
| `database` | `String` | `h2` | `h2` / `postgresql` / `mysql` / `mongodb` / `redis` |
| `schema` | `String` | derived | Database/schema name (defaults to `<package>_db`) |
| `apiStyle` | `String` | `rest` | `rest` / `grpc` / `rest+grpc` |
| `adminEnabled` | `boolean` | `false` | Generate `@AdminEnabled` on this service's application class |
| `independentDeployment` | `boolean` | `true` | Whether service can be deployed independently |
| `entities` | `List<EntitySpec>` | — | JPA entity definitions |
| `dependencies` | `List<String>` | — | Other service names this service calls (generates cross-module deps) |

### Generation pipeline

`ProjectInitializer.initialize(spec, outputRoot)` runs 13 sequential steps:

| Step | Generator | What it creates |
|---|---|---|
| 1 | `RootPomWriter` | Multi-module `pom.xml` with FractalX BOM |
| 2 | `ApplicationGenerator` | `@SpringBootApplication` class with `@AdminEnabled` if configured |
| 3 | `ApplicationYmlGenerator` | `application.yml` per service with ports, DB, OTel config |
| 4 | `ModuleMarkerGenerator` | `@DecomposableModule`-annotated service class skeletons |
| 5 | `EntityGenerator` | JPA `@Entity` classes from `EntitySpec` field definitions |
| 6 | `RepositoryGenerator` | `JpaRepository` interfaces for each entity |
| 7 | `ServiceClassGenerator` | `@Service` classes with stub methods for each dependency call |
| 8 | `ControllerGenerator` | `@RestController` classes with CRUD endpoints per entity |
| 9 | `FlywayMigrationGenerator` | `V1__initial_schema.sql` (for non-H2 databases) |
| 10 | `DockerComposeGenerator` | `docker-compose.yml` + `Dockerfile` per service |
| 11 | `GitHubActionsGenerator` | `.github/workflows/ci.yml` with build + test steps |
| 12 | `FractalxSpecWriter` | Copies/writes `fractalx-config.yml` into `src/main/resources/` |
| 13 | `ReadmeGenerator` | Project-level `README.md` describing the scaffold |

### Programmatic usage

```java
ProjectSpec spec = new SpecFileReader().read(Path.of("fractalx.yaml"));
new ProjectInitializer()
    .setProgressCallbacks(
        label -> System.out.println("  Starting: " + label),
        label -> System.out.println("  Done:     " + label)
    )
    .initialize(spec, Path.of("my-platform"));
```

### What you get

After running the initializer you have a fully compilable Spring Boot project with:
- One `@DecomposableModule`-annotated class per service
- Entities, repositories, service classes, and REST controllers
- `fractalx-config.yml` ready for `mvn fractalx:decompose`
- Docker Compose, Dockerfiles, and GitHub Actions CI
- Flyway migration stubs (for PostgreSQL/MySQL services)

The generated project immediately runs as a monolith (`mvn spring-boot:run`) and can be immediately decomposed (`mvn fractalx:decompose`).

---

## 28. Full Monolith Example — Annotated Code

Here is a minimal but complete modular monolith that FractalX can fully decompose into three independent microservices with a saga.

### `pom.xml` (relevant excerpt)

```xml
<properties>
    <fractalx.version>LATEST</fractalx.version><!-- replace with version from Maven Central badge -->
</properties>

<dependencies>
    <dependency>
        <groupId>org.fractalx</groupId>
        <artifactId>fractalx-annotations</artifactId>
        <version>${fractalx.version}</version>
    </dependency>
    <dependency>
        <groupId>org.fractalx</groupId>
        <artifactId>fractalx-runtime</artifactId>
        <version>${fractalx.version}</version>
    </dependency>
    <!-- your normal Spring Boot deps -->
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.fractalx</groupId>
            <artifactId>fractalx-maven-plugin</artifactId>
            <version>${fractalx.version}</version>
        </plugin>
    </plugins>
</build>
```

### `src/main/resources/fractalx-config.yml`

```yaml
fractalx:
  otel:
    endpoint: http://localhost:4317

  services:
    order-service:
      port: 8081
    payment-service:
      port: 8082
    inventory-service:
      port: 8083
      tracing:
        enabled: false    # inventory does not need tracing
```

### `MonolithApplication.java`

```java
@SpringBootApplication
@AdminEnabled(port = 9090, username = "admin", password = "admin123")
public class MonolithApplication {
    public static void main(String[] args) {
        SpringApplication.run(MonolithApplication.class, args);
    }
}
```

### `order/OrderModule.java`

```java
package com.example.order;

import org.fractalx.annotations.DecomposableModule;
import org.fractalx.annotations.DistributedSaga;

@DecomposableModule(
    serviceName  = "order-service",
    port         = 8081,
    ownedSchemas = {"orders"}
)
@Service
public class OrderModule {

    private final PaymentService   paymentService;
    private final InventoryService inventoryService;
    private final OrderRepository  orderRepository;

    public OrderModule(PaymentService paymentService,
                       InventoryService inventoryService,
                       OrderRepository orderRepository) {
        this.paymentService   = paymentService;
        this.inventoryService = inventoryService;
        this.orderRepository  = orderRepository;
    }

    @DistributedSaga(
        sagaId             = "place-order-saga",
        compensationMethod = "cancelPlaceOrder",
        timeout            = 30000,
        description        = "Coordinates payment and inventory to place an order."
    )
    @Transactional
    public Order placeOrder(Long customerId, BigDecimal totalAmount) {
        // Local work: save a PENDING order
        Order order = new Order(customerId, totalAmount, "PENDING");
        order = orderRepository.save(order);
        Long orderId = order.getId();    // extra local var — included in saga payload

        // Cross-module calls — REPLACED by outboxPublisher.publish() in generated code:
        paymentService.processPayment(customerId, totalAmount, orderId);
        inventoryService.reserveStock(orderId);

        // These lines are removed by SagaMethodTransformer (orphaned after publish):
        order.setStatus("CONFIRMED");
        return orderRepository.save(order);
    }

    @Transactional
    public void cancelPlaceOrder(Long customerId, BigDecimal totalAmount) {
        orderRepository.findTopByCustomerIdAndStatusOrderByCreatedAtDesc(customerId, "PENDING")
            .ifPresent(o -> {
                o.setStatus("CANCELLED");
                orderRepository.save(o);
            });
    }
}
```

> **What FractalX does to this method**: `SagaMethodTransformer` removes the two cross-service calls and inserts `outboxPublisher.publish("place-order-saga", String.valueOf(customerId), sagaPayload)` in their place. The payload map contains `customerId`, `totalAmount`, and `orderId` (the extra local var). The `return` statement is rewritten to use the last declared `Order` variable.

### `payment/PaymentModule.java`

```java
package com.example.payment;

import org.fractalx.annotations.DecomposableModule;

@DecomposableModule(
    serviceName  = "payment-service",
    port         = 8082,
    ownedSchemas = {"payments"}
)
@Service
public class PaymentModule {

    private final PaymentRepository paymentRepository;

    public PaymentModule(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    // Step 1 in place-order-saga
    @Transactional
    public Payment processPayment(Long customerId, BigDecimal amount, Long orderId) {
        Payment payment = new Payment(customerId, amount, orderId, "PROCESSED");
        return paymentRepository.save(payment);
    }

    // Compensation for step 1 — prefix "cancel" + "ProcessPayment"
    @Transactional
    public void cancelProcessPayment(Long customerId, BigDecimal amount, Long orderId) {
        paymentRepository.findByOrderId(orderId).ifPresent(p -> {
            p.setStatus("REFUNDED");
            paymentRepository.save(p);
        });
    }
}
```

### `inventory/InventoryModule.java`

```java
package com.example.inventory;

import org.fractalx.annotations.DecomposableModule;

@DecomposableModule(
    serviceName  = "inventory-service",
    port         = 8083,
    ownedSchemas = {"inventory"}
)
@Service
public class InventoryModule {

    private final InventoryRepository inventoryRepository;

    public InventoryModule(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    // Step 2 in place-order-saga
    @Transactional
    public void reserveStock(Long orderId) {
        // reserve logic
    }

    // Compensation for step 2 — prefix "cancel" + "ReserveStock"
    @Transactional
    public void cancelReserveStock(Long orderId) {
        // release reservation
    }
}
```

### What `mvn fractalx:decompose` produces

```
microservices/
  order-service/        port 8081 + gRPC 18081
  payment-service/      port 8082 + gRPC 18082
  inventory-service/    port 8083 + gRPC 18083
  fractalx-gateway/     port 9999
  admin-service/        port 9090
  logger-service/       port 9099
  fractalx-registry/    port 8761
  fractalx-saga-orchestrator/  port 8099
  docker-compose.yml
  start-all.sh
  stop-all.sh
```

Each generated service has:
- Full Spring Boot application with `pom.xml`, `Dockerfile`, `application.yml`
- NetScope gRPC client for each dependency (`PaymentServiceClient`, `InventoryServiceClient`)
- `OutboxPublisher` + `OutboxPoller` (in `order-service`)
- `SagaCompletionController` (in `order-service`)
- Flyway `V1__initial_schema.sql`
- `logback-spring.xml` with `%X{correlationId}`
- `OtelConfig.java`, `CorrelationTracingConfig.java` (except `inventory-service` — tracing disabled)

---

## 29. License

Licensed under the [Apache License 2.0](LICENSE).
