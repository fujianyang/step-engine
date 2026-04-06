# StepEngine

StepEngine is a lightweight workflow engine for short-running, idempotent workflows.

It provides a simple and explicit way to orchestrate multi-step operations with fine-grained control over retry behavior and failure handling — without introducing external infrastructure or workflow persistence.

---

## ✨ Features

- Sequential workflow execution
- Explicit step outcomes:
  - `success`
  - `retryable failure`
  - `permanent failure`
- Per-workflow default retry policy
- Per-step retry policy override
- Exponential backoff with jitter
- Exception classification for retry fallback
- Optional rollback (compensation) support
- Zero external dependencies

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
- an optional retry policy override

---

### StepOutcome

Each step returns one of:

- `StepOutcome.success()`
- `StepOutcome.retryableFailure(reason)`
- `StepOutcome.retryableFailure(reason, cause)`
- `StepOutcome.permanentFailure(reason)`
- `StepOutcome.permanentFailure(reason, cause)`
---

### RetryPolicy

A `RetryPolicy` controls:

- maximum attempts
- retry delay strategy (e.g., exponential backoff with jitter)
- which exceptions are retryable (fallback for unhandled exceptions)

---

### Context

The workflow operates on a context object that carries state across steps.

Any data object can be used as the context — it does not need to implement a specific interface or extend a base class.

In practice, the context is typically a request-scoped object that contains:
- input data
- intermediate state
- final result

This keeps the framework decoupled from domain models and avoids unnecessary abstractions.

### Workflow

A `Workflow` is an ordered sequence of steps executed against a context object.

---

## 🔁 Retry Semantics

StepEngine uses an explicit, outcome-driven retry model with **fail-fast behavior**.

---

### Forward execution

Each step produces one of the following outcomes:

- `StepOutcome.success()`  
  → the workflow proceeds to the next step

- `StepOutcome.retryableFailure(reason[, cause])`  
  → the step is retried according to the retry policy

- `StepOutcome.permanentFailure(reason[, cause])`  
  → the workflow fails immediately (**fail-fast**)

---

### Retry policy

Retries are controlled by a `RetryPolicy`, which defines:

- maximum number of attempts
- delay strategy between retries (e.g., exponential backoff with jitter)
- which exceptions are considered retryable

Key rules:

- `maxAttempts = 1` means no retry (only the initial attempt is executed)
- step-level retry policy overrides the workflow default retry policy
- if no step-level policy is defined, the workflow default is used

---

### Exception handling

If a step throws an exception:

- the exception is evaluated by the retry policy
- if the exception is retryable → the step is retried
- otherwise → the workflow fails immediately (**fail-fast**)

---

### Retry exhaustion

If a step continues to fail and exceeds the retry policy:

- the workflow transitions to failure (**fail-fast**)
- rollback (if applicable) is triggered

---

### Why jitter?

Exponential backoff uses full jitter, meaning the actual delay is randomized between zero and the computed exponential delay.

This reduces synchronized retry spikes when multiple clients retry at the same time.

---

### Design principle

Retry behavior is **explicit and fail-fast by default**:

- steps signal retryability via `RetryableFailure`
- retry policies define how retries are performed
- non-retryable conditions stop the workflow immediately
- no implicit or automatic retry decisions are made by the engine

---

### Rollback failure behavior

Rollback uses a **fail-fast policy**:

- if a rollback step throws an exception
- rollback stops immediately
- earlier steps are not rolled back

This preserves dependency order during compensation.

However, a rollback exception may represent:
- a true compensation failure
- a transient technical issue
- or a bug in rollback logic

This is a conservative default. Future versions may support configurable rollback failure policies.

---

## Exception Handling

StepEngine supports two kinds of failure signaling:

### 1. StepOutcome-based failures
Steps can return:
- `StepSuccess`
- `RetryableFailure`
- `PermanentFailure`

These failures are handled entirely within the workflow and returned as `WorkflowResult`.

### 2. ServiceException-based failures
Steps may also throw `ServiceException` subclasses for expected service-visible failures such as:
- invalid request
- resource not found
- conflict
- forbidden operation

When a step throws `ServiceException`, StepEngine:
1. stops forward execution immediately
2. runs rollback for previously completed rollback-capable steps
3. rethrows the original `ServiceException`

This allows framework-level exception handlers (for example Micronaut `ExceptionHandler`) to map the exception to an API response while still ensuring workflow cleanup is performed.

### Retry behavior
`ServiceException` is terminal and is never retried, even if the configured `RetryPolicy` would otherwise retry the thrown exception.

### Rollback failure during ServiceException handling
If rollback fails while handling a `ServiceException`, the original `ServiceException` is still rethrown. Rollback failure details are preserved for internal diagnostics and should not be exposed to API clients.


## 📦 Example

### Minimal workflow

```java

@Singleton
public class ValidateRequestStep implements StepHandler<MyContext> {

  @Override
  public StepOutcome apply(MyContext ctx) {
    try {
      // business logic here
      if (!isValid(ctx)) {
        // InvalidRequestException extends ServiceException  
        throw new InvalidRequestException("INVALID_REQUEST", "deviceId is required");
      }
      return StepOutcome.success();
    } catch (TimeoutException e) { // example of a retryable exception
      return StepOutcome.retryableFailure("timeout", e);
    } catch (Exception e) {
      return StepOutcome.permanentFailure("unexpected error", e);
    }
  }
}

@Singleton
public class CallDownstreamStep implements StepHandler<MyContext> {

  @Override
  public StepOutcome apply(MyContext ctx) {
    try {
      // business logic here
      return StepOutcome.success();
    } catch (TimeoutException e) { // example of a retryable exception
      return StepOutcome.retryableFailure("timeout", e);
    } catch (Exception e) {
      return StepOutcome.permanentFailure("unexpected error", e);
    }
  }
}

@Singleton
public class PersistResultStep implements StepHandler<MyContext> {

  @Override
  public StepOutcome apply(MyContext ctx) {
    try {
      // persistence logic here
      return StepOutcome.success();
    } catch (TimeoutException e) { // example of a retryable exception
      return StepOutcome.retryableFailure("timeout", e);
    } catch (OptimisticLockException | PreconditionFailedException e) {
      return StepOutcome.permanentFailure("precondition failed", e);
    } catch (Exception e) {
      return StepOutcome.permanentFailure("unexpected error", e);
    }
  }

}

@Factory
public class WorkflowFactory {

  @Singleton
  @Named("validateRequestStep")
  public Step<MyContext> validateRequestStep(ValidateRequestStep handler) {
    return Step.<MyContext>builder()
            .name("validate request")
            .forward(handler)
            .build();
  }

  @Singleton
  @Named("callDownstreamStep")
  public Step<MyContext> callDownstreamStep(CallDownstreamStep handler) {
    return Step.<MyContext>builder()
            .name("call downstream")
            .forward(handler)
            .retryPolicy(RetryPolicy.exponentialBackoff(3, Duration.ofMillis(200)))
            .build();
  }

  @Singleton
  @Named("persistResultStep")
  public Step<MyContext> persistResultStep(PersistResultStep handler) {
    return Step.<MyContext>builder()
            .name("persist result")
            .forward(handler)
            .retryPolicy(RetryPolicy.exponentialBackoff(3, Duration.ofMillis(200)))
            .build();
  }


  @Singleton
  @Named("MyWorkflow")
  public Workflow<MyContext> myWorkflow(
          @Named("validateRequestStep") Step<MyContext> validateRequest,
          @Named("callDownstreamStep") Step<MyContext> callDownstream,
          @Named("persistResultStep") Step<MyContext> persistResult) {

    return Workflow.<MyContext>builder()
            .step(validateRequest)
            .step(callDownstream)
            .step(persistResult)
            .build();
  }
}

@Singleton
public class MyService {

  @Inject
  @Named("myWorkflow")
  Workflow<MyContext> myWorkflow;

  public MyResponse process(TheRequest request) {
    // In this example, `MyContext` is a simple data object created per request 
    // and passed through the workflow. It accumulates state as each step executes.  
    MyContext context = new MyContext(request);

    WorkflowResult<MyContext> result = myWorkflow.run(context);

    if (!result.isSucceeded()) {
      // handle failure (log, throw, map to response, etc.)
      throw new RuntimeException("Workflow failed: " + result);
    }

    return context.getResponse();
  }
}


```

For more complete examples:
- Retry and failure handling
- Rollback behavior and compensation
- Edge cases and failure scenarios

See the test cases in the repository.

## ⚖️ Intellectual Property

This project is an original implementation created independently and does not contain proprietary code from any current or former employer.

The design is inspired by general software engineering practices for workflow orchestration, retry, and compensation patterns.

---

## 📄 License

This project is licensed under the MIT License.