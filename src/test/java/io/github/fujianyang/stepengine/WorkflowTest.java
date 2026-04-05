package io.github.fujianyang.stepengine;

import io.github.fujianyang.stepengine.exception.WorkflowException;
import io.github.fujianyang.stepengine.handler.StepHandler;
import io.github.fujianyang.stepengine.outcome.StepOutcome;
import io.github.fujianyang.stepengine.retry.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowTest {

    @Test
    void shouldSucceedWhenAllStepsSucceed() {
        TestContext context = new TestContext();

        Step<TestContext> step1 = Step.<TestContext>builder()
            .name("step-1")
            .forward(ctx -> {
                ctx.step1Calls++;
                return StepOutcome.success();
            })
            .build();

        Step<TestContext> step2 = Step.<TestContext>builder()
            .name("step-2")
            .forward(ctx -> {
                ctx.step2Calls++;
                return StepOutcome.success();
            })
            .build();

        Workflow<TestContext> workflow = Workflow.<TestContext>builder()
            .step(step1)
            .step(step2)
            .build();

        WorkflowResult<TestContext> result = workflow.run(context);

        assertTrue(result.isSucceeded());
        assertEquals(1, context.step1Calls);
        assertEquals(1, context.step2Calls);
        assertTrue(result.failedStepName().isEmpty());
        assertTrue(result.failureReason().isEmpty());
        assertTrue(result.failureCause().isEmpty());
    }

    @Test
    void shouldStopWorkflowOnPermanentFailure() {
        TestContext context = new TestContext();

        Step<TestContext> step1 = Step.<TestContext>builder()
            .name("validate")
            .forward(ctx -> {
                ctx.step1Calls++;
                return StepOutcome.permanentFailure("validation failed");
            })
            .build();

        Step<TestContext> step2 = Step.<TestContext>builder()
            .name("should-not-run")
            .forward(ctx -> {
                ctx.step2Calls++;
                return StepOutcome.success();
            })
            .build();

        Workflow<TestContext> workflow = Workflow.<TestContext>builder()
            .step(step1)
            .step(step2)
            .build();

        WorkflowResult<TestContext> result = workflow.run(context);

        assertTrue(result.isFailed());
        assertEquals(1, context.step1Calls);
        assertEquals(0, context.step2Calls);
        assertEquals("validate", result.failedStepName().orElseThrow());
        assertEquals("validation failed", result.failureReason().orElseThrow());
        assertTrue(result.failureCause().isEmpty());
    }

    @Test
    void shouldRetryRetryableFailureAndEventuallySucceed() {
        TestContext context = new TestContext();

        Step<TestContext> step = Step.<TestContext>builder()
            .name("remote-call")
            .forward(ctx -> {
                ctx.step1Calls++;
                if (ctx.step1Calls == 1) {
                    return StepOutcome.retryableFailure("temporary failure");
                }
                return StepOutcome.success();
            })
            .retryPolicy(RetryPolicy.exponentialBackoff(2, Duration.ofMillis(10)))
            .build();

        Workflow<TestContext> workflow = Workflow.<TestContext>builder()
            .step(step)
            .build();

        WorkflowResult<TestContext> result = workflow.run(context);

        assertTrue(result.isSucceeded());
        assertEquals(2, context.step1Calls);
    }

    @Test
    void shouldFailWhenRetryableFailureExhaustsRetryBudget() {
        TestContext context = new TestContext();

        Step<TestContext> step = Step.<TestContext>builder()
            .name("remote-call")
            .forward(ctx -> {
                ctx.step1Calls++;
                return StepOutcome.retryableFailure("still unavailable");
            })
            .retryPolicy(RetryPolicy.exponentialBackoff(3, Duration.ofMillis(10), t -> true))
            .build();

        Workflow<TestContext> workflow = Workflow.<TestContext>builder()
            .step(step)
            .build();

        WorkflowResult<TestContext> result = workflow.run(context);

        assertTrue(result.isFailed());
        assertEquals(3, context.step1Calls);
        assertEquals("remote-call", result.failedStepName().orElseThrow());
        assertEquals("still unavailable", result.failureReason().orElseThrow());
    }

    @Test
    void shouldFailOnNonRetryableException() {
        TestContext context = new TestContext();

        Step<TestContext> step = Step.<TestContext>builder()
            .name("remote-call")
            .forward(ctx -> {
                ctx.step1Calls++;
                throw new IllegalArgumentException("bad request");
            })
            .retryPolicy(RetryPolicy.noRetry())
            .build();

        Workflow<TestContext> workflow = Workflow.<TestContext>builder()
            .step(step)
            .build();

        WorkflowResult<TestContext> result = workflow.run(context);

        assertTrue(result.isFailed());
        assertEquals(1, context.step1Calls);
        assertEquals("remote-call", result.failedStepName().orElseThrow());
        assertTrue(result.failureReason().orElseThrow().contains("IllegalArgumentException"));
        assertInstanceOf(IllegalArgumentException.class, result.failureCause().orElseThrow());
    }

    @Test
    void shouldRetryThrownRetryableExceptionAndEventuallySucceed() {
        TestContext context = new TestContext();

        Step<TestContext> step = Step.<TestContext>builder()
            .name("remote-call")
            .forward(ctx -> {
                ctx.step1Calls++;
                if (ctx.step1Calls == 1) {
                    throw new SocketTimeoutException("timeout");
                }
                return StepOutcome.success();
            })
            .retryPolicy(RetryPolicy.exponentialBackoff(2, Duration.ofMillis(10), t -> true))
            .build();

        Workflow<TestContext> workflow = Workflow.<TestContext>builder()
            .step(step)
            .build();

        WorkflowResult<TestContext> result = workflow.run(context);

        assertTrue(result.isSucceeded());
        assertEquals(2, context.step1Calls);
    }

    @Test
    void shouldUseStepLevelRetryPolicyInsteadOfWorkflowDefault() {
        TestContext context = new TestContext();

        Step<TestContext> step = Step.<TestContext>builder()
            .name("step-with-override")
            .forward(ctx -> {
                ctx.step1Calls++;
                if (ctx.step1Calls == 1) {
                    return StepOutcome.retryableFailure("temporary");
                }
                return StepOutcome.success();
            })
            .retryPolicy(RetryPolicy.exponentialBackoff(2, Duration.ofMillis(10), t -> true))
            .build();

        Workflow<TestContext> workflow = Workflow.<TestContext>builder()
            .defaultRetryPolicy(RetryPolicy.noRetry())
            .step(step)
            .build();

        WorkflowResult<TestContext> result = workflow.run(context);

        assertTrue(result.isSucceeded());
        assertEquals(2, context.step1Calls);
    }

    @Test
    void shouldUseWorkflowDefaultRetryPolicyWhenStepDoesNotOverride() {
        TestContext context = new TestContext();

        Step<TestContext> step = Step.<TestContext>builder()
            .name("step-with-default-policy")
            .forward(ctx -> {
                ctx.step1Calls++;
                if (ctx.step1Calls == 1) {
                    return StepOutcome.retryableFailure("temporary");
                }
                return StepOutcome.success();
            })
            .build();

        Workflow<TestContext> workflow = Workflow.<TestContext>builder()
            .defaultRetryPolicy(RetryPolicy.exponentialBackoff(3, Duration.ofMillis(10), t -> true))
            .step(step)
            .build();

        WorkflowResult<TestContext> result = workflow.run(context);

        assertTrue(result.isSucceeded());
        assertEquals(2, context.step1Calls);
    }

    @Test
    void shouldThrowWorkflowExceptionWhenInterruptedDuringRetryDelay() {
        TestContext context = new TestContext();

        Step<TestContext> step = Step.<TestContext>builder()
            .name("interrupt-test")
            .forward(ctx -> StepOutcome.retryableFailure("temporary"))
            .retryPolicy(RetryPolicy.exponentialBackoff(2, Duration.ofMillis(10), t -> true))
            .build();

        Workflow<TestContext> workflow = Workflow.<TestContext>builder()
            .step(step)
            .build();

        Thread.currentThread().interrupt();

        try {
            assertThrows(WorkflowException.class, () -> workflow.run(context));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void shouldComputeJitteredDelayWithinExpectedRange() {
        RetryPolicy policy = RetryPolicy.exponentialBackoff(
            3,
            Duration.ofMillis(100),
            ex -> true
        );

        Duration delay = policy.delayBeforeRetry(3);

        assertFalse(delay.isNegative());
        assertTrue(delay.compareTo(Duration.ofMillis(400)) <= 0);
    }

    @Test
    void shouldNotRetryWhenStepThrowsBeforeReturningOutcome() {
        AtomicInteger attempts = new AtomicInteger();

        StepHandler<TestContext> handler = ctx -> {
            attempts.incrementAndGet();
            throw new RuntimeException("boom before outcome");
        };

        RetryPolicy retryPolicy = RetryPolicy.exponentialBackoff(3, Duration.ofMillis(100));

        Step<TestContext> step = Step.<TestContext>builder()
            .name("failing-step")
            .forward(handler)
            .retryPolicy(retryPolicy)
            .build();

        Workflow<TestContext> workflow = Workflow.<TestContext>builder().step(step).build();
        WorkflowResult<TestContext> result = workflow.run(new TestContext());

        assertFalse(result.isSucceeded());
        assertEquals(1, attempts.get());
        assertEquals("failing-step", result.failedStepName().orElse(null));
        assertTrue(
            result.failureReason()
                .map(reason -> reason.contains("Unhandled exception during step execution"))
                .orElse(false));
        assertInstanceOf(RuntimeException.class, result.failureCause().orElse(null));
        assertEquals(
            "boom before outcome",
            result.failureCause()
                .map(Throwable::getMessage)
                .orElse(null)
        );
    }

    private static class TestContext {
        int step1Calls;
        int step2Calls;
    }
}