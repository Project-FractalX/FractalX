# FractalX Developer Guide

> How to build a modular monolith application that FractalX can decompose into production-ready microservices.

This guide is written for **application developers** — people who write the business code.
It answers one question: **"How do I write my Spring Boot application so that FractalX can decompose it?"**

---

## Table of Contents

1. [How FractalX Works — The Big Picture](#1-how-fractalx-works--the-big-picture)
2. [Quick Start — Your First Decomposable App in 10 Minutes](#2-quick-start--your-first-decomposable-app-in-10-minutes)
3. [Setting Up Your Monolith Project](#3-setting-up-your-monolith-project)
   - [pom.xml](#31-pomxml)
   - [Application Entry Point](#32-application-entry-point)
   - [Monolith application.yml](#33-monolith-applicationyml)
4. [How to Structure Your Code (The Golden Rules)](#4-how-to-structure-your-code-the-golden-rules)
   - [One Package Per Module](#41-one-package-per-module)
   - [One Boundary Class Per Module (@DecomposableModule)](#42-one-boundary-class-per-module-decomposablemodule)
   - [Entities and Repositories — One Module Owns Everything](#43-entities-and-repositories--one-module-owns-everything)
   - [No Cross-Module JPA Relationships](#44-no-cross-module-jpa-relationships)
   - [Controllers — Path Conventions](#45-controllers--path-conventions)
5. [Writing a Module — Step by Step](#5-writing-a-module--step-by-step)
   - [The Module Boundary Class](#51-the-module-boundary-class)
   - [Service Classes](#52-service-classes)
   - [Entities](#53-entities)
   - [Repositories](#54-repositories)
   - [Controllers](#55-controllers)
   - [DTOs and Request/Response Objects](#56-dtos-and-requestresponse-objects)
6. [Calling Another Module (Cross-Module Dependencies)](#6-calling-another-module-cross-module-dependencies)
   - [How to Declare a Cross-Module Dependency](#61-how-to-declare-a-cross-module-dependency)
   - [Calling the Dependency at Runtime](#62-calling-the-dependency-at-runtime)
   - [What to Put in the Shared Interface](#63-what-to-put-in-the-shared-interface)
   - [Naming Rules That FractalX Enforces](#64-naming-rules-that-fractalx-enforces)
7. [Writing Distributed Sagas](#7-writing-distributed-sagas)
   - [When Do You Need a Saga](#71-when-do-you-need-a-saga)
   - [Step 1 — Write the Saga Method](#72-step-1--write-the-saga-method)
   - [Step 2 — Write the Compensation Methods](#73-step-2--write-the-compensation-methods)
   - [Step 3 — Write the Overall Rollback Method](#74-step-3--write-the-overall-rollback-method)
   - [Full Annotated Saga Example](#75-full-annotated-saga-example)
   - [Saga Rules Checklist](#76-saga-rules-checklist)
8. [fractalx-config.yml — Configuring Your Decomposition](#8-fractalx-configyml--configuring-your-decomposition)
9. [Running Your Monolith During Development](#9-running-your-monolith-during-development)
10. [Decomposing — Running mvn fractalx:decompose](#10-decomposing--running-mvn-fractalxdecompose)
11. [Running the Generated Microservices](#11-running-the-generated-microservices)
12. [What Gets Generated — A Mental Model](#12-what-gets-generated--a-mental-model)
    - [Per-Module Service](#121-per-module-service)
    - [API Gateway (port 9999)](#122-api-gateway-port-9999)
    - [Saga Orchestrator (port 8099)](#123-saga-orchestrator-port-8099)
    - [Admin UI (port 9090)](#124-admin-ui-port-9090)
    - [Logger Service (port 9099)](#125-logger-service-port-9099)
    - [Registry (port 8761)](#126-registry-port-8761)
13. [Logging — How to Log Correctly](#13-logging--how-to-log-correctly)
14. [Distributed Tracing — What You Get For Free](#14-distributed-tracing--what-you-get-for-free)
    - [Disabling Tracing for a Specific Service](#141-disabling-tracing-for-a-specific-service)
15. [API Gateway — How Routes Are Mapped](#15-api-gateway--how-routes-are-mapped)
16. [Data — What Happens to Your Database](#16-data--what-happens-to-your-database)
    - [Each Service Gets Its Own Database](#161-each-service-gets-its-own-database)
    - [Configuring a Real Database](#162-configuring-a-real-database)
    - [Flyway Migrations](#163-flyway-migrations)
17. [Resilience — What You Get For Free](#17-resilience--what-you-get-for-free)
18. [Inter-Service Communication (NetScope gRPC)](#18-inter-service-communication-netscope-grpc)
19. [Complete Working Example — E-Commerce Monolith](#19-complete-working-example--e-commerce-monolith)
20. [Dos and Don'ts — Full Rules Reference](#20-dos-and-donts--full-rules-reference)
21. [Annotations Quick Reference](#21-annotations-quick-reference)
22. [Troubleshooting](#22-troubleshooting)

---

## 1. How FractalX Works — The Big Picture

You write a **single normal Spring Boot application**. You add a few FractalX annotations to declare module boundaries. You run one Maven command. FractalX reads your code, analyzes it, and generates a complete set of independent microservices for you.

```
Your Monolith (you write this)           Generated Output (FractalX produces this)
─────────────────────────────           ──────────────────────────────────────────
src/main/java/
  com/example/
    order/                    ────────►  fractalx-output/order-service/
      OrderModule.java                     src/main/java/...
      OrderService.java                    pom.xml
      Order.java                           Dockerfile
      OrderRepository.java                 application.yml
      OrderController.java
    payment/                  ────────►  fractalx-output/payment-service/
      PaymentModule.java
      PaymentService.java                + fractalx-output/fractalx-gateway/
      ...                                + fractalx-output/fractalx-admin/
    inventory/                ────────►  fractalx-output/inventory-service/
      InventoryModule.java                + fractalx-output/fractalx-registry/
      ...                                + fractalx-output/logger-service/
                                         + fractalx-output/fractalx-saga-orchestrator/
                                         + fractalx-output/docker-compose.yml
                                         + fractalx-output/start-all.sh
```

**Key insight**: your monolith continues to run as a normal Spring Boot app — nothing breaks. FractalX reads it statically and produces the microservices as a separate output. You can keep developing the monolith and re-decompose at any time.

**What FractalX generates for you, automatically:**
- A fully-wired Spring Boot service per module with its own `pom.xml`, `Dockerfile`, health endpoints, and YAML configs
- A Spring Cloud Gateway with routing, CORS, rate limiting, circuit breakers, and security options
- A saga orchestrator that manages your distributed transactions
- The transactional outbox pattern (at-least-once delivery, no message broker needed)
- A centralized log collector (logger-service)
- A service registry
- An admin UI with traces, logs, saga monitoring, and service health
- OpenTelemetry distributed tracing with Jaeger integration
- Resilience4j circuit breakers, retries, and time limiters
- A `docker-compose.yml` and start/stop scripts
- Flyway database migration scaffolds

---

## 2. Quick Start — Your First Decomposable App in 10 Minutes

### Step 1 — Add FractalX to your pom.xml

```xml
<dependencies>
    <!-- FractalX annotations (compile-time) -->
    <dependency>
        <groupId>org.fractalx</groupId>
        <artifactId>fractalx-annotations</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </dependency>

    <!-- FractalX runtime (logging, tracing, correlation IDs) -->
    <dependency>
        <groupId>org.fractalx</groupId>
        <artifactId>fractalx-runtime</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <!-- FractalX Maven plugin -->
        <plugin>
            <groupId>org.fractalx</groupId>
            <artifactId>fractalx-maven-plugin</artifactId>
            <version>1.0.0-SNAPSHOT</version>
        </plugin>
    </plugins>
</build>
```

### Step 2 — Annotate your main class

```java
@SpringBootApplication
@AdminEnabled(port = 9090)
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

### Step 3 — Add `@DecomposableModule` to one class per domain

```java
package com.example.order;

@DecomposableModule(serviceName = "order-service", port = 8081)
@Service
public class OrderModule {
    // this class becomes the root of the order-service microservice
}
```

### Step 4 — Decompose

```bash
mvn fractalx:decompose
```

That is it. Your microservices are in `fractalx-output/`.

### Step 5 — Run them

```bash
cd fractalx-output
./start-all.sh
```

Or with Docker:

```bash
cd fractalx-output
docker-compose up -d
```

---

## 3. Setting Up Your Monolith Project

### 3.1 pom.xml

Your monolith is a standard Spring Boot Maven project. Add two FractalX dependencies and the plugin:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" ...>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.0</version>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>my-monolith</artifactId>
    <version>1.0.0-SNAPSHOT</version>

    <properties>
        <java.version>17</java.version>
    </properties>

    <dependencies>
        <!-- Standard Spring Boot deps you already have -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- FractalX — add these two -->
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
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
            <!-- FractalX plugin -->
            <plugin>
                <groupId>org.fractalx</groupId>
                <artifactId>fractalx-maven-plugin</artifactId>
                <version>1.0.0-SNAPSHOT</version>
            </plugin>
        </plugins>
    </build>
</project>
```

### 3.2 Application Entry Point

Add `@AdminEnabled` to configure the generated admin service. All other annotations on this class are preserved as-is.

```java
package com.example;

import org.fractalx.annotations.AdminEnabled;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling                   // required if you use @DistributedSaga (outbox poller uses @Scheduled)
@AdminEnabled(
    port     = 9090,
    username = "admin",
    password = "admin123"           // change in production — see fractalx-config.yml
)
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

### 3.3 Monolith application.yml

This file configures the monolith when you run it as a single application during development. FractalX also reads some values from here as fallback configuration.

```yaml
spring:
  application:
    name: my-monolith
  datasource:
    url: jdbc:h2:mem:monolith_db
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
  h2:
    console:
      enabled: true

server:
  port: 8080

# FractalX runtime configuration
fractalx:
  enabled: true
  registry:
    url: http://localhost:8761
  observability:
    tracing: true
    metrics: true
    logger-url: http://localhost:9099/api/logs   # logger-service endpoint

logging:
  level:
    com.example: DEBUG
    org.fractalx: INFO
```

---

## 4. How to Structure Your Code (The Golden Rules)

These rules are what FractalX reads. Following them ensures correct decomposition.

### 4.1 One Package Per Module

Each future microservice gets its own Java package. All classes that belong to that service live inside that package (and its sub-packages).

```
src/main/java/com/example/
    order/              ← everything for order-service lives here
        OrderModule.java
        OrderService.java
        Order.java
        OrderItem.java
        OrderRepository.java
        OrderController.java
        dto/
            CreateOrderRequest.java
            OrderResponse.java

    payment/            ← everything for payment-service lives here
        PaymentModule.java
        PaymentService.java
        Payment.java
        PaymentRepository.java
        PaymentController.java

    inventory/          ← everything for inventory-service lives here
        InventoryModule.java
        InventoryService.java
        InventoryItem.java
        InventoryRepository.java
        InventoryController.java

    shared/             ← shared utilities (copied into all services — use sparingly)
        Money.java
        PageRequest.java
```

**Rule: do not put classes from two different modules in the same package.** FractalX copies everything inside a module's package into that service. If you mix two modules in one package, all those files go into both services.

### 4.2 One Boundary Class Per Module (`@DecomposableModule`)

Each module has exactly **one** class annotated with `@DecomposableModule`. This is the "boundary class" — it declares the module's service name, port, and cross-module dependencies.

```java
// CORRECT — one boundary class per module
@DecomposableModule(serviceName = "order-service", port = 8081)
@Service
public class OrderModule { ... }

// WRONG — do not annotate multiple classes in the same module
@DecomposableModule(serviceName = "order-service", port = 8081)
@Service
public class OrderService { ... }   // <-- do not do this
```

The boundary class declares:
- The module metadata (`serviceName`, `port`, `ownedSchemas`)
- Cross-module dependencies (injected via constructor)
- `@DistributedSaga` methods, if this module initiates distributed transactions

> **Critical rule — same-module vs cross-module fields:**
>
> **New in 0.5.0:** FractalX now uses a deterministic **DependencyGraph** with structural annotation queries to detect dependencies, eliminating many name-suffix heuristics. However, it is **highly recommended** to explicitly declare cross-module dependencies using the `dependencies` attribute in the `@DecomposableModule` annotation.
> 
> Example: `@DecomposableModule(serviceName = "order-service", port = 8081, dependencies = {PaymentService.class, InventoryService.class})`
>
> If you omit the `dependencies` attribute, FractalX falls back to a heuristic that checks whether a constructor-injected field's **type name ends in `Service` or `Client`**. It does **not** look at the package. This means that if you inject a same-module class whose name ends in `Service` (e.g., `OrderService`), FractalX may mistakenly treat it as a cross-module dependency and try to generate a gRPC client for it — which will break the generated service.
>
> **Rule**: In the `@DecomposableModule` class, explicitly list cross-module dependencies using the `dependencies` array. If relying on the fallback heuristic, only inject:
> 1. Types from **other modules** whose names end in `Service` or `Client` (these become gRPC clients)
> 2. `*Repository` types from the **same module** (these stay as local Spring beans)
>
> For same-module service logic, either:
> - Call the repository directly inside the boundary class
> - Extract the logic to a class named `*Handler`, `*Manager`, `*Operations`, or `*Processor` (any suffix that does NOT end in `Service` or `Client`), and inject that

### 4.3 Entities and Repositories — One Module Owns Everything

Each entity and its repository must belong to exactly one module. No two modules share an entity class.

```
order/
    Order.java          ← owned by order module
    OrderItem.java      ← owned by order module
    OrderRepository.java

payment/
    Payment.java        ← owned by payment module
    PaymentRepository.java

inventory/
    InventoryItem.java  ← owned by inventory module
    InventoryItemRepository.java
```

### 4.4 No Cross-Module JPA Relationships

You cannot have `@ManyToOne`, `@OneToMany`, or `@OneToOne` references that cross module boundaries. If `Order` lives in the `order` module and `Customer` lives in the `customer` module, you cannot do this:

```java
// WRONG — cross-module JPA relationship
@Entity
public class Order {
    @ManyToOne
    private Customer customer;   // Customer is in customer module — NOT ALLOWED
}
```

Instead, store only the foreign key ID:

```java
// CORRECT — store only the ID from the other module
@Entity
public class Order {
    @Column(nullable = false)
    private Long customerId;     // store the ID, not the object
}
```

When you need to validate that the customer exists, do it via a service call (see [Section 6](#6-calling-another-module-cross-module-dependencies)).

FractalX will automatically convert `@ManyToOne Customer customer` → `Long customerId` during code transformation, but it is better to write it correctly from the start to keep your monolith clean.

### 4.5 Controllers — Path Conventions

Your REST controllers must use path prefixes that match the gateway routing pattern. The gateway routes requests based on the service name:

| Service Name | Gateway Path | Your Controller Must Use |
|---|---|---|
| `order-service` | `/api/order/**` and `/api/orders/**` | `@RequestMapping("/api/orders")` |
| `payment-service` | `/api/payment/**` and `/api/payments/**` | `@RequestMapping("/api/payments")` |
| `inventory-service` | `/api/inventory/**` and `/api/inventorys/**` | `@RequestMapping("/api/inventory")` |
| `user-account-service` | `/api/user-account/**` | `@RequestMapping("/api/user-account")` |

**Rule**: use `/api/<service-name-without-service-suffix>/` as your base path.

```java
@RestController
@RequestMapping("/api/orders")     // matches gateway route for order-service
public class OrderController { ... }

@RestController
@RequestMapping("/api/payments")   // matches gateway route for payment-service
public class PaymentController { ... }
```

---

## 5. Writing a Module — Step by Step

Here is exactly how to write each file in a module.

### 5.1 The Module Boundary Class

This is the most important class. It:
- Is annotated with `@DecomposableModule`
- Is annotated with `@Service` (or another Spring stereotype)
- Declares all cross-module dependencies as **constructor-injected fields** whose types end in `Service` or `Client`
- Contains `@DistributedSaga` methods if this module initiates distributed transactions
- Contains the overall saga compensation method

```java
package com.example.order;

import org.fractalx.annotations.DecomposableModule;
import org.fractalx.annotations.DistributedSaga;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;

@DecomposableModule(
    serviceName  = "order-service",
    port         = 8081,
    ownedSchemas = {"orders"},  // informational — appears in data README
    dependencies = {PaymentService.class, InventoryService.class} // New in 0.5.0: explicitly declare dependencies
)
@Service
public class OrderModule {

    // Cross-module dependencies — field types end in "Service" or "Client",
    // and these classes belong to OTHER modules (payment, inventory)
    private final PaymentService   paymentService;
    private final InventoryService inventoryService;

    // Local dependencies — use Repository types (ends in "Repository", NOT detected as cross-module)
    // DO NOT inject same-module *Service classes here — they would be falsely treated as cross-module!
    private final OrderRepository orderRepository;

    // Constructor injection — always use constructor injection, not field injection
    public OrderModule(PaymentService   paymentService,
                       InventoryService inventoryService,
                       OrderRepository  orderRepository) {
        this.paymentService   = paymentService;
        this.inventoryService = inventoryService;
        this.orderRepository  = orderRepository;
    }

    @DistributedSaga(
        sagaId             = "place-order-saga",
        compensationMethod = "cancelPlaceOrder",
        timeout            = 30000,
        description        = "Places an order: charges payment and reserves inventory.",
        successStatus      = "CONFIRMED",   // required — set on Order entity when saga succeeds
        failureStatus      = "CANCELLED"    // required — set on Order entity when saga fails
    )
    @Transactional
    public Order placeOrder(Long customerId, BigDecimal totalAmount) {
        // ... see Section 7
    }

    @Transactional
    public void cancelPlaceOrder(Long customerId, BigDecimal totalAmount) {
        // ... overall saga rollback for this module
    }
}
```

**If you need same-module service logic** in the boundary class, name the helper class using a non-`Service` suffix:

```java
// CORRECT — suffix "Operations" is not detected as cross-module
@Service
public class OrderOperations {
    private final OrderRepository orderRepository;
    // ... helper logic
}

@DecomposableModule(serviceName = "order-service", port = 8081)
@Service
public class OrderModule {
    private final PaymentService    paymentService;   // cross-module ✓
    private final OrderOperations   orderOperations;  // local — not detected ✓
    private final OrderRepository   orderRepository;  // local ✓
}

// WRONG — same-module class ending in "Service" in the boundary constructor
@DecomposableModule(serviceName = "order-service", port = 8081)
@Service
public class OrderModule {
    private final PaymentService  paymentService;  // cross-module ✓
    private final OrderService    orderService;    // WRONG — same module, falsely detected!
}
```

### 5.2 Service Classes

Regular service classes inside a module are plain Spring `@Service` beans. They do NOT have `@DecomposableModule`. They can be called by the boundary class and by controllers.

```java
package com.example.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional
    public Order createDraftOrder(Long customerId, BigDecimal totalAmount) {
        Order order = new Order();
        order.setCustomerId(customerId);
        order.setTotalAmount(totalAmount);
        order.setStatus("DRAFT");
        return orderRepository.save(order);
    }

    public Order findById(Long orderId) {
        return orderRepository.findById(orderId)
            .orElseThrow(() -> new EntityNotFoundException("Order not found: " + orderId));
    }
}
```

### 5.3 Entities

Write JPA entities normally. Follow these rules:

1. **Do not import entity classes from other modules.** If you need a reference, store the ID (a `Long` field).
2. **Use `@Column(nullable = false)` for required fields** — FractalX uses this to generate Flyway SQL.
3. **Include a `status` String or enum field** for entities involved in sagas — the generated `SagaCompletionController` will set it to the values declared in `successStatus` / `failureStatus` on the `@DistributedSaga` annotation.
4. **Add `createdAt` / `updatedAt` fields** if you want them — they are preserved as-is.

```java
package com.example.order;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long customerId;        // ID from customer-service — NOT @ManyToOne

    @Column(nullable = false)
    private BigDecimal totalAmount;

    @Column(nullable = false)
    private String status;          // e.g. "PENDING", "CONFIRMED", "CANCELLED"
                                    // FractalX SagaCompletionController sets this to
                                    // successStatus / failureStatus from @DistributedSaga

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // getters and setters ...
}
```

**For cross-module references, store only the ID:**

```java
// CORRECT
@Column(nullable = false)
private Long customerId;

// WRONG
@ManyToOne
@JoinColumn(name = "customer_id")
private Customer customer;   // Customer is not in this module
```

### 5.4 Repositories

Standard Spring Data JPA repositories. Nothing special — write them as you normally would.

```java
package com.example.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByCustomerId(Long customerId);

    Optional<Order> findTopByCustomerIdAndStatusOrderByCreatedAtDesc(
        Long customerId, String status);

    @Query("SELECT o FROM Order o WHERE o.status = :status")
    List<Order> findByStatus(String status);
}
```

### 5.5 Controllers

Write REST controllers normally. The only rule is to use the correct path prefix (see [Section 4.5](#45-controllers--path-conventions)).

```java
package com.example.order;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/orders")      // use /api/<module-name>/ prefix
public class OrderController {

    private final OrderService    orderService;
    private final OrderModule     orderModule;

    public OrderController(OrderService orderService, OrderModule orderModule) {
        this.orderService = orderService;
        this.orderModule  = orderModule;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> getOrder(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.findById(id));
    }

    @PostMapping
    public ResponseEntity<Order> placeOrder(@RequestBody PlaceOrderRequest request) {
        Order order = orderModule.placeOrder(
            request.getCustomerId(),
            request.getTotalAmount(),
            request.getItems()
        );
        return ResponseEntity.ok(order);
    }

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<Order>> getOrdersByCustomer(@PathVariable Long customerId) {
        return ResponseEntity.ok(orderService.findByCustomer(customerId));
    }
}
```

### 5.6 DTOs and Request/Response Objects

Put DTOs in a sub-package of your module (e.g., `com.example.order.dto`). They will be copied into the generated service automatically.

```java
package com.example.order.dto;

import java.math.BigDecimal;
import java.util.List;

public class PlaceOrderRequest {
    private Long customerId;
    private BigDecimal totalAmount;
    private List<OrderItemRequest> items;
    // getters and setters
}
```

**Do not share DTOs between modules.** If `order-service` and `payment-service` both need a `Money` class, either:
- Duplicate it in both packages (preferred — keeps services truly independent)
- Put it in a `shared/` package (it will be copied into every service)

---

## 6. Calling Another Module (Cross-Module Dependencies)

### 6.1 How to Declare a Cross-Module Dependency

In the monolith, cross-module calls are normal Spring bean injections. **New in 0.5.0:** FractalX uses Phase 1 & 2 deterministic DependencyGraph structural queries to replace previous name-suffix and args-based heuristics for dependency detection, auth/data isolation, and JWT claim extraction from the UserDetails entity. 

The recommended way to declare a cross-module call is to **explicitly define it in the `dependencies` attribute of `@DecomposableModule`**.
If you omit the `dependencies` attribute, the fallback heuristic enforces:

1. **The injected field type must end in `Service` or `Client`.**
2. **The class must belong to a DIFFERENT module's package.** FractalX does NOT check the package — you must ensure this yourself. If you inject a same-module class whose name ends in `Service`, FractalX will mistakenly generate a gRPC client for it.

```java
@DecomposableModule(serviceName = "order-service", port = 8081)
@Service
public class OrderModule {

    // CORRECT — cross-module deps: types end in "Service" AND belong to other modules
    private final PaymentService   paymentService;    // from com.example.payment → payment-service
    private final InventoryService inventoryService;  // from com.example.inventory → inventory-service

    // CORRECT — local dep: ends in "Repository", never detected as cross-module
    private final OrderRepository  orderRepository;

    // WRONG — same-module class ending in "Service":
    // private final OrderService orderService;  // OrderService is in com.example.order — DO NOT inject here!
}
```

FractalX converts `PaymentService` to the service name `payment-service` using this rule:
- Strip the `Service` suffix
- Convert PascalCase to kebab-case
- Append `-service`

So: `PaymentService` → `payment` → `payment-service`. This must match the `serviceName` of the target `@DecomposableModule`.

| Field Type | Generated Service Name | Must Match `serviceName` |
|---|---|---|
| `PaymentService` | `payment-service` | `@DecomposableModule(serviceName = "payment-service")` |
| `InventoryService` | `inventory-service` | `@DecomposableModule(serviceName = "inventory-service")` |
| `UserAccountService` | `user-account-service` | `@DecomposableModule(serviceName = "user-account-service")` |
| `EmailNotificationService` | `email-notification-service` | `@DecomposableModule(serviceName = "email-notification-service")` |

### 6.2 Calling the Dependency at Runtime

In the monolith, the cross-module call is just a normal method call — both `OrderModule` and `PaymentService` are Spring beans in the same JVM:

```java
// In the monolith — normal method call, same JVM
Payment payment = paymentService.processPayment(customerId, amount, orderId);
```

After decomposition, FractalX generates a NetScope gRPC client that proxies the same call over gRPC to `payment-service`. **You do not write any gRPC code.** The generated client has the same interface as your `PaymentService` class.

### 6.3 What to Put in the Shared Interface

For each cross-module dependency, write the service class as a normal Spring `@Service`. The methods you write become the gRPC interface in generated code.

Rules for methods that cross module boundaries:
- **Return types must be serializable** (primitives, Strings, POJOs with getters/setters). Hibernate-proxied entities work in the monolith but will be serialized as plain objects in the generated services — this is fine.
- **Parameters must be serializable** — same rule.
- **No `void` return types on saga steps** — if the saga orchestrator needs to pass the result to the next step, the method must return a value. For fire-and-forget steps, `void` is fine.
- **Method names drive compensation detection** — name them thoughtfully. See [Section 7.3](#73-step-2--write-the-compensation-methods).

```java
package com.example.payment;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;

    public PaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    // Forward method — this becomes a saga step
    @Transactional
    public Payment processPayment(Long customerId, BigDecimal amount, Long orderId) {
        Payment payment = new Payment(customerId, amount, orderId, "PROCESSED");
        return paymentRepository.save(payment);
    }

    // Compensation — name starts with "cancel" + capitalized forward method name
    @Transactional
    public void cancelProcessPayment(Long customerId, BigDecimal amount, Long orderId) {
        paymentRepository.findByOrderId(orderId).ifPresent(p -> {
            p.setStatus("REFUNDED");
            paymentRepository.save(p);
        });
    }

    // Regular method — also available to other modules as a cross-module call
    public Optional<Payment> findByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId);
    }
}
```

### 6.4 Naming Rules That FractalX Enforces

| What | Rule | Example |
|---|---|---|
| Cross-module field type | Must end in `Service` or `Client` | `PaymentService`, `InventoryClient` |
| Service name in `@DecomposableModule` | Must be kebab-case | `payment-service` |
| Service name must match field type | `PaymentService` → `payment-service` | If mismatched, FractalX cannot wire gRPC clients |
| Compensation method name | Must start with a recognized prefix + capitalized forward method name | `cancel` + `ProcessPayment` = `cancelProcessPayment` |

---

## 7. Writing Distributed Sagas

A **saga** is a sequence of steps across multiple services that must either all succeed or all be rolled back. Use a saga when a business operation spans more than one service's data.

### 7.1 When Do You Need a Saga

Use `@DistributedSaga` when:
- Your method calls two or more cross-module services, AND
- If one of those calls fails, the earlier ones must be undone.

Example: placing an order requires charging a payment AND reserving inventory. If inventory fails, the payment must be refunded. This is a saga.

Do NOT use `@DistributedSaga` when:
- Your method only calls services for reads (no side effects).
- Your method only calls one external service (just use normal error handling with compensation in a try/catch).

### 7.2 Step 1 — Write the Saga Method

The saga method is the entry point. Write it in the `@DecomposableModule` class and annotate it with `@DistributedSaga`.

**Rules for the saga method body:**
1. Do **local work first** (save a PENDING entity, validate inputs, derive IDs).
2. Then call the cross-module services **in the order you want them executed**.
3. Do NOT write code after the cross-module calls that depends on their results — the generator removes those calls and replaces them with an outbox publish. Any code after the calls that references variables set by those calls is also removed.
4. The method should return the locally-created entity (e.g., the PENDING order). Its status will be set to the value of `successStatus` or `failureStatus` by the saga completion callback.
5. **Saga method parameters must be simple, standard Java types.** Use `Long`, `String`, `BigDecimal`, `Integer`, `Boolean`, `UUID`, `LocalDate`, or `List`/`Map` of these types. **Do NOT use custom domain objects** (e.g., `List<OrderItem>`) as parameters — the saga orchestrator generates a typed payload record and only knows how to import standard Java types. Custom types as parameters will cause a compilation error in the generated orchestrator. If you need to pass complex data, use IDs and let the target service look up the details from its own database.

```java
@DistributedSaga(
    sagaId             = "place-order-saga",
    compensationMethod = "cancelPlaceOrder",   // method in THIS class for overall rollback
    timeout            = 30000,                // milliseconds before saga times out
    description        = "Places an order by processing payment and reserving inventory.",
    successStatus      = "CONFIRMED",          // required — status set on Order when saga succeeds
    failureStatus      = "CANCELLED"           // required — status set on Order when saga fails
)
@Transactional
public Order placeOrder(Long customerId, BigDecimal totalAmount) {
    // Parameters: Long, BigDecimal — both standard Java types. GOOD.
    // Do NOT add "List<OrderItem> items" here — OrderItem is a custom type and
    // the orchestrator cannot import it (compile error in generated orchestrator).
    // Instead, pass orderId to the inventory step and let it look up the items itself.

    // 1. LOCAL WORK — do this before cross-module calls
    //    FractalX preserves these statements in the generated code
    Order order = new Order();
    order.setCustomerId(customerId);
    order.setTotalAmount(totalAmount);
    order.setStatus("PENDING");              // always start with PENDING
    order = orderRepository.save(order);

    Long orderId = order.getId();            // extra local var — included in saga payload automatically

    // 2. CROSS-MODULE CALLS — FractalX REPLACES these with outbox.publish() in generated code
    //    In the monolith they run synchronously (same JVM)
    //    After decomposition they run asynchronously via saga orchestrator
    paymentService.processPayment(customerId, totalAmount, orderId);
    inventoryService.reserveStock(orderId);    // inventory service fetches its own items by orderId

    // 3. FINAL STATE — this code runs in monolith but is REMOVED in generated code
    //    The saga completion callback sets status to successStatus/failureStatus instead
    order.setStatus("CONFIRMED");
    return orderRepository.save(order);      // in generated code: just "return order;"
}
```

**What FractalX does to this method in the generated `order-service`:**

FractalX performs a **source-level transformation** (not AOP) that replaces the cross-module calls with an atomic outbox publish.

```java
// GENERATED — what the method looks like after transformation:
@Transactional
public Order placeOrder(Long customerId, BigDecimal totalAmount) {
    Order order = new Order();
    order.setCustomerId(customerId);
    order.setTotalAmount(totalAmount);
    order.setStatus("PENDING");
    order = orderRepository.save(order);

    Long orderId = order.getId();

    // Cross-module calls REPLACED with outbox publish (atomic with the save above)
    java.util.Map<String, Object> sagaPayload = new java.util.LinkedHashMap<>();
    sagaPayload.put("customerId", customerId);
    sagaPayload.put("totalAmount", totalAmount);
    sagaPayload.put("orderId", orderId);
    outboxPublisher.publish("place-order-saga", String.valueOf(customerId), sagaPayload);

    return order;   // returns the PENDING order
}
// 1. DELIVERY: The OutboxPoller forwards the event to the orchestrator (port 8099).
//    Delivery is INSTANT on transaction commit, with a 10s fallback poller for retries.
// 2. ORCHESTRATION: The orchestrator then calls processPayment and reserveStock in order.
// 3. CALLBACK:
//    On success: calls POST /internal/saga-complete/{correlationId}
//    On failure: calls POST /internal/saga-failed/{correlationId}
// 4. ZERO-TOUCH COMPLETION: FractalX 0.5.0 generates a SagaCompletionController that
//    automatically updates order.status to "CONFIRMED" or "CANCELLED" via the OrderRepository.
//    If your service is down, the orchestrator retries this callback every 2 seconds (max 10 attempts).
```

### 7.3 Step 2 — Write the Compensation Methods

For each forward method that can be rolled back, write a compensation method in the **target service class** (not in the saga owner class). Name it using one of these prefixes followed by the capitalized forward method name:

| Prefix | Example |
|---|---|
| `cancel` | `cancelProcessPayment` |
| `rollback` | `rollbackProcessPayment` |
| `undo` | `undoProcessPayment` |
| `revert` | `revertProcessPayment` |
| `release` | `releaseProcessPayment` |
| `refund` | `refundProcessPayment` |

FractalX tries these prefixes in the order shown above. The first one that exists as a method on the target class is used.

```java
// In PaymentService (payment module)

// Forward step
@Transactional
public Payment processPayment(Long customerId, BigDecimal amount, Long orderId) {
    Payment p = new Payment(customerId, amount, orderId, "PROCESSED");
    return paymentRepository.save(p);
}

// Compensation — FractalX will detect this automatically
// Called when a LATER step fails (e.g., inventory reservation fails after payment)
@Transactional
public void cancelProcessPayment(Long customerId, BigDecimal amount, Long orderId) {
    paymentRepository.findByOrderId(orderId).ifPresent(p -> {
        p.setStatus("REFUNDED");
        paymentRepository.save(p);
    });
}
```

```java
// In InventoryService (inventory module)

// Forward step — parameter is Long (standard type), not List<OrderItem> (custom type)
// The service looks up order items from its own local data by orderId.
@Transactional
public void reserveStock(Long orderId) {
    List<InventoryReservation> reservations = reservationRepository.findByOrderId(orderId);
    for (InventoryReservation r : reservations) {
        inventoryRepository.findByProductId(r.getProductId()).ifPresent(inv -> {
            inv.setReserved(inv.getReserved() + r.getQuantity());
            inventoryRepository.save(inv);
        });
    }
}

// Compensation — same parameters as forward method
@Transactional
public void cancelReserveStock(Long orderId) {
    List<InventoryReservation> reservations = reservationRepository.findByOrderId(orderId);
    for (InventoryReservation r : reservations) {
        inventoryRepository.findByProductId(r.getProductId()).ifPresent(inv -> {
            inv.setReserved(inv.getReserved() - r.getQuantity());
            inventoryRepository.save(inv);
        });
    }
}
```

**Compensation rules:**
- Must be in the same class as the forward method.
- Should accept the same parameters as the forward method (FractalX passes the same arguments).
- Must be **idempotent** — the orchestrator may call them more than once if the network fails.
- Should not throw exceptions — wrap in try/catch and log if the compensation itself fails.
- Should be annotated `@Transactional`.

### 7.4 Step 3 — Write the Overall Rollback Method

The `compensationMethod` attribute of `@DistributedSaga` names a method in the **same class** as the saga method. This method is called on the **owner service** to undo its own local state changes when the entire saga is abandoned.

```java
// The overall rollback — called on order-service when the saga terminates in FAILED state.
// Must have the SAME parameters as the @DistributedSaga method (Long, BigDecimal here).
@Transactional
public void cancelPlaceOrder(Long customerId, BigDecimal totalAmount) {
    // Undo the PENDING order that was created in placeOrder()
    orderRepository
        .findTopByCustomerIdAndStatusOrderByCreatedAtDesc(customerId, "PENDING")
        .ifPresent(order -> {
            order.setStatus("CANCELLED");
            orderRepository.save(order);
        });
}
```

Note: this method is distinct from the saga completion callback (`SagaCompletionController`). The overall rollback is explicitly called when the saga itself fails. The completion callback handles the normal success/failure state transitions.

### 7.5 Full Annotated Saga Example

```
Order Module (owns the saga)          Payment Module               Inventory Module
─────────────────────────────         ─────────────────────────    ────────────────────────────

@DistributedSaga(                     processPayment()             reserveStock()
  sagaId="place-order-saga",          cancelProcessPayment()       cancelReserveStock()
  compensationMethod="cancelPlaceOrder")
placeOrder()
  │
  ├── orderRepository.save(PENDING)
  ├── Long orderId = order.getId()
  │
  ├── paymentService.processPayment(customerId, totalAmount, orderId)  ───step 1──►  PaymentService.processPayment()
  │                                                                                   Payment saved, status=PROCESSED
  │
  ├── inventoryService.reserveStock(orderId)  ───step 2──►  InventoryService.reserveStock()
  │                                                          Stock reserved (fetches items by orderId)
  │
  └── return order (status=PENDING)
         │
         │ [all steps succeeded]
         ▼
    /internal/saga-complete/{correlationId}
    → order.status = "CONFIRMED"

         │
         │ [step 2 failed: not enough stock]
         ▼
    COMPENSATING:
    cancelReserveStock()     (skipped — step 2 failed, nothing to undo on inventory)
    cancelProcessPayment()   (called — refunds the payment from step 1)
    cancelPlaceOrder()       (called — marks order as CANCELLED)
    → /internal/saga-failed/{correlationId}
    → order.status = "CANCELLED"
```

### 7.6 Saga Rules Checklist

- [ ] `@DistributedSaga` is on a method inside the `@DecomposableModule` class
- [ ] The method saves a PENDING entity **before** the cross-module calls
- [ ] Cross-module calls use **field references** (e.g., `paymentService.x()`), not local variables
- [ ] All saga method parameters are passed directly to the cross-module calls (so FractalX can map them)
- [ ] **Saga method parameters use only standard Java types** — `Long`, `String`, `BigDecimal`, `Integer`, `Boolean`, `UUID`, `LocalDate`, or `List`/`Map` of these. No custom domain objects.
- [ ] `compensationMethod` names a real method in the same class with the same parameters
- [ ] The overall compensation method exists and is `@Transactional`
- [ ] Each forward step has a compensation method (prefixed with `cancel`, `rollback`, `undo`, `revert`, `release`, or `refund`)
- [ ] Compensation methods are idempotent
- [ ] `@EnableScheduling` is on your main `@SpringBootApplication` class (needed for `OutboxPoller`)
- [ ] Your entity has a `status` String field (so the saga completion controller can update it)

---

## 8. `fractalx-config.yml` — Configuring Your Decomposition

Create `src/main/resources/fractalx-config.yml` in your monolith to control how FractalX generates your services. Every setting is optional — the defaults work out of the box.

```yaml
fractalx:
  # New in 0.5.0: Configurable Java version, service version, and infrastructure ports.
  java-version: 21                            # defaults to version read from source pom.xml, then 21
  initial-service-version: 1.0.0-SNAPSHOT     # version for generated pom.xml artifacts
  spring-boot-version: 3.3.2                  # defaults to version read from source pom.xml

  # Where Jaeger / OTel collector is running (for all services)
  otel:
    endpoint: http://localhost:4317         # default: http://localhost:4317
    jaeger-ui-port: 16686
    jaeger-otlp-port: 4317

  # Where the logger-service is running
  logger:
    url: http://localhost:9099              # default: http://localhost:9099
    port: 9099                              # New in 0.5.0: logger port configurable

  # Where the service registry is running
  registry:
    url: http://localhost:8761              # default: http://localhost:8761
    port: 8761                              # New in 0.5.0: registry port configurable
  
  saga:
    port: 8099                              # New in 0.5.0: saga orchestrator port configurable

  # API Gateway settings
  gateway:
    port: 9999                              # default: 9999
    cors:
      allowed-origins: "http://localhost:3000,http://localhost:4200"

  # Admin service port
  admin:
    port: 9090                              # default: 9090

  # Per-service overrides
  services:

    order-service:
      port: 8081                            # explicit port (overrides @DecomposableModule port)
      datasource:                           # real database (omit to keep H2 in-memory default)
        url: jdbc:mysql://localhost:3306/orders_db
        username: root
        password: my_password

    payment-service:
      port: 8082
      tracing:
        enabled: false                      # disable OTel for this service (default: true)

    inventory-service:
      port: 8083
      # tracing not set → tracing is enabled (default)
```

**Port assignment priority**: `fractalx-config.yml` overrides annotation port. If neither is set, ports are auto-assigned starting from 8081.

**Tracing per service**: set `tracing.enabled: false` under the service name to skip generating `OtelConfig.java` and set `management.tracing.sampling.probability: 0.0` for that service. The API gateway and saga orchestrator always have tracing enabled regardless of this flag.

---

## 9. Running Your Monolith During Development

During development, run your monolith as a normal Spring Boot application. All modules run together in one JVM, cross-module calls are direct Spring bean calls, and there is no gRPC involved yet.

```bash
mvn spring-boot:run
# or
./mvnw spring-boot:run
```

The monolith starts on port 8080 (or whatever you configured). All your `@RestController` endpoints are available directly.

FractalX runtime components that run inside the monolith:
- **`TraceFilter`** — adds `X-Correlation-Id` to every request, populates MDC
- **`FractalLogAppender`** — ships log entries to `logger-service` if it is running (silently skipped if not)
- **`FractalServiceRegistry`** — an in-process service registry bean (not used in monolith mode, just wired)

The monolith does not require the logger-service, registry, or Jaeger to be running. If they are not available, FractalX runtime silently skips the calls.

If you want logs to appear in the admin UI during monolith development, start `logger-service` separately:
```bash
cd fractalx-output/logger-service
mvn spring-boot:run
```

---

## 10. Decomposing — Running `mvn fractalx:decompose`

Once your modules are written and annotated, run:

```bash
mvn fractalx:decompose
```

FractalX shows an animated progress dashboard and then prints a summary:

```
  Microservices
    order-service        http://localhost:8081
    payment-service      http://localhost:8082
    inventory-service    http://localhost:8083

  Infrastructure
    fractalx-gateway     http://localhost:9999
    fractalx-admin       http://localhost:9090
    fractalx-registry    http://localhost:8761

  Get started
    cd fractalx-output
    ./start-all.sh
    docker-compose up -d

  Done in 4.2s
```

**What happens:**
1. FractalX scans your `src/main/java` for `@DecomposableModule` classes.
2. For each module, it copies the Java files from the module's package into a new Spring Boot project.
3. It transforms the code: removes `@DecomposableModule`, decouples cross-module JPA references, generates gRPC clients, rewrites saga methods to use the outbox.
4. It generates all infrastructure services (gateway, registry, admin, logger, orchestrator).
5. It writes `docker-compose.yml` and shell scripts.

**To re-decompose after changes:**

```bash
mvn fractalx:decompose
```

The output directory is overwritten each time. Do not edit files inside `fractalx-output/` directly — edit your monolith and re-decompose.

**Plugin flags:**

```bash
# Just analyze, do not generate (shows which modules were found)
mvn fractalx:decompose -Dfractalx.generate=false

# Output to a different directory
mvn fractalx:decompose -Dfractalx.outputDirectory=/path/to/output

# Skip entirely (useful in CI)
mvn fractalx:decompose -Dfractalx.skip=true
```

---

## 11. Running the Generated Microservices

### Option A — Shell scripts (local development)

```bash
cd fractalx-output
./start-all.sh      # starts all services in order: registry → services → orchestrator → gateway
# to stop:
./stop-all.sh
```

Logs for each service are written to `fractalx-output/<service-name>.log`.

### Option B — Docker Compose

```bash
cd fractalx-output
docker-compose up -d          # starts everything including Jaeger
docker-compose ps             # check status
docker-compose logs -f order-service   # tail logs
docker-compose down           # stop
```

### Option C — Start services individually

```bash
# Terminal 1 — registry must start first
cd fractalx-output/fractalx-registry
mvn spring-boot:run

# Terminal 2
cd fractalx-output/order-service
mvn spring-boot:run

# Terminal 3
cd fractalx-output/payment-service
mvn spring-boot:run

# etc.
```

### Health Checks

Every generated service has Spring Boot Actuator:

```bash
curl http://localhost:8081/actuator/health   # order-service
curl http://localhost:8082/actuator/health   # payment-service
curl http://localhost:9999/actuator/health   # gateway
curl http://localhost:9090/actuator/health   # admin
```

### Admin UI

Open `http://localhost:9090` in your browser. Log in with the credentials from `@AdminEnabled`.

The admin UI shows:
- **Services** tab — health status of all running services
- **Traces** tab — distributed traces from Jaeger (need Jaeger running)
- **Logs** tab — all logs from all services aggregated
- **Sagas** tab — list of saga instances with status and step progress
- **Topology** tab — visual dependency map between services

---

## 12. What Gets Generated — A Mental Model

### 12.1 Per-Module Service

For each `@DecomposableModule` you get a full Spring Boot project:

```
order-service/
  pom.xml                               — Spring Boot 3 + NetScope + fractalx-runtime deps
  Dockerfile                            — multi-stage Maven build
  src/main/java/
    org/fractalx/generated/orderservice/
      OrderServiceApplication.java      — @SpringBootApplication entry point
      OtelConfig.java                   — OTel SDK configuration
      CorrelationTracingConfig.java     — tags spans with correlation.id
      TracingExclusionConfig.java       — suppresses actuator/scheduler spans
      NetScopeResilienceConfig.java     — Resilience4j circuit breaker beans
      outbox/
        OutboxEvent.java                — JPA entity for transactional outbox
        OutboxRepository.java
        OutboxPublisher.java            — call inside @Transactional methods
        OutboxPoller.java               — @Scheduled, forwards events to orchestrator
      saga/
        SagaCompletionController.java  — receives /internal/saga-complete and /saga-failed
      client/
        PaymentServiceClient.java       — @NetScopeClient interface (gRPC proxy)
        InventoryServiceClient.java
      [your module's Java files — copied and transformed from monolith]
        OrderModule.java               — saga method replaced with outbox.publish()
        OrderService.java
        Order.java
        OrderRepository.java
        OrderController.java
  src/main/resources/
    application.yml
    application-dev.yml
    application-docker.yml
    logback-spring.xml                  — pattern includes %X{correlationId}
    db/migration/
      V1__initial_schema.sql           — Flyway baseline
  DATA_README.md                        — documents data ownership and cross-module deps
```

### 12.2 API Gateway (port 9999)

Spring Cloud Gateway (WebFlux). Routes every `/api/<service>/**` request to the right upstream. Includes: CORS, security (disabled by default, activate via env vars), rate limiting, circuit breakers, request/response logging, distributed tracing.

### 12.3 Saga Orchestrator (port 8099)

Only generated when at least one `@DistributedSaga` is found. Receives saga start events from the outbox poller, executes steps in order via NetScope gRPC, runs compensation on failure, calls back the owner service on completion.

REST endpoints:
- `POST /saga/{sagaId}/start` — trigger a saga (payload is JSON from outbox)
- `GET /saga/{sagaId}/status/{correlationId}` — check saga status
- `GET /saga/all` — list all saga instances

**Saga completion callbacks with automatic retry:**

When all saga steps succeed (or after compensation on failure), the orchestrator calls back the owner service to update the entity status (`CONFIRMED` or `CANCELLED`). If the owner service is temporarily unavailable, the callback is automatically retried:

- Retried every **2 seconds** by a background `@Scheduled` poller on the orchestrator.
- Up to a maximum of **10 attempts**.
- On each failed attempt the retry count is persisted to the `saga_instance` table.
- When the owner comes back online, the next poll delivers the notification automatically.
- If all 10 attempts fail, the saga is logged as a **dead-letter** and the entity remains in `PENDING` status until manual intervention.

```
Owner service down?
  └─ Orchestrator logs warning, increments retry count, tries again in 2s
  └─ When owner comes back: next poll delivers notification (≤2s delay)
  └─ After 10 failures: dead-letter logged — check orchestrator logs for correlationId
```

To check if there are stuck notifications:
```bash
# Query the orchestrator's saga_instance table
SELECT correlation_id, saga_id, status, notification_retry_count, last_notification_attempt
FROM saga_instance
WHERE owner_notified = false AND status IN ('DONE', 'FAILED');
```

### 12.4 Admin UI (port 9090)

Single-page HTML application served by a Spring Boot backend. Proxies Jaeger, logger-service, and all registered services. Requires only a browser.

### 12.5 Logger Service (port 9099)

Receives log entries from all services via `FractalLogAppender`. Stores last 5000 entries in memory. Queried by the Admin UI.

### 12.6 Registry (port 8761)

Custom FractalX service registry. Each generated service registers itself on startup. Used by the Admin UI to display service health. Does not affect NetScope gRPC routing (which uses static config).

---

## 13. Logging — How to Log Correctly

Use standard SLF4J logging. FractalX provides the rest automatically.

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    public Order createOrder(Long customerId) {
        log.info("Creating order for customer {}", customerId);     // will include correlationId automatically
        // ...
        log.debug("Order saved: {}", order.getId());
        log.warn("Customer {} has existing pending order", customerId);
        log.error("Failed to save order", exception);
    }
}
```

**You do not need to manage correlation IDs in log statements.** The generated `logback-spring.xml` pattern includes `%X{correlationId}`, which is populated by `TraceFilter` for HTTP requests and by `NetScopeContextInterceptor` for gRPC calls. Every log line automatically carries the correlation ID.

Every log line from every service is automatically shipped to `logger-service` and visible in the Admin UI Logs tab.

**Log levels** — use them consistently:
- `ERROR` — service cannot continue, requires intervention
- `WARN` — unexpected but recoverable situation
- `INFO` — normal significant events (order created, payment processed)
- `DEBUG` — detailed flow tracing (for development)

---

## 14. Distributed Tracing — What You Get For Free

Every HTTP request and gRPC call is automatically traced. You do not write any tracing code.

What you get without writing a single tracing line:
- Every HTTP request creates a span in Jaeger automatically (via Micrometer Observation auto-configuration)
- Every NetScope gRPC call creates a child span
- The `traceparent` W3C header is propagated through all hops
- Each span is tagged with `correlation.id` (the FractalX correlation ID) — searchable in Jaeger
- Actuator endpoints (`/actuator/health`, `/actuator/metrics`, etc.) and scheduled task spans are excluded from Jaeger to avoid noise

View traces at `http://localhost:16686` (Jaeger UI, requires Jaeger running).

Start Jaeger locally:

```bash
docker run -d --name jaeger \
  -p 4317:4317 \
  -p 16686:16686 \
  jaegertracing/all-in-one:latest
```

### 14.1 Disabling Tracing for a Specific Service

If a service is very high-traffic and you do not want traces for it (e.g., a polling service), disable tracing in `fractalx-config.yml`:

```yaml
fractalx:
  services:
    inventory-service:
      tracing:
        enabled: false
```

This removes all OTel config classes from that service and sets sampling probability to 0. The API gateway and saga orchestrator are always traced regardless of this setting.

---

## 15. API Gateway — How Routes Are Mapped

The gateway routes incoming requests based on the URL path prefix. The routing rule is:

**`/api/<service-name-without-service-suffix>/**`** → forwarded to that service.

| You call (via gateway :9999) | Forwarded to |
|---|---|
| `GET http://localhost:9999/api/orders/42` | `GET http://localhost:8081/api/orders/42` |
| `POST http://localhost:9999/api/payments` | `POST http://localhost:8082/api/payments` |
| `GET http://localhost:9999/api/inventory` | `GET http://localhost:8083/api/inventory` |

**Your controller must use the same path that the gateway routes to.** Since `StripPrefix=0` is configured, the path is forwarded unchanged. So:

```java
// In order-service:
@RequestMapping("/api/orders")   // correct — matches /api/orders/** route
public class OrderController { ... }

// WRONG:
@RequestMapping("/orders")       // wrong — gateway sends /api/orders, service expects /orders → 404
```

The gateway is the single entry point for all external clients. Internal service-to-service calls use NetScope gRPC (not the gateway).

---

## 16. Data — What Happens to Your Database

### 16.1 Each Service Gets Its Own Database

After decomposition, each service has its own database. In dev mode (default), this is an H2 in-memory database. Each service's database only contains the tables for that module's entities.

There are no shared tables. Services do not share a database.

### 16.2 Configuring a Real Database

In `fractalx-config.yml`:

```yaml
fractalx:
  services:
    order-service:
      datasource:
        url: jdbc:mysql://localhost:3306/orders_db
        username: root
        password: secret
        # driver-class-name is auto-detected from URL (mysql → com.mysql.cj.jdbc.Driver)
```

FractalX will:
1. Add the JDBC URL and credentials to the generated `application.yml`
2. Add the MySQL or PostgreSQL driver dependency to the generated `pom.xml`

Supported databases: **MySQL** (detected from `jdbc:mysql://`) and **PostgreSQL** (detected from `jdbc:postgresql://`). Other databases: add the driver manually to the generated `pom.xml`.

### 16.3 Flyway Migrations

Each generated service has a Flyway scaffold. A `V1__initial_schema.sql` is generated from the module's JPA entities.

**You must review and possibly edit `V1__initial_schema.sql`** before running in production — the generated SQL is a best-effort inference from the entity annotations and may need adjustment for your database dialect or constraints.

Location: `<service-name>/src/main/resources/db/migration/V1__initial_schema.sql`

To add more migrations: create `V2__add_column.sql`, `V3__add_index.sql`, etc. in the same directory of the generated service. But remember — if you re-decompose, the generated files are overwritten. Keep your custom migrations in source control separately or in the monolith's resources.

### 16.4 Cross-Module Data Rules

After decomposition, each service can only read and write its own tables. Cross-module data access happens via:

1. **NetScope gRPC calls** — call the owning service's method to get data from another module.
2. **Reference IDs only** — store `Long customerId` not `Customer customer`.
3. **No distributed joins** — if you need data from multiple services in one response, the calling service must make multiple gRPC calls and assemble the response.

---

## 17. Resilience — What You Get For Free

For every cross-module dependency in your module, FractalX generates Resilience4j configuration automatically.

Each dependency gets:
- **Circuit Breaker** — opens after 50% failure rate (over last 10 calls), waits 30 seconds before trying again
- **Retry** — retries up to 3 times with exponential backoff (100ms, 200ms, 400ms)
- **Time Limiter** — fails the call if it takes more than 2 seconds

This is pre-configured in the generated `application.yml` and `NetScopeResilienceConfig.java`. You can tune the values by editing those files in the generated service (but remember, re-decomposing overwrites them).

---

## 18. Inter-Service Communication (NetScope gRPC)

After decomposition, services communicate via **NetScope**, FractalX's gRPC abstraction. You do not write gRPC code — FractalX generates it from your existing Java interfaces.

**gRPC ports** — every service exposes a gRPC port at `HTTP port + 10000`:

| Service | HTTP | gRPC |
|---|---|---|
| order-service | 8081 | 18081 |
| payment-service | 8082 | 18082 |
| inventory-service | 8083 | 18083 |

The gRPC port is configured automatically in `application-dev.yml` and `application-docker.yml`.

**Correlation IDs over gRPC** — the correlation ID flows automatically through every gRPC call via the `x-correlation-id` metadata header. You do not need to do anything. Every log entry on every downstream service will carry the same correlation ID as the original HTTP request.

---

## 19. Complete Working Example — E-Commerce Monolith

Here is a complete, working modular monolith that FractalX will fully decompose into three microservices with saga orchestration.

### Directory Structure

```
src/main/java/com/example/
  MonolithApplication.java
  order/
    OrderModule.java      ← @DecomposableModule
    OrderService.java
    Order.java
    OrderItem.java
    OrderRepository.java
    OrderController.java
    dto/
      PlaceOrderRequest.java
  payment/
    PaymentModule.java    ← @DecomposableModule
    PaymentService.java
    Payment.java
    PaymentRepository.java
    PaymentController.java
  inventory/
    InventoryModule.java  ← @DecomposableModule
    InventoryService.java
    InventoryItem.java
    InventoryItemRepository.java
    InventoryController.java

src/main/resources/
  application.yml
  fractalx-config.yml
```

### `MonolithApplication.java`

```java
package com.example;

import org.fractalx.annotations.AdminEnabled;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@AdminEnabled(port = 9090, username = "admin", password = "admin123")
public class MonolithApplication {
    public static void main(String[] args) {
        SpringApplication.run(MonolithApplication.class, args);
    }
}
```

### `fractalx-config.yml`

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
```

### `order/Order.java`

```java
package com.example.order;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long customerId;           // ID only — no @ManyToOne

    @Column(nullable = false)
    private BigDecimal totalAmount;

    @Column(nullable = false)
    private String status;             // PENDING / CONFIRMED / CANCELLED

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); }

    // getters and setters
    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

### `order/OrderRepository.java`

```java
package com.example.order;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByCustomerId(Long customerId);
    Optional<Order> findTopByCustomerIdAndStatusOrderByCreatedAtDesc(Long customerId, String status);
}
```

### `order/OrderService.java`

```java
package com.example.order;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public Order findById(Long orderId) {
        return orderRepository.findById(orderId)
            .orElseThrow(() -> new EntityNotFoundException("Order not found: " + orderId));
    }

    public List<Order> findByCustomer(Long customerId) {
        return orderRepository.findByCustomerId(customerId);
    }
}
```

### `order/OrderModule.java`

```java
package com.example.order;

import com.example.payment.PaymentService;
import com.example.inventory.InventoryService;
import org.fractalx.annotations.DecomposableModule;
import org.fractalx.annotations.DistributedSaga;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;

@DecomposableModule(
    serviceName  = "order-service",
    port         = 8081,
    ownedSchemas = {"orders"}
)
@Service
public class OrderModule {

    // Cross-module deps: PaymentService and InventoryService belong to other modules.
    // Their type names end in "Service" → FractalX detects them as gRPC dependencies.
    private final PaymentService   paymentService;   // com.example.payment — cross-module ✓
    private final InventoryService inventoryService; // com.example.inventory — cross-module ✓

    // Local dep: OrderRepository ends in "Repository" — NOT detected as cross-module.
    // NOTE: OrderService (same module) is NOT injected here — injecting it would cause
    // FractalX to falsely generate a gRPC client for order-service calling itself.
    private final OrderRepository  orderRepository;

    public OrderModule(PaymentService   paymentService,
                       InventoryService inventoryService,
                       OrderRepository  orderRepository) {
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
    public Order placeOrder(Long customerId, BigDecimal totalAmount) {
        // Local work — preserved in generated code
        Order order = new Order();
        order.setCustomerId(customerId);
        order.setTotalAmount(totalAmount);
        order.setStatus("PENDING");
        order = orderRepository.save(order);
        Long orderId = order.getId();   // extra local var — included in saga payload automatically

        // Cross-module calls — REPLACED by outboxPublisher.publish() in generated code.
        // Parameters are simple Java types (Long, BigDecimal) — required for orchestrator.
        paymentService.processPayment(customerId, totalAmount, orderId);
        inventoryService.reserveStock(orderId);

        // Removed in generated code — saga completion callback handles this instead
        order.setStatus("CONFIRMED");
        return orderRepository.save(order);
    }

    @Transactional
    public void cancelPlaceOrder(Long customerId, BigDecimal totalAmount) {
        orderRepository
            .findTopByCustomerIdAndStatusOrderByCreatedAtDesc(customerId, "PENDING")
            .ifPresent(o -> {
                o.setStatus("CANCELLED");
                orderRepository.save(o);
            });
    }
}
```

### `order/OrderController.java`

```java
package com.example.order;

import com.example.order.dto.PlaceOrderRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderModule  orderModule;
    private final OrderService orderService;

    public OrderController(OrderModule orderModule, OrderService orderService) {
        this.orderModule  = orderModule;
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<Order> placeOrder(@RequestBody PlaceOrderRequest req) {
        return ResponseEntity.ok(
            orderModule.placeOrder(req.getCustomerId(), req.getTotalAmount())
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> getOrder(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.findById(id));
    }

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<Order>> getByCustomer(@PathVariable Long customerId) {
        return ResponseEntity.ok(orderService.findByCustomer(customerId));
    }
}
```

### `payment/Payment.java`

```java
package com.example.payment;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long customerId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private String status;    // PROCESSED / REFUNDED

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); }

    public Payment() {}
    public Payment(Long customerId, BigDecimal amount, Long orderId, String status) {
        this.customerId = customerId;
        this.amount = amount;
        this.orderId = orderId;
        this.status = status;
    }
    // getters and setters
    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public BigDecimal getAmount() { return amount; }
    public Long getOrderId() { return orderId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
```

### `payment/PaymentRepository.java`

```java
package com.example.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderId(Long orderId);
}
```

### `payment/PaymentModule.java`

```java
package com.example.payment;

import org.fractalx.annotations.DecomposableModule;
import org.springframework.stereotype.Service;

@DecomposableModule(
    serviceName  = "payment-service",
    port         = 8082,
    ownedSchemas = {"payments"}
)
@Service
public class PaymentModule {
    // payment-service has no cross-module dependencies.
    // No fields here — this class is the module boundary marker.
    // The actual business logic lives in PaymentService.java.
    //
    // DO NOT inject PaymentService here — PaymentService is in the same package
    // (com.example.payment), and its name ends in "Service". Injecting it would
    // cause FractalX to generate a gRPC client for payment-service calling itself.
}
```

### `payment/PaymentService.java`

```java
package com.example.payment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;

    public PaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public Payment processPayment(Long customerId, BigDecimal amount, Long orderId) {
        Payment payment = new Payment(customerId, amount, orderId, "PROCESSED");
        return paymentRepository.save(payment);
    }

    // Compensation — auto-detected by SagaAnalyzer (prefix "cancel" + "ProcessPayment")
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
import org.springframework.stereotype.Service;

@DecomposableModule(
    serviceName  = "inventory-service",
    port         = 8083,
    ownedSchemas = {"inventory_items"}
)
@Service
public class InventoryModule {
    // inventory-service has no cross-module dependencies.
    // No fields here — this class is the module boundary marker.
    // The actual business logic lives in InventoryService.java.
    //
    // DO NOT inject InventoryService here — InventoryService is in the same package
    // and its name ends in "Service". Injecting it would cause FractalX to generate
    // a gRPC client for inventory-service calling itself.
}
```

### `inventory/InventoryService.java`

```java
package com.example.inventory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {

    private final InventoryItemRepository inventoryItemRepository;

    public InventoryService(InventoryItemRepository inventoryItemRepository) {
        this.inventoryItemRepository = inventoryItemRepository;
    }

    @Transactional
    public void reserveStock(Long orderId) {
        // reserve stock logic
    }

    // Compensation — auto-detected ("cancel" + "ReserveStock")
    @Transactional
    public void cancelReserveStock(Long orderId) {
        // release reserved stock
    }
}
```

### Run decomposition

```bash
mvn fractalx:decompose
cd fractalx-output
./start-all.sh
```

Test it:

```bash
# Place an order
curl -X POST http://localhost:9999/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId": 1, "totalAmount": 99.99}'

# Check saga status
curl http://localhost:8099/saga/place-order-saga/status/{correlationId}

# View logs
open http://localhost:9090
```

---

## 20. Dos and Don'ts — Full Rules Reference

### Structure

| Do | Don't |
|---|---|
| One package per module | Mix two modules' classes in one package |
| One `@DecomposableModule` per module | Annotate multiple classes with the same `serviceName` |
| All module classes under their own package | Put module classes in the root package |
| Use `@RequestMapping("/api/<module>/")` | Use paths that don't match gateway routing |
| Use `@EnableScheduling` on your main class when using sagas | Forget it — `OutboxPoller` uses `@Scheduled` |

### Dependencies

| Do | Don't |
|---|---|
| Inject cross-module deps with types ending in `Service` or `Client` — from **other** modules | Use types ending in `Manager`, `Handler`, `Helper` for cross-module calls |
| Keep same-module helper classes named `*Handler`, `*Operations`, `*Manager` (not `*Service`) when injected into the boundary class | Inject a same-module `*Service` class into the `@DecomposableModule` constructor — it will be falsely treated as a cross-module dep |
| Match `ServiceName` → `service-name` exactly | Let the service name and field type name diverge |
| Use constructor injection | Use `@Autowired` field injection |

### Entities

| Do | Don't |
|---|---|
| Store `Long foreignEntityId` for cross-module references | Use `@ManyToOne` / `@OneToMany` across modules |
| Include a `status` String field on saga-involved entities | Forget the status field — saga completion can't update it |
| One entity class per module's entity | Share entity classes across modules |

### Sagas

| Do | Don't |
|---|---|
| Write the saga method in the `@DecomposableModule` class | Write it in a nested service class |
| Call cross-module fields directly in the saga method body | Delegate cross-module calls to private helper methods |
| Use only simple Java types as saga parameters (`Long`, `String`, `BigDecimal`, `Integer`, `Boolean`, `UUID`, `LocalDate`) | Use custom domain objects (`List<OrderItem>`, your entity classes) as saga parameters — the orchestrator cannot import them |
| Pass all necessary data as saga method parameters or local vars computed before the calls | Compute values from cross-service call results (they'll be removed) |
| Write compensation methods with recognized prefixes (`cancel*`, `rollback*`, `undo*`) | Use arbitrary names for compensation methods |
| Make compensation methods idempotent | Write compensations that fail if called twice |
| Save a PENDING entity before any cross-module calls | Try to create entities from cross-service call results |
| Include `@Transactional` on the saga method and compensations | Forget `@Transactional` — outbox write must be atomic with entity save |

---

## 21. Annotations Quick Reference

### `@DecomposableModule`

```java
@DecomposableModule(
    serviceName          = "order-service",   // REQUIRED. Kebab-case. Unique.
    port                 = 8081,              // 0 = auto-assign. Overridable in fractalx-config.yml.
    independentDeployment = true,             // default true. false = skip generation.
    ownedSchemas         = {"orders"}         // informational only
)
```

### `@DistributedSaga`

```java
@DistributedSaga(
    sagaId             = "place-order-saga",  // REQUIRED. Unique. Kebab-case.
    compensationMethod = "cancelPlaceOrder",  // method in same class for overall rollback
    timeout            = 30000,               // milliseconds. Default 30000.
    steps              = {"step1", "step2"},  // optional hints for documentation
    description        = "Human description" // shown in admin UI
)
```

### `@ServiceBoundary`

```java
@ServiceBoundary(
    allowedCallers = {"order-service"},   // who can call this class
    strict         = true                 // whether verifier fails on violation
)
```

### `@AdminEnabled`

```java
@AdminEnabled(
    port       = 9090,       // default 9090
    username   = "admin",    // default "admin"
    password   = "admin123", // change in production
    monitoring = true,       // default true
    logging    = true        // default true
)
```

---

## 22. Troubleshooting

### "No @DecomposableModule classes found"

FractalX found no classes to decompose.

- Check that `@DecomposableModule` is imported from `org.fractalx.annotations.DecomposableModule`.
- The annotated class must be a top-level class (not inner, not interface, not abstract).
- The file must be under `src/main/java` (the plugin's `sourceDirectory` default).

### Gateway returns 404 for my endpoint

The path in your controller does not match what the gateway routes.

- Controller must use `@RequestMapping("/api/<service-base>/**")`.
- The service base is the service name without the `-service` suffix: `order-service` → `/api/orders` or `/api/order`.
- Both singular and plural are routed by the gateway, but your controller only handles what you mapped.

### Saga steps not detected

FractalX could not find cross-module calls in the saga method.

- The calls must be on **field references** directly in the method body: `paymentService.processPayment(...)`.
- The field type must end in `Service` or `Client`.
- The calls must be in the annotated method body, not in a helper method called from there.

### Compensation method not auto-detected

The generator logs: `No compensation method found for PaymentService.processPayment`.

- The compensation method must be in the **same class** as the forward method (e.g., `PaymentService`).
- Name must be one of: `cancel`, `rollback`, `undo`, `revert`, `release`, `refund` + capitalized forward method name.
- Example: forward = `processPayment` → compensation must be `cancelProcessPayment` (or `rollbackProcessPayment`, etc.).

### Outbox events not forwarding / saga never starts

The saga orchestrator is not receiving events.

- Check `OutboxPoller` logs: search for `Forwarded outbox event` or `Failed to forward`.
- Verify the orchestrator URL in `application.yml`: `fractalx.saga.orchestrator.url` must point to where the orchestrator is running.
- Check the `fractalx_outbox` table: if `published=false` and `retry_count=5`, the event gave up — check the `OutboxPoller` error logs for the HTTP call failure reason.

### Service fails to start: "OutboxPublisher required a bean"

The `OutboxPublisher` bean could not be found.

- Ensure `OutboxPoller`, `OutboxPublisher`, and `OutboxEvent` were generated in the service's `outbox/` package.
- This happens when the service has cross-module dependencies. Re-run `mvn fractalx:decompose`.

### Cross-module imports cause compilation errors in generated code

A class from module A was imported in module B's generated code.

- In your monolith, you may have imported `com.example.payment.Payment` directly in `OrderService.java`.
- After decomposition, `Payment` is not in `order-service`'s source tree.
- Fix: replace return types / parameters that cross module boundaries with IDs (`Long paymentId`) or with a DTO defined in your own module's `dto/` package.

### "correlationId" is empty in logs

The correlation ID is not being set.

- For HTTP requests: `TraceFilter` runs at `HIGHEST_PRECEDENCE`. It should always fire. Check that `fractalx-runtime` is on the classpath.
- For gRPC calls: `NetScopeContextInterceptor` must be applied. It is wired automatically by `NetScopeGrpcInterceptorConfigurer`. Check that `fractalx-runtime` is on the classpath.
- Check `logback-spring.xml` exists in `src/main/resources` and includes `%X{correlationId}` in the pattern.

### Entity stays PENDING after saga completes

The owner service was not reachable when the orchestrator tried to send the completion callback.

- Check the orchestrator logs for `Failed to notify owner service of saga completion` or `saga notification dead-letter`.
- If the owner service is now running, the orchestrator will retry automatically every 2 seconds (up to 10 attempts).
- If 10 retries were exhausted (look for `dead-letter` in orchestrator logs), the notification will not retry further. Manually re-trigger via:
  ```bash
  # Re-trigger the completion callback manually
  curl -X POST http://localhost:8081/internal/saga-complete/{correlationId} \
    -H "Content-Type: application/json" \
    -d '{"orderId": 42}'   # use the actual saga payload
  ```
- To find affected sagas, query the orchestrator database:
  ```sql
  SELECT * FROM saga_instance WHERE owner_notified = false AND status IN ('DONE','FAILED');
  ```

### Service failing to start because it is calling itself via gRPC

Symptoms: a generated service tries to connect to a gRPC port of itself at startup, or you see a bean named `<ServiceName>ServiceClient` inside the service for that service.

Cause: you injected a same-module `*Service` class into the `@DecomposableModule` constructor. For example, `PaymentModule` with `private final PaymentService paymentService` — `PaymentService` ends in `Service`, so FractalX generates a gRPC client for it, even though it is in the same module.

Fix: remove same-module `*Service` fields from the `@DecomposableModule` class constructor. Inject `*Repository` types instead, or rename the helper class to use a non-`Service` suffix (`*Handler`, `*Operations`, `*Manager`, `*Processor`).

### Orchestrator fails to compile — "cannot find symbol" on a custom type

Cause: a saga method parameter or return type is a custom domain class (e.g., `List<OrderItem>`). The orchestrator generates a typed payload record that references `OrderItem`, but `OrderItem` is not a known Java type so no import is generated.

Fix: change saga method parameters to use only standard Java types (`Long`, `String`, `BigDecimal`, `Integer`, `Boolean`, `UUID`, `LocalDate`). Instead of passing `List<OrderItem>`, pass `Long orderId` and let the target service retrieve the items from its own database using that ID.

### Re-decomposing overwrites my manual edits

Do not edit files inside `fractalx-output/` directly. Everything there is regenerated on each `mvn fractalx:decompose`.

If you need to customize generated code:
- Make the change in your monolith source (which FractalX copies and transforms)
- Add a post-processing step to `fractalx-config.yml` if the feature is supported
- Accept that certain infrastructure classes (pom.xml, application.yml) are always regenerated from config
