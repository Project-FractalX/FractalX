# FractalX Developer Guide

> Complete reference for building a decomposable modular monolith with FractalX.
> Every section documents the **exact conventions** the framework enforces. Deviating from them produces incorrect generated output.

---

## Table of Contents

1. [What Is FractalX](#1-what-is-fractalx)
2. [Project Layout — What FractalX Generates](#2-project-layout--what-fractalx-generates)
3. [Module Structure — Your Monolith Project](#3-module-structure--your-monolith-project)
4. [Annotations Reference](#4-annotations-reference)
   - [@DecomposableModule](#41-decomposablemodule)
   - [@DistributedSaga](#42-distributedsaga)
   - [@ServiceBoundary](#43-serviceboundary)
   - [@AdminEnabled](#44-adminenabled)
5. [How Cross-Module Dependencies Work](#5-how-cross-module-dependencies-work)
6. [fractalx-config.yml — Full Reference](#6-fractalx-configyml--full-reference)
7. [How Code Generation Works — The Full Pipeline](#7-how-code-generation-works--the-full-pipeline)
8. [Generated application.yml — Per-Service Config](#8-generated-applicationyml--per-service-config)
9. [Logging — How It Works End to End](#9-logging--how-it-works-end-to-end)
10. [Correlation IDs — Tracing Requests Across Services](#10-correlation-ids--tracing-requests-across-services)
11. [Distributed Tracing (OpenTelemetry + Jaeger)](#11-distributed-tracing-opentelemetry--jaeger)
    - [Enable / Disable Per Service](#111-enable--disable-per-service)
12. [API Gateway](#12-api-gateway)
    - [Routing Rules](#121-routing-rules)
    - [CORS](#122-cors)
    - [Security](#123-security)
    - [Rate Limiting](#124-rate-limiting)
    - [Circuit Breaker at Gateway Level](#125-circuit-breaker-at-gateway-level)
    - [Observability on the Gateway](#126-observability-on-the-gateway)
13. [Service Registry (fractalx-registry)](#13-service-registry-fractalx-registry)
14. [NetScope — Inter-Service gRPC Communication](#14-netscope--inter-service-grpc-communication)
    - [How NetScope Clients Are Generated](#141-how-netscope-clients-are-generated)
    - [gRPC Port Convention](#142-grpc-port-convention)
    - [Correlation ID Propagation Over gRPC](#143-correlation-id-propagation-over-grpc)
    - [Resilience — Circuit Breaker, Retry, Time Limiter](#144-resilience--circuit-breaker-retry-time-limiter)
15. [Distributed Sagas](#15-distributed-sagas)
    - [How Sagas Are Detected](#151-how-sagas-are-detected)
    - [Writing a Saga Method](#152-writing-a-saga-method)
    - [Compensation Methods — Naming Convention](#153-compensation-methods--naming-convention)
    - [What the Generator Does to the Saga Method](#154-what-the-generator-does-to-the-saga-method)
    - [The Saga Orchestrator Service](#155-the-saga-orchestrator-service)
    - [Saga State Machine](#156-saga-state-machine)
    - [Saga Completion Callbacks](#157-saga-completion-callbacks)
    - [The Transactional Outbox Pattern](#158-the-transactional-outbox-pattern)
16. [Data Consistency and Isolation](#16-data-consistency-and-isolation)
    - [Per-Service Database](#161-per-service-database)
    - [Flyway Migrations](#162-flyway-migrations)
    - [Reference Validation (Decoupled Foreign Keys)](#163-reference-validation-decoupled-foreign-keys)
    - [Data README](#164-data-readme)
17. [Admin Service and Admin UI](#17-admin-service-and-admin-ui)
18. [Logger Service](#18-logger-service)
19. [Maven Plugin Goals](#19-maven-plugin-goals)
20. [Docker Compose and Start Scripts](#20-docker-compose-and-start-scripts)
21. [Verification — fractalx:verify](#21-verification--fractalxverify)
22. [Full Monolith Example — Annotated Code](#22-full-monolith-example--annotated-code)
23. [Troubleshooting / Common Mistakes](#23-troubleshooting--common-mistakes)

---

## 1. What Is FractalX

FractalX is a **code generation Maven plugin** that reads your modular monolith source code and produces a complete set of independently-runnable Spring Boot microservices, an API gateway, a saga orchestrator, an admin UI, a logger service, and a service registry — all wired together with distributed tracing, structured logging, and resilience patterns.

**Generation happens at build time**, not at runtime. You annotate your monolith classes, run `mvn fractalx:decompose`, and receive a fully-functional distributed system in an `microservices/` directory.

**Key principle**: your monolith continues to compile and run as a normal Spring Boot application. FractalX reads it statically. You never have to give up the monolith.

### Module Hierarchy

```
fractalx-annotations     — @DecomposableModule, @DistributedSaga, @ServiceBoundary, @AdminEnabled
fractalx-runtime         — TraceFilter, FractalLogAppender, NetScopeContextInterceptor (runtime JAR, bundled in every generated service)
fractalx-core            — the generator engine (not a runtime dep — only used by the plugin)
fractalx-maven-plugin    — exposes mvn fractalx:decompose / verify / start / stop / ps / services
```

Your monolith depends on **`fractalx-annotations`** (to write annotations) and **`fractalx-runtime`** (for the TraceFilter and FractalLogAppender that run in the monolith itself during development).

---

## 2. Project Layout — What FractalX Generates

After `mvn fractalx:decompose` the `microservices/` directory contains:

```
microservices/
  fractalx-registry/          — Custom service registry (port 8761)
  fractalx-gateway/           — Spring Cloud Gateway (port 9999)
  admin-service/              — Admin UI + REST API (port 9090)
  logger-service/             — Centralized log collector (port 9099)
  fractalx-saga-orchestrator/ — Saga engine, only when @DistributedSaga is found (port 8099)
  order-service/              — Your @DecomposableModule, one dir per module
  payment-service/
  inventory-service/
  ...
  docker-compose.yml
  start-all.sh
  stop-all.sh
  README.md
```

Each generated service is a standalone Spring Boot 3 / Spring MVC (servlet-stack) project with its own `pom.xml`, `src/`, `Dockerfile`, `application.yml`, `application-dev.yml`, `application-docker.yml`, and Flyway migrations.

---

## 3. Module Structure — Your Monolith Project

Your monolith is a standard Spring Boot application. FractalX adds nothing special at runtime beyond what is in `fractalx-runtime`. The conventions that FractalX reads are:

1. **One Java class per module** — annotated with `@DecomposableModule`. This is the "boundary class" and becomes the root of the generated service.
2. **Package-per-module** — each module lives in its own package (e.g., `com.example.order`, `com.example.payment`). FractalX copies every `.java` file under that package into the generated service.
3. **Cross-module calls via injected Service/Client beans** — if `OrderModule` calls `PaymentService`, the field type must end in `Service` or `Client`. This is what FractalX uses to detect inter-service dependencies.
4. **Saga methods** — annotated with `@DistributedSaga` directly on the module's service class method.

### Recommended Monolith Package Layout

```
com.example
  order/
    OrderModule.java          <-- @DecomposableModule
    OrderService.java
    OrderController.java
    OrderRepository.java
    Order.java
  payment/
    PaymentModule.java        <-- @DecomposableModule
    PaymentService.java
    PaymentController.java
    PaymentRepository.java
    Payment.java
  inventory/
    InventoryModule.java      <-- @DecomposableModule
    InventoryService.java
    InventoryController.java
    InventoryRepository.java
    InventoryItem.java
```

FractalX walks every `.java` file under `src/main/java`. It looks for classes annotated `@DecomposableModule`. For each found class it reads:
- `serviceName` — the name of the generated microservice
- `port` — the HTTP port (can also be set in `fractalx-config.yml`, which takes priority)
- `independentDeployment` — whether to generate a full standalone service (default `true`)
- `ownedSchemas` — informational; used in DATA_README

The **class itself** becomes the anchor for:
- Dependency detection (what other services does this module call?)
- Saga detection (what `@DistributedSaga` methods does this module own?)

---

## 4. Annotations Reference

### 4.1 `@DecomposableModule`

Place on a class that represents the boundary of a single future microservice.

```java
@DecomposableModule(
    serviceName = "order-service",     // REQUIRED. Kebab-case. Becomes the service directory name.
    port        = 8081,                // Optional. 0 = auto-assign. Can be overridden in fractalx-config.yml.
    independentDeployment = true,      // Optional. Default true. Set false to skip generation.
    ownedSchemas = {"orders", "order_items"}  // Optional. Informational — appears in DATA_README.
)
@Service  // Keep your normal Spring annotations — they are preserved in the generated service
public class OrderModule {

    // Inject cross-module dependencies here.
    // The TYPE must end in "Service" or "Client" for FractalX to detect the dependency.
    private final PaymentService paymentService;
    private final InventoryService inventoryService;

    public OrderModule(PaymentService paymentService, InventoryService inventoryService) {
        this.paymentService = paymentService;
        this.inventoryService = inventoryService;
    }

    @DistributedSaga(sagaId = "place-order-saga", ...)
    public Order placeOrder(...) { ... }
}
```

**Rules:**
- `serviceName` is mandatory and must be unique across all modules.
- Use kebab-case: `order-service`, `payment-service`, `inventory-service`.
- The port in the annotation is a fallback. `fractalx-config.yml` → annotation port → auto-assign (starting from 8081).
- Annotate exactly **one class per module**. If you annotate multiple classes in the same package with different `serviceName` values you will get duplicate files copied into two services.

### 4.2 `@DistributedSaga`

Place on a **method** inside a `@DecomposableModule`-annotated class. Marks the method as the entry point of a distributed saga.

```java
@DistributedSaga(
    sagaId            = "place-order-saga",   // REQUIRED. Unique. Kebab-case. Becomes the orchestrator endpoint path.
    compensationMethod = "cancelOrder",        // Optional. Name of the overall rollback method in THIS class.
    timeout           = 30000,                 // Optional. Milliseconds. Default 30000 (30 s).
    steps             = {"create-order",       // Optional. Hint for documentation / admin UI. Not used by the engine.
                         "process-payment",
                         "reserve-inventory"},
    description       = "Places a customer order by coordinating payment and inventory reservation."
)
public Order placeOrder(Long customerId, BigDecimal totalAmount, List<OrderItem> items) {
    // ... see Section 15 for what to write here
}
```

**Rules:**
- `sagaId` must be globally unique across all modules.
- `compensationMethod` is the method called on the **owner service** (the one containing this saga method) when the entire saga fails. It must exist in the same class and accept the same parameters.
- The step detection is automatic — you do NOT need to fill in `steps` for the engine to work. It is only used for documentation.
- The method body must call cross-module dependencies directly (see Section 15.2). The generator replaces those calls with outbox publish logic automatically.

### 4.3 `@ServiceBoundary`

Place on a **class or package** to declare which other modules are allowed to call it. Used by the static verifier (`fractalx:verify`) to enforce boundary rules.

```java
@ServiceBoundary(
    allowedCallers = {"order-service", "saga-orchestrator"},  // service names that may call this
    strict = true   // if true, the verifier fails when an unlisted caller imports from this package
)
public class PaymentService { ... }
```

This does not affect code generation — it only affects `mvn fractalx:verify` output.

### 4.4 `@AdminEnabled`

Place on your monolith's main `@SpringBootApplication` class to configure the generated admin service.

```java
@SpringBootApplication
@AdminEnabled(
    port       = 9090,          // Port for admin-service. Default 9090.
    username   = "admin",       // Default "admin". Change in production via env var.
    password   = "admin123",    // Default "admin123". Change in production via env var.
    monitoring = true,          // Enable health/metrics monitoring panel.
    logging    = true           // Enable log aggregation panel.
)
public class MonolithApplication { ... }
```

The admin port can also be set in `fractalx-config.yml` under `fractalx.admin.port`, which takes priority.

---

## 5. How Cross-Module Dependencies Work

FractalX detects cross-module dependencies by inspecting **field declarations** and **constructor parameters** in each `@DecomposableModule`-annotated class. Any field or constructor parameter whose **type ends in `Service` or `Client`** is treated as a potential cross-module dependency.

```java
@DecomposableModule(serviceName = "order-service", port = 8081)
@Service
public class OrderModule {

    private final PaymentService  paymentService;   // <-- detected: cross-module dep on payment-service
    private final InventoryService inventoryService; // <-- detected: cross-module dep on inventory-service
    private final OrderRepository  orderRepository;  // <-- NOT detected (ends in Repository)

    public OrderModule(PaymentService paymentService,
                       InventoryService inventoryService,
                       OrderRepository orderRepository) {
        this.paymentService   = paymentService;
        this.inventoryService = inventoryService;
        this.orderRepository  = orderRepository;
    }
}
```

**Naming convention for type → service name mapping:**

The generator converts `PaymentService` → `payment-service`, `InventoryService` → `inventory-service`, etc., using the rule:

1. Strip the `Service` or `Client` suffix.
2. Convert PascalCase to kebab-case.
3. Append `-service`.

So `UserAccountService` → `user-account-service`. This must match the `serviceName` of the target module.

**In generated code**, these fields become NetScope gRPC client beans:

```java
// Original in monolith:
private final PaymentService paymentService;

// Generated in order-service:
private final PaymentServiceClient paymentServiceClient;  // NetScope gRPC proxy
```

You do not write or modify the generated client code. FractalX generates it from the interface signature of `PaymentService`.

---

## 6. `fractalx-config.yml` — Full Reference

Place this file at `src/main/resources/fractalx-config.yml` in your monolith project. All values are optional — omitting a value falls back to the default shown below.

```yaml
fractalx:

  # ── Global infrastructure URLs ───────────────────────────────────────────
  registry:
    url: http://localhost:8761           # FractalX service registry URL
                                        # Default: http://localhost:8761

  logger:
    url: http://localhost:9099          # Logger service base URL
                                        # Default: http://localhost:9099
                                        # The appender will POST to <url>/api/logs

  otel:
    endpoint: http://localhost:4317     # OTel collector / Jaeger OTLP gRPC endpoint
                                        # Default: http://localhost:4317
                                        # Set to your Jaeger or OTel collector host

  # ── API Gateway ──────────────────────────────────────────────────────────
  gateway:
    port: 9999                          # Gateway HTTP port. Default: 9999.

    cors:
      allowed-origins: "http://localhost:3000,http://localhost:4200"
      # Default: http://localhost:3000,http://localhost:4200
      # Comma-separated. Used in gateway application.yml CORS config.

    security:
      oauth2:
        jwk-set-uri: http://localhost:8080/realms/fractalx/protocol/openid-connect/certs
        # Default: http://localhost:8080/...
        # Only relevant when GATEWAY_OAUTH2_ENABLED=true at runtime

  # ── Admin service ────────────────────────────────────────────────────────
  admin:
    port: 9090                          # Admin UI port. Default: 9090.

  # ── Per-service overrides ────────────────────────────────────────────────
  services:

    order-service:
      port: 8081                        # Override port for this service.
                                        # Annotation port is used if not specified here.

      datasource:                       # Optional. Provide if you want a real DB instead of H2.
        url: jdbc:mysql://localhost:3306/orders_db
        username: root
        password: secret
        driver-class-name: com.mysql.cj.jdbc.Driver   # optional, auto-detected from URL

    payment-service:
      port: 8082
      tracing:
        enabled: false                  # Disable OTel for this service only.
                                        # Default: true (omitting = enabled).
                                        # When false:
                                        #   - OtelConfig.java, CorrelationTracingConfig.java,
                                        #     TracingExclusionConfig.java are NOT generated.
                                        #   - management.tracing.sampling.probability: 0.0
                                        #   - fractalx.observability.otel.enabled: false

    inventory-service:
      # port not set — auto-assigned after all explicitly-ported services
      tracing:
        enabled: true                   # Explicitly enabled (same as omitting tracing block)
```

### Reading Priority

For each config value, FractalX reads in this order:

1. `fractalx-config.yml` (primary, FractalX-specific)
2. `application.yml` (fallback, same key paths under `fractalx.*`)
3. `application.properties` (fallback)
4. Hardcoded defaults in `FractalxConfig.defaults()`

---

## 7. How Code Generation Works — The Full Pipeline

Running `mvn fractalx:decompose` triggers:

```
DecomposeMojo.execute()
    │
    ├── ModuleAnalyzer.analyzeProject(src/main/java)
    │       Walks all .java files, parses them with JavaParser,
    │       finds @DecomposableModule classes, builds List<FractalModule>
    │
    ├── ServiceGenerator.generateServices(modules)
    │       │
    │       ├── FractalxConfigReader.read(src/main/resources)
    │       │       Reads fractalx-config.yml → FractalxConfig
    │       │
    │       ├── RepositoryAnalyzer.analyze()
    │       │       Warns about @Repository classes accessed across module boundaries
    │       │
    │       ├── SagaAnalyzer.analyzeSagas()
    │       │       Finds @DistributedSaga methods, detects steps, compensation methods
    │       │       Returns List<SagaDefinition>
    │       │
    │       ├── RegistryServiceGenerator.generate()
    │       │       Writes microservices/fractalx-registry/
    │       │
    │       ├── FOR EACH MODULE — run pipeline:
    │       │   ├── PomGenerator              — generates pom.xml with all deps
    │       │   ├── ApplicationGenerator      — generates Application.java (@SpringBootApplication)
    │       │   ├── ConfigurationGenerator    — generates application.yml / dev / docker profiles
    │       │   ├── ObservabilityInjector     — patches logback-spring.xml into resources
    │       │   ├── CodeCopier                — copies .java files from monolith package to service
    │       │   ├── CodeTransformer           — removes @DecomposableModule, decouples JPA relations,
    │       │   │                               fixes imports
    │       │   ├── FileCleanupStep           — removes stale generated files (e.g., PaymentClientImpl)
    │       │   ├── NetScopeServerAnnotationStep — adds @NetScopeService/@NetworkPublic to service classes
    │       │   ├── NetScopeClientGenerator   — generates *Client.java interface for each cross-module dep
    │       │   ├── NetScopeClientWiringStep  — rewires cross-module fields to *Client in constructors
    │       │   ├── SagaMethodTransformer     — replaces cross-service calls with outboxPublisher.publish()
    │       │   ├── DistributedServiceHelper  — data isolation, DB config, Flyway, outbox, ref validators
    │       │   ├── CorrelationIdGenerator    — generates logback-spring.xml with %X{correlationId}
    │       │   ├── OtelConfigStep            — generates OtelConfig.java (if tracing enabled)
    │       │   ├── HealthMetricsStep         — generates custom health + metrics endpoints
    │       │   ├── ServiceRegistrationStep   — generates ServiceRegistrar bean (registers with registry)
    │       │   ├── NetScopeRegistryBridgeStep— bridges NetScope with FractalX registry
    │       │   └── ResilienceConfigStep      — generates Resilience4j config + YAML
    │       │
    │       ├── LoggerServiceGenerator.generate()
    │       │       Writes microservices/logger-service/
    │       │
    │       ├── GatewayGenerator.generateGateway()  (only when >1 module)
    │       │       Writes microservices/fractalx-gateway/
    │       │
    │       ├── AdminServiceGenerator.generateAdminService()
    │       │       Writes microservices/admin-service/
    │       │
    │       ├── SagaOrchestratorGenerator.generateOrchestratorService()  (only when sagas found)
    │       │       Writes microservices/fractalx-saga-orchestrator/
    │       │
    │       └── DockerComposeGenerator.generate()
    │               Writes docker-compose.yml, start-all.sh, stop-all.sh, README.md
```

The **pipeline** is an ordered list of `ServiceFileGenerator` steps. Each step receives a `GenerationContext` that provides:
- `module` — the `FractalModule` being generated
- `sourceRoot` — the monolith `src/` directory
- `serviceRoot` — the generated service output directory
- `srcMainJava` / `srcMainResources` — convenience paths
- `allModules` — all modules (for cross-service wiring)
- `fractalxConfig` — the resolved `FractalxConfig`
- `sagaDefinitions` — all detected sagas

---

## 8. Generated `application.yml` — Per-Service Config

For each service, three YAML files are generated:

### `application.yml` (base profile, always active)

```yaml
spring:
  application:
    name: order-service
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}   # defaults to dev profile

server:
  port: 8081

fractalx:
  enabled: true
  registry:
    url: ${FRACTALX_REGISTRY_URL:http://localhost:8761}
    enabled: true
    host: ${FRACTALX_REGISTRY_HOST:localhost}
  saga:
    orchestrator:
      url: ${FRACTALX_SAGA_ORCHESTRATOR_URL:http://localhost:8099}
  observability:
    tracing: true              # false when tracing disabled for this service
    metrics: true
    logger-url: ${FRACTALX_LOGGER_URL:http://localhost:9099/api/logs}
    otel:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4317}
      # — OR when tracing is disabled:
      # otel:
      #   enabled: false

netscope:
  server:
    grpc:
      port: 18081              # HTTP port + 10000 = gRPC port
    security:
      enabled: false

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
      probability: 1.0         # 0.0 when tracing disabled

logging:
  level:
    org.fractalx: DEBUG
    org.fractalx.netscope: DEBUG
```

### `application-dev.yml` (local development)

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:order_service
    driver-class-name: org.h2.Driver
    username: sa
    password:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 5
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
  h2:
    console:
      enabled: true
  flyway:
    enabled: true
    locations: classpath:db/migration

netscope:
  client:
    servers:
      payment-service:
        host: localhost
        port: 18082         # payment-service gRPC port
      inventory-service:
        host: localhost
        port: 18083
```

### `application-docker.yml` (Docker / container mode)

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:h2:mem:order_service}
    username: ${DB_USERNAME:sa}
    password: ${DB_PASSWORD:}
  flyway:
    enabled: true
    locations: classpath:db/migration
fractalx:
  observability:
    otel:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://jaeger:4317}
    logger-url: ${FRACTALX_LOGGER_URL:http://logger-service:9099/api/logs}
netscope:
  client:
    servers:
      payment-service:
        host: ${PAYMENT_SERVICE_HOST:payment-service}
        port: ${PAYMENT_SERVICE_GRPC_PORT:18082}
```

### Environment Variables Understood by Each Service

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` | Switch to `docker` in containers |
| `FRACTALX_REGISTRY_URL` | `http://localhost:8761` | Registry endpoint |
| `FRACTALX_LOGGER_URL` | `http://localhost:9099/api/logs` | Log shipper target |
| `FRACTALX_SAGA_ORCHESTRATOR_URL` | `http://localhost:8099` | Outbox poller target |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | Jaeger / OTel collector |
| `DB_URL` | H2 in-memory | JDBC URL for real DB in docker mode |
| `DB_USERNAME` | `sa` | DB username |
| `DB_PASSWORD` | (empty) | DB password |
| `<SERVICE>_HOST` | service name | gRPC host for named service (docker mode) |
| `<SERVICE>_GRPC_PORT` | port+10000 | gRPC port for named service (docker mode) |

---

## 9. Logging — How It Works End to End

### Architecture

```
Your Service Code
    │  logs via SLF4J / Logback
    ▼
logback-spring.xml (generated per service)
    │  pattern: %d %X{correlationId} [%thread] %-5level %logger{36} — %msg%n
    │
    ├── Console appender (always on)
    │
    └── FractalLogAppender (fractalx-runtime)
            │  POSTs JSON to logger-service on every log event
            ▼
        logger-service (port 9099) — in-memory ring buffer (5000 entries)
            │
            └── Admin UI (port 9090) — polls /api/logs, shows newest-first
```

### FractalLogAppender

`FractalLogAppender` is a Logback `AppenderBase<ILoggingEvent>` bundled in `fractalx-runtime`. It fires on every log event and ships it via HTTP POST to the logger service URL configured at `fractalx.observability.logger-url`.

**Payload structure sent per log event:**

```json
{
  "service":       "order-service",
  "level":         "INFO",
  "message":       "Order 42 created for customer 7",
  "timestamp":     "2026-03-06T10:15:30Z",
  "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "spanId":        "abc123",
  "parentSpanId":  "def456"
}
```

- `correlationId` is read from `MDC.get("correlationId")`. Falls back to `MDC.get("traceId")` (Micrometer tracing).
- `spanId` and `parentSpanId` are read from MDC keys set by the Micrometer tracing bridge.
- Appender is **fire-and-forget** (async, exceptions swallowed) — a failing logger service never disrupts service operation.
- Registered automatically via `FractalXRuntimeAutoConfiguration` when `fractalx.observability.metrics: true` (default).

### Logger Service

`logger-service` (port 9099) is a standalone Spring Boot service with an in-memory ring buffer holding the last **5000 log entries**. It exposes:

| Endpoint | Purpose |
|---|---|
| `POST /api/logs` | Receive a log entry (called by FractalLogAppender) |
| `GET /api/logs?page=0&size=50&service=order-service&level=ERROR&search=xyz` | Query logs with pagination + filters |
| `GET /actuator/health` | Health check |

The Admin UI's Logs tab reads from this endpoint. Server-side pagination returns 50 entries per page, newest-first.

### logback-spring.xml

Generated per service by `CorrelationIdGenerator`. Key pattern fragment:

```xml
<pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%X{correlationId}] [%thread] %-5level %logger{36} - %msg%n</pattern>
```

The `%X{correlationId}` is populated by `TraceFilter` for HTTP requests, and by `NetScopeContextInterceptor` for incoming gRPC calls.

### Activating Logging in the Monolith (during development)

The runtime auto-configuration activates automatically. Ensure your monolith's `application.yml` contains:

```yaml
fractalx:
  observability:
    metrics: true
    logger-url: http://localhost:9099/api/logs
```

---

## 10. Correlation IDs — Tracing Requests Across Services

A **correlation ID** is a UUID that identifies a single logical request chain across all services. It is distinct from an OTel trace ID (though they are linked).

### How It Flows

```
HTTP Client
    │  X-Correlation-Id: f47ac10b-...  (optional — generated if missing)
    ▼
TraceFilter (fractalx-runtime, @Order(HIGHEST_PRECEDENCE))
    │  Reads X-Correlation-Id header OR generates UUID
    │  Puts correlationId into MDC ("correlationId")
    │  Echoes header back in response
    ▼
HTTP Handler (your @RestController)
    │  MDC["correlationId"] = "f47ac10b-..."
    │  FractalLogAppender reads it for every log event
    ▼
NetScopeContextInterceptor (client side, gRPC call)
    │  Reads MDC["correlationId"]
    │  Injects x-correlation-id gRPC metadata header into outgoing call
    ▼
NetScopeContextInterceptor (server side, on receiving service)
    │  Extracts x-correlation-id from gRPC metadata
    │  Populates MDC["correlationId"] in both onMessage() and onHalfClose()
    │  Generates a new UUID if header absent
    ▼
Downstream service handler
    │  MDC["correlationId"] = same "f47ac10b-..." as originating request
    │  All logs carry the same correlation ID
```

### Key Points

- `TraceFilter` runs at `Ordered.HIGHEST_PRECEDENCE` — before Spring Security, before any `@RestController`.
- `NetScopeContextInterceptor` propagates via `x-correlation-id` gRPC metadata key (lowercase).
- The `OutboxPoller` saves the correlation ID to the `OutboxEvent` entity (captured from MDC while still in the HTTP thread) and forwards it as `X-Correlation-Id` header when calling the saga orchestrator.
- `CorrelationTracingConfig` (generated by `OtelConfigStep`) is a Spring MVC `HandlerInterceptor` that reads `MDC["correlationId"]` and tags the active Micrometer/OTel span with `correlation.id` (with a dot). This makes traces searchable in Jaeger by correlation ID.

### Using Correlation ID in Your Code

You never need to manage correlation IDs manually. They are set before your handler runs. Just log normally:

```java
log.info("Processing order {}", orderId);
// Logback pattern includes %X{correlationId} — it appears automatically
```

If you need to read it programmatically:

```java
@Autowired
TraceContextPropagator traceContextPropagator;

String cid = traceContextPropagator.getCurrentCorrelationId();
// or directly:
String cid = MDC.get("correlationId");
```

---

## 11. Distributed Tracing (OpenTelemetry + Jaeger)

Every generated microservice gets full OTel tracing via:

1. **`micrometer-tracing-bridge-otel`** — bridges Spring Boot's Micrometer Observation API to OTel.
2. **`opentelemetry-exporter-otlp`** — exports spans via OTLP/gRPC to Jaeger (default) or any OTel collector.
3. **`OtelConfig.java`** (generated per service) — a `@ConditionalOnMissingBean(OpenTelemetry.class)` fallback that creates the SDK with W3C traceparent + baggage propagation.
4. **`CorrelationTracingConfig.java`** (generated per service) — a `HandlerInterceptor` that tags every span with `correlation.id` using the MDC value.
5. **`TracingExclusionConfig.java`** (generated per service) — suppresses actuator and scheduled-task spans from Jaeger.

### Span Tag: `correlation.id`

The canonical tag key is **`correlation.id`** (with a dot). This is what the Admin UI uses when searching Jaeger traces by correlation ID:

```
/api/traces?tags=correlation.id%3D<value>
```

### W3C Traceparent Propagation

The gateway always injects `traceparent` headers on forwarded requests. `OtelConfig` registers W3C propagators:
- `W3CTraceContextPropagator` (`traceparent` header)
- `W3CBaggagePropagator` (`baggage` header)

The `OutboxPoller` also forwards the `traceparent` value stored in the `OutboxEvent` to the saga orchestrator, connecting the saga's span to the original HTTP request trace in Jaeger.

### Jaeger Setup

Run Jaeger locally with OTLP enabled:

```bash
docker run -d --name jaeger \
  -p 4317:4317 \      # OTLP gRPC
  -p 4318:4318 \      # OTLP HTTP
  -p 16686:16686 \    # Jaeger UI
  jaegertracing/all-in-one:latest
```

Open `http://localhost:16686` to view traces.

### 11.1 Enable / Disable Per Service

Tracing is **enabled by default** for all decomposed microservices. To disable it for a specific service, set in `fractalx-config.yml`:

```yaml
fractalx:
  services:
    payment-service:
      tracing:
        enabled: false
```

**What disabling does:**
- `OtelConfig.java` is **not generated** for that service.
- `CorrelationTracingConfig.java` is **not generated**.
- `TracingExclusionConfig.java` is **not generated**.
- `application.yml` gets `management.tracing.sampling.probability: 0.0` (Spring Boot sends nothing to Jaeger).
- `application.yml` gets `fractalx.observability.otel.enabled: false` instead of the endpoint block.

**What disabling does NOT affect:**
- Correlation ID propagation still works via `TraceFilter` and `NetScopeContextInterceptor`.
- Log shipping to `logger-service` still works.
- The **API Gateway** is always traced (hardcoded in `GatewayPomGenerator` + `GatewayConfigGenerator`). There is no per-flag override for the gateway.
- The **Saga Orchestrator** is always traced (hardcoded in `SagaOrchestratorGenerator`).

---

## 12. API Gateway

The gateway is a **Spring Cloud Gateway** (WebFlux / reactive) service running on port 9999. It is generated when there are **2 or more** decomposed modules. With a single module, no gateway is generated.

### 12.1 Routing Rules

Each module gets a route. The path pattern is derived from the service name:

| Service Name | Route Predicate | Upstream |
|---|---|---|
| `order-service` | `/api/order/**` and `/api/orders/**` | `http://localhost:8081` |
| `payment-service` | `/api/payment/**` and `/api/payments/**` | `http://localhost:8082` |
| `inventory-service` | `/api/inventory/**` and `/api/inventorys/**` | `http://localhost:8083` |

Rule: `<serviceName>` → strip `-service` suffix → use as base path. Both singular and simple-plural forms are matched (e.g., `/api/order/**` and `/api/orders/**`).

`StripPrefix=0` means the full path is forwarded to the upstream without stripping any prefix segments. So `GET /api/orders/42` on the gateway is forwarded as `GET /api/orders/42` to `order-service`.

Your controllers in the generated services must use the matching path prefix:

```java
@RestController
@RequestMapping("/api/orders")
public class OrderController { ... }
```

### 12.2 CORS

CORS is configured globally on the gateway. The allowed origins come from `fractalx-config.yml`:

```yaml
fractalx:
  gateway:
    cors:
      allowed-origins: "http://localhost:3000,http://localhost:4200"
```

Methods allowed: `GET, POST, PUT, DELETE, PATCH, OPTIONS`. Credentials: `true`. Override at runtime with:

```
CORS_ORIGINS=http://myfrontend.com
```

### 12.3 Security

Security is **disabled by default**. All four auth mechanisms are pre-wired in the generated `application.yml` and activated via environment variables at runtime — no code changes required.

| Mechanism | Env var to enable | Additional env vars |
|---|---|---|
| JWT Bearer | `GATEWAY_BEARER_ENABLED=true` | `JWT_SECRET` (min 32 chars) |
| OAuth2 / OIDC | `GATEWAY_OAUTH2_ENABLED=true` | `OAUTH2_JWK_URI` |
| Basic Auth | `GATEWAY_BASIC_ENABLED=true` | `GATEWAY_BASIC_USER`, `GATEWAY_BASIC_PASS` |
| API Key | `GATEWAY_APIKEY_ENABLED=true` | `GATEWAY_API_KEY_1` |

Public paths (no auth required): `/api/*/public/**`, `/api/*/auth/**`.

`GATEWAY_SECURITY_ENABLED=true` must also be set to globally enable the security filter — it is the master switch.

### 12.4 Rate Limiting

A global `RateLimitFilter` (generated `GlobalFilter` bean) applies request rate limiting. Default: 100 requests/second. Override:

```
GATEWAY_DEFAULT_RPS=200
```

Rate limiting is implemented as a token-bucket in memory — **no Redis required**. This is intentional for simplicity. For production, you would replace the `GlobalFilter` implementation.

### 12.5 Circuit Breaker at Gateway Level

Each service route has a `CircuitBreaker` filter:

```yaml
- name: CircuitBreaker
  args:
    name: order-service
    fallbackUri: forward:/fallback/order-service
```

The fallback endpoint returns a structured error response. The circuit breaker is Resilience4j-backed, configured via `resilience4j.circuitbreaker.instances.<service-name>` in the gateway's `application.yml`.

A default `Retry` global filter retries on `502 BAD_GATEWAY`, `503 SERVICE_UNAVAILABLE`, `504 GATEWAY_TIMEOUT` — up to 2 retries.

### 12.6 Observability on the Gateway

The gateway always has full tracing enabled (hardcoded):

- `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` in its `pom.xml`.
- `management.tracing.sampling.probability: 1.0` in its `application.yml`.
- `TracingFilter` — injects `traceparent` header into forwarded requests so downstream services continue the trace.
- `RequestLoggingFilter` — logs request/response with correlation ID and timing.
- `GatewayMetricsFilter` — records per-route latency metrics.

There is currently no flag to disable gateway tracing. Modifying `GatewayPomGenerator` or `GatewayConfigGenerator` is required if needed.

---

## 13. Service Registry (fractalx-registry)

`fractalx-registry` is a custom-built Spring Boot service on port **8761**. It is NOT Eureka or Consul — it is a lightweight FractalX-specific registry.

Each generated service includes a `ServiceRegistrar` bean (generated by `ServiceRegistrationStep`) that registers itself with the registry on startup:

```
POST http://localhost:8761/api/registry/register
{
  "name": "order-service",
  "host": "localhost",
  "port": 8081,
  "grpcPort": 18081,
  "healthUrl": "http://localhost:8081/actuator/health"
}
```

The registry exposes:

| Endpoint | Purpose |
|---|---|
| `POST /api/registry/register` | Register a service instance |
| `DELETE /api/registry/deregister/{name}` | Deregister a service |
| `GET /api/registry/services` | List all registered services |
| `GET /api/registry/services/{name}` | Look up a specific service |
| `GET /api/registry/health` | Registry health |

The gateway and admin service query the registry to build dynamic health dashboards. The NetScope gRPC clients use static configuration from `application.yml` (not registry-based discovery) for service-to-service calls at runtime.

---

## 14. NetScope — Inter-Service gRPC Communication

**NetScope** is the gRPC-based inter-service communication library used by all generated microservices. It is not a standard gRPC library — it is FractalX's own abstraction that uses gRPC under the hood.

When FractalX detects that `order-service` depends on `PaymentService`, it generates a NetScope client interface and wires it into `order-service`:

```java
// Generated: PaymentServiceClient.java in order-service
@NetScopeClient("payment-service")
public interface PaymentServiceClient {
    Payment processPayment(Long customerId, BigDecimal amount, Long orderId);
    void cancelProcessPayment(Long customerId, BigDecimal amount, Long orderId);
    // ... all public methods from PaymentService that FractalX detected
}
```

```java
// Generated Application.java
@SpringBootApplication
@EnableNetScopeClient(basePackages = {"org.fractalx.generated.orderservice.client"})
public class OrderServiceApplication { ... }
```

### 14.1 How NetScope Clients Are Generated

`NetScopeClientGenerator` scans the **monolith source** for the `PaymentService` class (matching the injected field type name), reads its public methods, and generates the client interface with matching signatures.

`NetScopeClientWiringStep` then rewrites the `OrderModule` class (in the generated output) to replace the `PaymentService` field with `PaymentServiceClient`:

```java
// Before (in generated copy of OrderModule.java after CodeCopier):
private final PaymentService paymentService;

// After NetScopeClientWiringStep:
private final PaymentServiceClient paymentServiceClient;
```

Constructor injection is also rewritten automatically.

### 14.2 gRPC Port Convention

Every service exposes a **gRPC port = HTTP port + 10000**.

| Service | HTTP Port | gRPC Port |
|---|---|---|
| order-service | 8081 | 18081 |
| payment-service | 8082 | 18082 |
| inventory-service | 8083 | 18083 |

This is hardcoded as `GRPC_PORT_OFFSET = 10000` in `ConfigurationGenerator`. The `application-dev.yml` uses `localhost` + the gRPC port for each dependency.

### 14.3 Correlation ID Propagation Over gRPC

`NetScopeContextInterceptor` (in `fractalx-runtime`) implements both `ClientInterceptor` and `ServerInterceptor`:

- **Client side**: reads `MDC.get("correlationId")` and injects it as the `x-correlation-id` gRPC metadata header on every outgoing call.
- **Server side**: reads `x-correlation-id` from incoming gRPC metadata and sets it in MDC (`correlationId`) in the `onMessage()` and `onHalfClose()` listener callbacks — the callbacks where the actual service method runs.

This is wired automatically by `NetScopeGrpcInterceptorConfigurer` (a `BeanPostProcessor` in `fractalx-runtime`) which intercepts both the `NetScopeChannelFactory` (client) and `NetScopeGrpcServer` (server) beans.

`CorrelationAwareNetScopeGrpcServer` overrides the default NetScope gRPC server bean to ensure the interceptor is applied even when the server bean is created before the interceptor.

### 14.4 Resilience — Circuit Breaker, Retry, Time Limiter

`ResilienceConfigStep` generates Resilience4j configuration for every service that has cross-module dependencies. For each dependency, it produces:

**CircuitBreaker** (per dependency):
- `failure-rate-threshold: 50` — opens after 50% failure rate
- `wait-duration-in-open-state: 30s` — stays open 30 seconds
- `permitted-number-of-calls-in-half-open-state: 5` — tests with 5 calls when half-open
- `sliding-window-size: 10` — based on last 10 calls

**Retry** (per dependency):
- `max-attempts: 3`
- `wait-duration: 100ms`
- `exponential-backoff-multiplier: 2` — 100ms, 200ms, 400ms

**TimeLimiter** (per dependency):
- `timeout-duration: 2s` — fails a call if it takes more than 2 seconds

A `NetScopeResilienceConfig.java` `@Configuration` class is generated with `@Bean` methods for each `CircuitBreaker` instance. The YAML blocks are appended to `application.yml`.

---

## 15. Distributed Sagas

Sagas in FractalX implement the **orchestration-based saga pattern** with the **transactional outbox** for guaranteed delivery. No message broker is required.

### 15.1 How Sagas Are Detected

`SagaAnalyzer` walks all `.java` files under `sourceRoot` and finds methods annotated with `@DistributedSaga`. For each such method, it:

1. Reads `sagaId`, `compensationMethod`, `timeout`, `description` from the annotation.
2. Identifies which `@DecomposableModule` owns this class (by package prefix matching).
3. Builds a map of injected fields whose types are cross-module dependencies.
4. Scans the method body for **method calls on those cross-module fields** in source order. Each such call becomes one `SagaStep`.
5. For each step, searches all class method maps for a matching compensation method using prefixes: `cancel`, `rollback`, `undo`, `revert`, `release`, `refund`.
6. Captures the actual argument expressions from each call site.
7. Detects any local variables used as step arguments that are NOT formal parameters of the saga method (e.g., `Long orderId = draftOrder.getId()`). These become `extraLocalVars` that are included in the orchestrator payload.

### 15.2 Writing a Saga Method

The saga method must:

1. Be in the `@DecomposableModule`-annotated class.
2. Call each cross-module dependency method directly (no abstraction layers between the annotated method body and the calls on injected fields).
3. Have its arguments be passed directly to the cross-module calls (so the analyzer can map argument names to types).
4. Have a compensation method with the matching name convention if rollback is needed.

**Template:**

```java
@DecomposableModule(serviceName = "order-service", port = 8081)
@Service
public class OrderModule {

    private final PaymentService  paymentService;    // cross-module dep
    private final InventoryService inventoryService; // cross-module dep
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
        description        = "Creates an order, charges payment, reserves inventory."
    )
    @Transactional
    public Order placeOrder(Long customerId, BigDecimal totalAmount, List<OrderItem> items) {
        // Step 1 — local work (preserved in generated code)
        Order order = new Order();
        order.setCustomerId(customerId);
        order.setTotalAmount(totalAmount);
        order.setStatus("PENDING");
        order = orderRepository.save(order);

        Long orderId = order.getId();    // <-- extra local var: detected and included in payload

        // Step 2 — cross-module calls (REPLACED by outboxPublisher.publish() in generated code)
        Payment payment = paymentService.processPayment(customerId, totalAmount, orderId);
        inventoryService.reserveStock(items, orderId);

        // Step 3 — local finalization (orphan statements after publish are stripped by generator)
        order.setStatus("CONFIRMED");
        return orderRepository.save(order);
    }

    // Overall compensation — called if the saga orchestrator decides to cancel the entire saga
    @Transactional
    public void cancelPlaceOrder(Long customerId, BigDecimal totalAmount, List<OrderItem> items) {
        // Undo local work for the order-service portion
        // The orchestrator handles calling step-level compensations on payment/inventory
        orderRepository.findLatestPendingByCustomer(customerId)
            .ifPresent(order -> {
                order.setStatus("CANCELLED");
                orderRepository.save(order);
            });
    }
}
```

### 15.3 Compensation Methods — Naming Convention

Compensation methods are discovered **per step** on the target service bean. The analyzer checks the target bean's class for methods matching the following prefixes prepended to the capitalized forward method name:

| Forward Method | Compensation Candidates Checked (in order) |
|---|---|
| `processPayment` | `cancelProcessPayment`, `rollbackProcessPayment`, `undoProcessPayment`, `revertProcessPayment`, `releaseProcessPayment`, `refundProcessPayment` |
| `reserveStock` | `cancelReserveStock`, `rollbackReserveStock`, `undoReserveStock`, `revertReserveStock`, `releaseReserveStock`, `refundReserveStock` |

The **first matching method that actually exists** on the target class is used. If none exist, the step gets no compensation (it is skipped during rollback with a log warning).

**Write compensation methods in the target service class:**

```java
// In PaymentService:
public Payment processPayment(Long customerId, BigDecimal amount, Long orderId) { ... }
public void cancelProcessPayment(Long customerId, BigDecimal amount, Long orderId) {
    // issue refund
}

// In InventoryService:
public void reserveStock(List<OrderItem> items, Long orderId) { ... }
public void cancelReserveStock(List<OrderItem> items, Long orderId) {
    // release reservation
}
```

Compensation methods should:
- Accept the **same parameters** as the forward method (or a reasonable subset).
- Be idempotent — the orchestrator may call them more than once.
- Not throw exceptions that prevent rollback progress.
- Be annotated `@Transactional`.

### 15.4 What the Generator Does to the Saga Method

`SagaMethodTransformer` rewrites the saga method body in the **generated service** (not your monolith). It:

1. Removes all statements that call cross-module client fields (both return-value captures and void calls).
2. Removes dependent statements that reference the now-undefined variables (e.g., `order.setPayment(payment)`).
3. Adds `OutboxPublisher` as a constructor-injected field.
4. Inserts an outbox publish block at the position of the first cross-service call:

```java
// Generated — what the saga method body becomes in order-service:
@Transactional
public Order placeOrder(Long customerId, BigDecimal totalAmount, List<OrderItem> items) {
    Order order = new Order();
    order.setCustomerId(customerId);
    order.setTotalAmount(totalAmount);
    order.setStatus("PENDING");
    order = orderRepository.save(order);

    Long orderId = order.getId();

    // Saga steps delegated to orchestrator via transactional outbox
    java.util.Map<String, Object> sagaPayload = new java.util.LinkedHashMap<>();
    sagaPayload.put("customerId", customerId);
    sagaPayload.put("totalAmount", totalAmount);
    sagaPayload.put("items", items);
    sagaPayload.put("orderId", orderId);     // extra local var included
    outboxPublisher.publish("place-order-saga", String.valueOf(customerId), sagaPayload);

    return order;   // return fixed to use last declared Order variable
}
```

The `outboxPublisher.publish()` call writes an `OutboxEvent` row to the database in the **same transaction** as `orderRepository.save(order)`. This is the atomic dual-write that makes delivery guaranteed.

### 15.5 The Saga Orchestrator Service

`fractalx-saga-orchestrator` (port 8099) is a standalone Spring Boot service generated when at least one `@DistributedSaga` is found. It contains:

| Generated File | Purpose |
|---|---|
| `SagaStatus.java` | Enum: `STARTED`, `IN_PROGRESS`, `DONE`, `COMPENSATING`, `FAILED` |
| `SagaInstance.java` | JPA entity storing saga execution state (sagaId, correlationId, status, payload, step, error) |
| `SagaInstanceRepository.java` | Spring Data repository |
| `<SagaId>SagaService.java` | Per-saga orchestration logic: runs steps in order, compensates in reverse on failure |
| `SagaController.java` | REST: `POST /saga/{sagaId}/start`, `GET /saga/{sagaId}/status/{correlationId}`, `GET /saga/all` |
| `<BeanType>Client.java` | NetScope gRPC client for each participating service (in `client/` package) |
| `OtelConfig.java` | Tracing (always on in orchestrator) |
| `CorrelationTracingConfig.java` | Correlation ID span tagging |

The orchestrator is also wired with `fractalx-runtime` for full correlation ID propagation across its outbound gRPC calls.

### 15.6 Saga State Machine

```
                   ┌─────────────────────────────────────┐
  POST /start      │                                     │
─────────────► STARTED ──► IN_PROGRESS                   │
                               │                         │
                    step 1 OK ─┤                         │
                    step 2 OK ─┤                         │
                    step N OK ─┴──► DONE ────────────────┘
                                                         │
                    any step FAILS                       │
                               │                         │
                               ▼                         │
                          COMPENSATING                   │
                    (compensate N ... 1 in reverse)      │
                               │                         │
                               ▼                         │
                             FAILED ────────────────────►┘
```

The saga service:
1. Persists a `SagaInstance` row with status `STARTED`.
2. Calls each `SagaStep` in order via NetScope gRPC. Sets status to `IN_PROGRESS`.
3. On step success: advances to next step.
4. On step failure: transitions to `COMPENSATING`, calls compensation methods in **reverse order** (newest step first).
5. After compensation: transitions to `FAILED`.
6. On all steps success: transitions to `DONE`.

### 15.7 Saga Completion Callbacks

After the saga finishes (`DONE` or `FAILED`), the orchestrator calls back the owner service via HTTP:

| Outcome | Endpoint called on owner service |
|---|---|
| Success | `POST /internal/saga-complete/{correlationId}` |
| Failure | `POST /internal/saga-failed/{correlationId}` |

These endpoints are implemented in the generated `SagaCompletionController.java` (generated by `SagaMethodTransformer` into the owner service's `saga/` package). The controller:

- Reads the saga payload to extract the aggregate ID (e.g., `orderId`).
- On success: calls the repository to mark the entity as `CONFIRMED`.
- On failure: calls the repository to mark the entity as `CANCELLED`.
- These state transitions are auto-implemented when the analyzer can detect the aggregate ID field name (e.g., `orderId` in the payload maps to `OrderRepository.findById(id)`).

The owner URL is configured in the orchestrator's `application.yml`:

```yaml
fractalx:
  saga:
    owner-urls:
      place-order-saga: ${PLACE_ORDER_SAGA_OWNER_URL:http://localhost:8081}
```

#### 15.7.1 Notification Retry Mechanism

**Problem addressed:** The initial notification attempt is fire-and-forget. If the owner service is down or returns an error, the saga entity stays in `PENDING` forever with no recovery path.

**Solution:** Retry tracking is persisted on `SagaInstance` and a `@Scheduled` poller on each saga service retries undelivered notifications until success or max retries.

**Generator changes** (all in `SagaOrchestratorGenerator.java`):

1. **`buildSagaInstance()`** — 3 new fields on the generated `SagaInstance` entity:

   | Field | Column | Default | Purpose |
   |---|---|---|---|
   | `ownerNotified` | `owner_notified BOOLEAN` | `false` | Set to `true` when callback succeeds |
   | `notificationRetryCount` | `notification_retry_count INT` | `0` | Incremented on each failed attempt |
   | `lastNotificationAttempt` | `last_notification_attempt TIMESTAMP` | `null` | Timestamp of most recent attempt |

2. **`buildRepository()`** — 1 new query method on `SagaInstanceRepository`:
   ```java
   List<SagaInstance> findByOwnerNotifiedFalseAndStatusIn(List<SagaStatus> statuses);
   ```

3. **`notifyOwnerComplete()` / `notifyOwnerFailed()`** — changed from `private` to package-private:
   - **On success**: sets `ownerNotified = true`, saves instance.
   - **On failure**: increments `notificationRetryCount`, sets `lastNotificationAttempt`, saves instance, logs warning with attempt number.

4. **`buildSagaService()`** — adds `retryPendingNotifications()` into each generated `*SagaService`:
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

5. **`writeFlywayMigration()`** — `V1__init_saga.sql` includes the 3 new columns and a composite index `(owner_notified, status)` for efficient polling queries.

**Retry policy:**

| Parameter | Value |
|---|---|
| Poll interval | 2 seconds (`fixedDelay = 2000`) |
| Max retries | 10 |
| Dead-letter action | Log `ERROR` with `correlationId`, stop retrying |
| Recovery on owner restart | Automatic — next poll (≤2s) delivers the notification |

**Updated `saga_instance` schema:**

| Column | Type | Purpose |
|---|---|---|
| `owner_notified` | `BOOLEAN NOT NULL DEFAULT FALSE` | Cleared to `true` when callback succeeds |
| `notification_retry_count` | `INT NOT NULL DEFAULT 0` | Number of failed attempts so far |
| `last_notification_attempt` | `TIMESTAMP` | Timestamp of last attempt (null = never tried) |

### 15.8 The Transactional Outbox Pattern

The outbox pattern solves the **dual-write problem**: you cannot atomically write to your database AND send a message to another service. FractalX solves this without a message broker.

**Generated files per service (when it has cross-module dependencies):**

| File | Purpose |
|---|---|
| `OutboxEvent.java` | JPA `@Entity` stored in `fractalx_outbox` table |
| `OutboxRepository.java` | Spring Data repository: finds unpublished events |
| `OutboxPublisher.java` | `@Component`: writes outbox events (call inside `@Transactional`) |
| `OutboxPoller.java` | `@Scheduled(fixedDelay=1000)`: polls and forwards to orchestrator |

**OutboxEvent schema:**

| Column | Type | Purpose |
|---|---|---|
| `id` | `BIGINT` | Auto-increment PK |
| `event_type` | `VARCHAR` | Saga ID (e.g., `place-order-saga`) |
| `aggregate_id` | `VARCHAR` | ID of the aggregate (e.g., `"42"` for order 42) |
| `payload` | `TEXT` | JSON-serialized saga payload |
| `correlation_id` | `VARCHAR` | Correlation ID captured from MDC at publish time |
| `traceparent` | `VARCHAR(55)` | W3C traceparent captured from active OTel span |
| `published` | `BOOLEAN` | `false` until successfully forwarded |
| `retry_count` | `INT` | Retry count (capped at 5) |
| `created_at` | `DATETIME` | Creation timestamp |
| `published_at` | `DATETIME` | Forwarding timestamp |

**OutboxPoller behavior:**
- Polls every **1 second** (configurable by editing the generated class).
- Picks up all unpublished events ordered by `createdAt` (oldest first).
- Forwards each to `http://localhost:8099/saga/{eventType}/start`.
- Sets `X-Correlation-Id` header from `event.correlationId`.
- Sets `traceparent` header from `event.traceparent` (links orchestrator trace to original request in Jaeger).
- On success: marks `published=true`, sets `publishedAt`.
- On failure: increments `retryCount`. Events with `retryCount >= 5` are logged as errors and skipped (require manual inspection/cleanup).

---

## 16. Data Consistency and Isolation

### 16.1 Per-Service Database

Each generated service owns its own data store. In dev mode (default), it uses an **H2 in-memory database**. For production, configure per-service in `fractalx-config.yml`:

```yaml
fractalx:
  services:
    order-service:
      datasource:
        url: jdbc:mysql://localhost:3306/orders_db
        username: root
        password: secret
```

When a real DB is configured:
- `DbConfigurationGenerator` reads the `fractalx-config.yml` and writes the connection details into `application.yml` (dev and docker profiles).
- `DependencyManager` adds the correct JDBC driver to `pom.xml` (MySQL or PostgreSQL auto-detected from URL).

**Data isolation rule**: entities from one module must NOT be `@OneToMany`/`@ManyToOne` mapped directly to entities in another module. FractalX's `RelationshipDecoupler` detects cross-module JPA relationships and replaces them with plain `Long` foreign key columns:

```java
// Before (in monolith):
@ManyToOne
private Customer customer;  // Customer is in another module

// After (in generated service):
private Long customerId;    // Decoupled to plain ID
```

This is automatic. The `ReferenceValidatorGenerator` generates a `ReferenceValidator` bean that performs explicit ID existence checks across services via REST/gRPC instead of JPA.

### 16.2 Flyway Migrations

Each service gets a Flyway scaffold in `src/main/resources/db/migration/`:

```
V1__initial_schema.sql    — generated baseline (tables for your module's entities + fractalx_outbox)
```

The generated `V1__initial_schema.sql` contains `CREATE TABLE` statements inferred from the JPA entities copied into the service. It includes the `fractalx_outbox` table for services that participate in sagas.

Flyway is enabled in all profiles. In dev, `ddl-auto: update` also runs (belt and suspenders). In docker, `ddl-auto: validate` is used — only Flyway manages the schema.

### 16.3 Reference Validation (Decoupled Foreign Keys)

After JPA relationships are decoupled to plain IDs, cross-service ID existence checks are performed by the generated `ReferenceValidator`. This bean calls the owner service's REST endpoint before saving an entity that references another service's data.

Example: `order-service` saves an order with `customerId`. The `ReferenceValidator` calls `GET http://localhost:8082/api/customers/{customerId}` (on `customer-service`) before persisting. If the response is not 2xx, a `DataIntegrityViolationException` is thrown.

### 16.4 Data README

A `DATA_README.md` is generated in each service root explaining:
- Which schemas this service owns
- Cross-module data dependencies (decoupled foreign keys)
- Which sagas involve this service
- How to run the Flyway migration
- Database connection defaults

---

## 17. Admin Service and Admin UI

`admin-service` (port 9090) is a Spring Boot service with a single-page HTML admin UI. It provides:

| Section | What it Shows |
|---|---|
| **Services** | Health status of all registered services (polls `fractalx-registry`) |
| **Topology** | Visual map of service dependencies and call directions |
| **Traces** | Distributed traces from Jaeger (proxied via `/api/traces`) — 7 columns: Trace ID, Correlation ID, Service, Duration, Spans, Jaeger link, Logs link |
| **Logs** | Aggregated logs from `logger-service` (proxied via `/api/logs`) — server-side pagination, 50/page, newest-first |
| **Sagas** | List of all saga instances with status, correlation ID, step progress |
| **Data Consistency** | Outbox queue sizes, event flow visualization |
| **API Explorer** | Try-out panel for all service REST endpoints |
| **gRPC Browser** | List of all NetScope gRPC methods across services |
| **Config Editor** | View and edit service application.yml at runtime |
| **Incidents** | Alerts for circuit breaker opens, high error rates |
| **Analytics** | Request rates, error rates, latency percentiles |
| **User Management** | Admin user list (static) |

**Traces tab specifics:**
- Newest-first (results reversed client-side).
- Client-side pagination: 20 traces per page, fetches 100 from Jaeger per load.
- Clicking "Logs" opens the Logs tab pre-filtered by that correlation ID.
- Service dropdown populated by querying Jaeger's `/api/services`.

**Logs tab specifics:**
- Newest-first (results reversed per page).
- Server-side pagination: 50 entries per page.
- Filters: service name, log level, free-text search.
- `goToLogsWithCorrelation(cid)` navigates to logs pre-filtered by correlation ID (called from Traces tab "Logs" button).

**Admin UI security**: basic auth with credentials from `@AdminEnabled` annotation or set via environment:

```
ADMIN_USERNAME=admin
ADMIN_PASSWORD=secretpassword
```

---

## 18. Logger Service

`logger-service` (port 9099) stores and serves log entries. It is a simple Spring Boot service with no database — all data lives in a **ConcurrentLinkedDeque** ring buffer capped at **5000 entries** (oldest discarded on overflow).

| Endpoint | Method | Description |
|---|---|---|
| `/api/logs` | `POST` | Accept a log entry JSON (called by `FractalLogAppender`) |
| `/api/logs` | `GET` | Query logs: `?page=0&size=50&service=x&level=ERROR&search=text` |
| `/actuator/health` | `GET` | Health check |

The logger service does not persist to disk. If it restarts, all logs are lost. For production use, replace the in-memory store with a persistent backend or use a proper log aggregation system (ELK, Grafana Loki).

---

## 19. Maven Plugin Goals

Add the plugin to your monolith `pom.xml`:

```xml
<plugin>
    <groupId>org.fractalx</groupId>
    <artifactId>fractalx-maven-plugin</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</plugin>
```

| Goal | Command | Description |
|---|---|---|
| `decompose` | `mvn fractalx:decompose` | Analyzes monolith and generates all microservices into `microservices/` |
| `verify` | `mvn fractalx:verify` | Statically verifies the generated output without starting services |
| `start` | `mvn fractalx:start` | Starts all generated services (requires prior decompose) |
| `stop` | `mvn fractalx:stop` | Stops all running generated services |
| `restart` | `mvn fractalx:restart` | Restarts a specific service |
| `ps` | `mvn fractalx:ps` | Shows running status of all generated services |
| `services` | `mvn fractalx:services` | Lists all detected modules and their ports |

### Plugin Parameters (decompose)

| Parameter | Property | Default | Description |
|---|---|---|---|
| `sourceDirectory` | `fractalx.sourceDirectory` | `${project.build.sourceDirectory}` | Monolith `src/main/java` |
| `outputDirectory` | `fractalx.outputDirectory` | `${project.basedir}/microservices` | Where to write generated projects |
| `skip` | `fractalx.skip` | `false` | Skip decomposition entirely |
| `generate` | `fractalx.generate` | `true` | Set `false` to only analyze, not generate |

```bash
# Skip generation, just analyze and print modules found:
mvn fractalx:decompose -Dfractalx.generate=false

# Output to a custom directory:
mvn fractalx:decompose -Dfractalx.outputDirectory=/tmp/my-services

# Skip entirely (useful in CI to speed up builds during testing):
mvn fractalx:decompose -Dfractalx.skip=true
```

### Progress Display

`fractalx:decompose` uses an alternate screen buffer (if the terminal supports ANSI) to display an in-place dashboard showing each generation step's status. On completion, it returns to the normal screen and prints a Vercel-style summary:

```
  Microservices
    order-service        http://localhost:8081
    payment-service      http://localhost:8082
    inventory-service    http://localhost:8083

  Infrastructure
    fractalx-gateway     http://localhost:9999
    admin-service        http://localhost:9090
    fractalx-registry    http://localhost:8761

  Get started
    cd microservices
    ./start-all.sh
    docker-compose up -d

  Done in 3.4s
```

---

## 20. Docker Compose and Start Scripts

`DockerComposeGenerator` produces a `docker-compose.yml` that brings up all generated services plus Jaeger:

```yaml
version: "3.8"
services:

  jaeger:
    image: jaegertracing/all-in-one:latest
    ports:
      - "16686:16686"   # Jaeger UI
      - "4317:4317"     # OTLP gRPC

  fractalx-registry:
    build: ./fractalx-registry
    ports:
      - "8761:8761"

  logger-service:
    build: ./logger-service
    ports:
      - "9099:9099"

  order-service:
    build: ./order-service
    ports:
      - "8081:8081"
      - "18081:18081"   # gRPC port
    environment:
      - SPRING_PROFILES_ACTIVE=docker
      - FRACTALX_REGISTRY_URL=http://fractalx-registry:8761
      - FRACTALX_LOGGER_URL=http://logger-service:9099/api/logs
      - OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4317
    depends_on:
      - fractalx-registry
      - logger-service

  # ... payment-service, inventory-service, gateway, admin, saga-orchestrator
```

Each service's `Dockerfile` is generated with a standard multi-stage Maven build.

**Start scripts:**

`start-all.sh` — starts services in order: registry → microservices → saga orchestrator → gateway. Includes 5-second waits for the registry to become ready. Logs each service to `<name>.log`.

`stop-all.sh` — kills all services via `pkill -f spring-boot:run` and service names.

---

## 21. Verification — `fractalx:verify`

`mvn fractalx:verify` runs the `DecompositionVerifier` against the generated output. No services need to be running.

**Checks performed:**

| Category | Check | Severity |
|---|---|---|
| Infrastructure | `fractalx-registry` dir exists | FAIL |
| Infrastructure | `fractalx-gateway` dir exists | FAIL |
| Infrastructure | `admin-service` dir exists | FAIL |
| Infrastructure | `logger-service` dir exists | FAIL |
| Infrastructure | `docker-compose.yml` exists | FAIL |
| Infrastructure | `start-all.sh` exists | FAIL |
| Infrastructure | `stop-all.sh` exists | FAIL |
| Per Service | Service directory exists | FAIL |
| Per Service | `pom.xml` present | FAIL |
| Per Service | `Dockerfile` present | WARN |
| Per Service | `application.yml` present | FAIL |
| Per Service | Correct port in `application.yml` | FAIL |
| Per Service | Main class exists | FAIL |
| NetScope | `@NetScopeClient` present when module has deps | WARN |
| NetScope | `@NetworkPublic` present when module is called | WARN |
| Docker | `docker-compose.yml` references every service | WARN |

Additional checkers (run independently, output to log):
- `CrossBoundaryImportChecker` — warns when one module imports classes from another module's package directly
- `PortConflictChecker` — fails if two services share the same port
- `SecretLeakScanner` — warns if passwords/keys appear to be hardcoded
- `SqlSchemaValidator` — validates generated Flyway SQL syntax
- `ApiConventionChecker` — warns if REST controllers use non-standard path patterns
- `CompilationVerifier` — attempts to compile each generated service via `javac`
- `SmokeTestGenerator` — generates a JUnit smoke test class per service

---

## 22. Full Monolith Example — Annotated Code

Here is a minimal but complete modular monolith that FractalX can fully decompose.

### `pom.xml` (monolith)

```xml
<dependencies>
    <dependency>
        <groupId>org.fractalx</groupId>
        <artifactId>fractalx-annotations</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </dependency>
    <dependency>
        <groupId>org.fractalx</groupId>
        <artifactId>fractalx-runtime</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </dependency>
    <!-- your normal Spring Boot deps -->
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.fractalx</groupId>
            <artifactId>fractalx-maven-plugin</artifactId>
            <version>1.0.0-SNAPSHOT</version>
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

    private final PaymentService  paymentService;
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

        // Cross-module calls — these are REPLACED by outbox.publish() in generated code:
        paymentService.processPayment(customerId, totalAmount, orderId);
        inventoryService.reserveStock(orderId);

        // These lines are removed by the generator (orphaned after publish):
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

    // Forward method — called as step 1 in place-order-saga
    @Transactional
    public Payment processPayment(Long customerId, BigDecimal amount, Long orderId) {
        Payment payment = new Payment(customerId, amount, orderId, "PROCESSED");
        return paymentRepository.save(payment);
    }

    // Compensation — detected by SagaAnalyzer (prefix "cancel" + "ProcessPayment")
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

    // Forward method — called as step 2 in place-order-saga
    @Transactional
    public void reserveStock(Long orderId) {
        // ... reserve logic
    }

    // Compensation — "cancel" + "ReserveStock"
    @Transactional
    public void cancelReserveStock(Long orderId) {
        // ... release reservation
    }
}
```

---

## 23. Troubleshooting / Common Mistakes

### "No @DecomposableModule classes found"
- Ensure `@DecomposableModule` is on a class, not an interface.
- Ensure the class is under `src/main/java` (the default `sourceDirectory`).
- Check that the annotation is imported from `org.fractalx.annotations.DecomposableModule`.

### Cross-module dependency not detected
- The injected field type must end in exactly `Service` or `Client` (case-sensitive suffix check).
- The type must be declared as a **field** or **constructor parameter** directly in the `@DecomposableModule` class (not in a parent class or inner class).
- `OrderRepository` ends in `Repository` — not detected as a cross-module dep (correct behavior).

### Saga steps not detected
- Cross-service calls must be on **fields** of the `@DecomposableModule` class, not local variables created inside the method.
- The calls must appear directly in the method body (not delegated to a private helper method).
- The field type must be detected as a cross-module dependency (same rule as above — ends in `Service` or `Client`).

### Compensation method not auto-detected
- Method must exist on the **target bean class** (e.g., `PaymentService`, not `PaymentModule`).
- Name must be one of the prefixes: `cancel`, `rollback`, `undo`, `revert`, `release`, `refund` + capitalized forward method name.
- Check the generator log: `No compensation method found for PaymentService.processPayment (checked: cancelProcessPayment, rollbackProcessPayment, ...)`.

### Gateway returns 404 for my endpoint
- Check that your controller uses `@RequestMapping("/api/orders")` — the gateway routes `/api/order/**` and `/api/orders/**`.
- Verify the service name follows the pattern: `<path>-service` → route is `/api/<path>/**`.
- `StripPrefix=0` means the path is not modified — your service must match the full `/api/orders/...` path.

### Port conflict between services
- Set explicit ports in `fractalx-config.yml` under `fractalx.services.<name>.port`.
- The gateway auto-increments conflicting ports with a warning in the log — check `fractalx-gateway/application.yml` to see the resolved ports.
- The `PortConflictChecker` in `fractalx:verify` will report conflicts.

### Outbox events not forwarding (saga not starting)
- Check that `OutboxPoller` is running: look for `Forwarded outbox event` log messages.
- Verify the saga orchestrator URL: `fractalx.saga.orchestrator.url` in `application.yml` (default `http://localhost:8099`).
- Events with `retry_count >= 5` are logged as errors but left in the DB — inspect `fractalx_outbox` table.
- The `OutboxPoller` runs every 1 second. After successful delivery `published=true` and `published_at` is set.

### Tracing not showing in Jaeger
- Ensure Jaeger is running on port 4317 (OTLP gRPC).
- Check `OTEL_EXPORTER_OTLP_ENDPOINT` is set correctly.
- Verify `management.tracing.sampling.probability: 1.0` in `application.yml` (will be `0.0` if tracing was disabled via config).
- The OTel SDK bean is `@ConditionalOnMissingBean(OpenTelemetry.class)` — if another bean provides `OpenTelemetry`, the generated one is skipped. Ensure no conflicting auto-configuration.

### Correlation ID not appearing in logs
- Check `logback-spring.xml` exists in `src/main/resources` (generated by `CorrelationIdGenerator`).
- The pattern must include `%X{correlationId}`.
- For HTTP requests: `TraceFilter` must be registered (it is a `@Component` auto-configured by `fractalx-runtime`).
- For gRPC calls: `NetScopeContextInterceptor` must be applied (wired by `NetScopeGrpcInterceptorConfigurer`).

### Admin UI shows no traces / "Cannot connect to Jaeger"
- Admin service proxies Jaeger at `/api/traces` — ensure Jaeger UI port 16686 is accessible from the admin service host.
- The `AdminObservabilityGenerator` generates a proxy controller that calls `http://localhost:16686/api/traces`.
- When searching by correlation ID without selecting a service, the admin proxies all Jaeger services and merges results.

### Generated code does not compile
- Run `mvn fractalx:verify` — the `CompilationVerifier` step will show which files fail and why.
- Common cause: a class referenced in your monolith is not in the copied package. FractalX only copies files from the module's own package. Shared utilities must be duplicated or moved to a shared module.
- Cross-module JPA entity references that were not decoupled by `RelationshipDecoupler` — check the import list.
