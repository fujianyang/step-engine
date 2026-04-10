# StepEngine

StepEngine is a lightweight workflow engine for short-running, idempotent workflows.

It provides a simple and explicit way to orchestrate multi-step operations with retry and rollback support — without introducing external infrastructure or workflow persistence.

---

## ✨ Features

- Sequential and parallel step execution
- Exception-driven failure model
- Optional rollback (compensation) support
- Pluggable retry policies (e.g. exponential backoff with jitter)
- Optional per-step retry policy and executor override
- Parallel execution via virtual threads (Java 21), with optional custom executor
- Minimal dependency footprint (SLF4J only)

---

## 🧭 When to use StepEngine

StepEngine is a good fit when:

- Workflows are **short-running**
- Steps are **idempotent** or safe to retry
- You want orchestration **inside a service/application**
- You need **explicit control** over retries and failures
- You want to avoid heavy workflow infrastructure

---

## 🚫 When NOT to use StepEngine

StepEngine is not intended for:

- Long-running workflows (minutes, hours, days)
- Durable execution / state persistence
- Human-in-the-loop workflows
- Distributed worker orchestration
- Scheduling / cron workflows

If you need durable execution and workflow state persistence, consider platforms like [Temporal](https://temporal.io/).

---

## 🧩 Core Concepts

### Step

A `Step` represents a unit of work in a workflow.

Each step defines:
- a name
- a forward handler (required)
- an optional rollback handler

```java
Step<MyContext> step = Step.<MyContext>builder()
    .name("create-order")
    .forward(ctx -> {
        ctx.setOrderId(orderService.create(ctx.request()));
    })
    .rollback(ctx -> {
        if (ctx.getOrderId() != null) {
            orderService.delete(ctx.getOrderId());
        }
    })
    .build();
```

---

### Context

The workflow operates on a context object that carries state across steps.

Any data object can be used as the context — it does not need to implement a specific interface.

Typically, the context contains:
- input data
- intermediate state
- final result

---

### StepEngine

A `StepEngine` executes an ordered list of steps.

```java
StepEngine<MyContext> engine = StepEngine.<MyContext>builder()
    .step("validate", ctx -> {
        if (ctx.request() == null) {
            throw new IllegalArgumentException("request must not be null");
        }
    })
    .step("create-order",
        ctx -> ctx.setOrderId(orderService.create(ctx.request())),
        ctx -> orderService.delete(ctx.getOrderId())
    )
    .build();

engine.execute(context);
```

---

## ⚠️ Failure Model

StepEngine uses **exceptions as the single failure mechanism**.

### ServiceException (business failures)

Use `ServiceException` (or subclasses) for expected, client-visible failures:

```java
throw new InvalidRequestException("INVALID_REQUEST", "deviceId is required");
```

Behavior:
- execution stops immediately
- rollback is triggered
- exception is rethrown as-is
- never retried

### Other Exceptions (technical failures)

Examples:
- IO errors
- timeouts
- downstream failures

Behavior:
- evaluated by retry policy
- retried if allowed
- if retries are exhausted:
  - rollback is triggered

---

## 🔁 Retry

Retry behavior is controlled by a `RetryPolicy`.

### Example: Exponential Backoff

```java
RetryPolicy retryPolicy = ExponentialBackoffRetryPolicy.builder()
    .maxAttempts(3)
    .initialDelay(Duration.ofMillis(100))
    .maxDelay(Duration.ofSeconds(2))
    .retryOn(t -> true)
    .build();
```

Apply it to the engine:

```java
StepEngine<MyContext> engine = StepEngine.<MyContext>builder()
    .step(...)
    .retryPolicy(retryPolicy)
    .build();
```

---

## ⚡ Parallel Steps

When steps are independent and don't depend on each other's output, they can be executed concurrently using a `ParallelGroup`.

```java
StepEngine<MyContext> engine = StepEngine.<MyContext>builder()
    .step("validate", ctx -> { ... })
    .parallel(
        ParallelGroup.<MyContext>builder()
            .step(Step.of("call-service-A", ctx -> { ... }, ctx -> { ... }))
            .step(Step.of("call-service-B", ctx -> { ... }, ctx -> { ... }))
            .step(Step.of("call-service-C", ctx -> { ... }))
            .build()
    )
    .step("persist", ctx -> { ... })
    .build();
```

By default, parallel steps run on Java 21 virtual threads. A custom `Executor` can be set at the group level or per step:

```java
ParallelGroup.<MyContext>builder()
    .step(Step.<MyContext>builder()
        .name("rate-limited-call")
        .execute(ctx -> { ... })
        .executor(rateLimitedExecutor)
        .build())
    .step(Step.of("fast-call", ctx -> { ... }))
    .executor(groupExecutor)  // default for steps without their own executor
    .build()
```

Executor resolution order: step → group → virtual threads.

### Failure behavior

- **Wait-all**: when a step fails, other running steps finish their current attempt before the group fails
- **ServiceException**: dooms the entire group — sibling steps stop retrying immediately
- **Regular exceptions**: each step retries independently per its own retry policy
- **Multiple failures**: the first exception is primary, others are attached as suppressed

### Rollback in parallel groups

If a parallel group fails, all successfully completed steps in the group are rolled back in parallel. Then any previously completed sequential steps are rolled back in reverse order.

### Context thread-safety

When using parallel steps, the context object must be safe for concurrent access. This is the caller's responsibility.

---

## 🔄 Rollback

If a step fails, all previously completed steps that support rollback are executed in reverse order.

```text
Step1 → Step2 → Step3 (fails)
Rollback: Step2 → Step1
```

Rollback behavior:
- best-effort
- rollback failures are attached as suppressed exceptions
- original failure is preserved

---

## 📦 Example

```java
StepEngine<MyContext> engine = StepEngine.<MyContext>builder()
    .step("validate", ctx -> {
        if (ctx.request() == null) {
            throw new InvalidRequestException("INVALID_REQUEST", "deviceId is required");
        }
    })
    .step("call-downstream", ctx -> {
        ctx.setResult(callService(ctx.request()));
    })
    .step("persist",
        ctx -> save(ctx)
    )
    .retryPolicy(
        ExponentialBackoffRetryPolicy.builder()
            .maxAttempts(3)
            .initialDelay(Duration.ofMillis(200))
            .build()
    )
    .build();

engine.execute(context);
```

---

## 🧭 Design Principles

StepEngine is designed to be:

- **simple** — minimal concepts, easy to understand
- **explicit** — clear execution and failure behavior
- **exception-driven** — no result wrappers
- **fail-fast** — failures stop execution immediately
- **practical** — built for real-world service workflows

---

## ⚖️ Intellectual Property

This project is an original implementation created independently and does not contain proprietary code from any current or former employer.

---

## 📄 License

MIT