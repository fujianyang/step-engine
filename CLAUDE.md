# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Test Commands

```bash
# Build (skip tests)
mvn package -DskipTests

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=StepEngineExecutionTest

# Run a single test method
mvn test -Dtest=StepEngineExecutionTest#someTestMethod
```

Java 21 is required. The only runtime dependency is SLF4J; Logback is used only in tests.

## Architecture

StepEngine is a small library with no external runtime dependencies (SLF4J only). All source lives under `src/main/java/io/github/fujianyang/stepengine/`.

### Core execution flow

`StepEngine<C>` holds an ordered list of `Step<C>` objects and a `RetryPolicy`. Calling `execute(context)` runs each step's forward handler in sequence. The context type `C` is unconstrained — any POJO works.

On failure:
1. If the exception is a `ServiceException` (or subclass), it is rethrown immediately — no retry.
2. Otherwise the active `RetryPolicy` is consulted (`shouldRetry` + `backoffDelay`). When retries are exhausted, the exception propagates.
3. In either case, already-completed steps are rolled back in reverse order (best-effort). Rollback failures are attached as suppressed exceptions on the original failure.

### Key types

| Type | Purpose |
|------|---------|
| `StepEngine<C>` | Orchestrator; built via `StepEngine.builder()` |
| `Step<C>` | A named unit of work with a forward handler, optional rollback handler, and optional per-step `RetryPolicy` override |
| `StepHandler<C>` | `@FunctionalInterface` — `void forward(C context) throws Exception` |
| `RollbackHandler<C>` | `@FunctionalInterface` — `void rollback(C context) throws Exception` |
| `ServiceException` | Abstract base for expected business failures (carries an `errorCode`); never retried |
| `RetryPolicy` | Interface: `shouldRetry(Throwable, attemptNumber)` + `backoffDelay(attemptNumber)` |
| `ExponentialBackoffRetryPolicy` | Built-in policy with jitter; defaults: maxAttempts=3, initialDelay=100ms, maxDelay=5s |
| `NoRetryPolicy` | Default when no policy is set; never retries |

### Per-step retry override

A `Step` can carry its own `RetryPolicy` (set via `Step.builder().retryPolicy(...)`). `StepEngine` uses the step-level policy if present, otherwise falls back to the engine-level policy.

### Parallel steps (planned)

Support for running independent steps concurrently via `.parallel(ParallelGroup)`.

#### API

```java
StepEngine.<Ctx>builder()
    .step("validate", ctx -> { ... })
    .parallel(
        ParallelGroup.<Ctx>builder()
            .step(Step.of("call-A", ctx -> { ... }, ctx -> { ... }))
            .step(Step.of("call-B", ctx -> { ... }))
            .step(Step.of("call-C", ctx -> { ... }))
            .executor(groupExecutor)  // optional
            .build()
    )
    .step("persist", ctx -> { ... })
    .build();
```

- `ParallelGroup` is a first-class type containing the steps and optional group-level executor.
- `Step` gains an optional `executor` field (same pattern as `retryPolicy`; ignored for sequential execution).
- Executor resolution order: step → group → virtual threads (Java 21 default).

#### Failure semantics

- **Wait-all**: when a step fails, let currently-executing steps finish before handling failure.
- **ServiceException in a parallel group**: the group is immediately doomed. Other steps finish their current attempt but do not start new retries. The ServiceException is the primary exception.
- **Regular exceptions**: each step retries independently per its own policy. The group fails only after a step exhausts retries without any ServiceException from a sibling.
- **Multiple failures**: first exception (chronologically) is primary; others are suppressed.

#### Rollback

For `Step1 ✓ → [A ✓, B ✗, C ✓]`:
1. Roll back A and C in parallel (they were independent forward, independent in reverse).
2. Then roll back Step1.
3. The failed step (B) is not rolled back — it never completed.

#### Context thread-safety

User's responsibility. If using parallel steps, the context `C` must be safe for concurrent access.
