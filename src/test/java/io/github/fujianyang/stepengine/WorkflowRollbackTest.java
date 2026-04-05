package io.github.fujianyang.stepengine;

import io.github.fujianyang.stepengine.outcome.StepOutcome;
import io.github.fujianyang.stepengine.result.RollbackResult;
import io.github.fujianyang.stepengine.retry.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowRollbackTest {

    @Test
    void shouldCompleteNormallyWhenAllThreeStepsSucceed() {
        TestContext context = new TestContext();

        Step<TestContext> step1 = Step.<TestContext>builder()
            .name("step1")
            .forward(ctx -> {
                ctx.events.add("forward-step1");
                return StepOutcome.success();
            })
            .rollback(ctx -> ctx.events.add("rollback-step1"))
            .build();

        Step<TestContext> step2 = Step.<TestContext>builder()
            .name("step2")
            .forward(ctx -> {
                ctx.events.add("forward-step2");
                return StepOutcome.success();
            })
            .rollback(ctx -> ctx.events.add("rollback-step2"))
            .build();

        Step<TestContext> step3 = Step.<TestContext>builder()
            .name("step3")
            .forward(ctx -> {
                ctx.events.add("forward-step3");
                return StepOutcome.success();
            })
            .build();

        Workflow<TestContext> workflow = Workflow.<TestContext>builder()
            .step(step1)
            .step(step2)
            .step(step3)
            .build();

        WorkflowResult<TestContext> result = workflow.run(context);

        assertTrue(result.isSucceeded());
        assertFalse(result.isFailed());
        assertEquals(
            List.of("forward-step1", "forward-step2", "forward-step3"),
            context.events
        );

        assertTrue(result.failedStepName().isEmpty());
        assertTrue(result.failureReason().isEmpty());
        assertTrue(result.failureCause().isEmpty());

        assertFalse(result.rollbackAttempted());
        assertFalse(result.rollbackSucceeded());
        assertFalse(result.hasRollbackFailure());
    }

    @Test
    void shouldRollbackStep2ThenStep1WhenStep3FailsAndBothRollbacksSucceed() {
        TestContext context = new TestContext();

        Step<TestContext> step1 = Step.<TestContext>builder()
            .name("step1")
            .forward(ctx -> {
                ctx.events.add("forward-step1");
                return StepOutcome.success();
            })
            .rollback(ctx -> ctx.events.add("rollback-step1"))
            .build();

        Step<TestContext> step2 = Step.<TestContext>builder()
            .name("step2")
            .forward(ctx -> {
                ctx.events.add("forward-step2");
                return StepOutcome.success();
            })
            .rollback(ctx -> ctx.events.add("rollback-step2"))
            .build();

        Step<TestContext> step3 = Step.<TestContext>builder()
            .name("step3")
            .forward(ctx -> {
                ctx.events.add("forward-step3");
                return StepOutcome.permanentFailure("step3 failed");
            })
            .build();

        Workflow<TestContext> workflow = Workflow.<TestContext>builder()
            .step(step1)
            .step(step2)
            .step(step3)
            .build();

        WorkflowResult<TestContext> result = workflow.run(context);

        assertTrue(result.isFailed());
        assertEquals(
            List.of(
                "forward-step1",
                "forward-step2",
                "forward-step3",
                "rollback-step2",
                "rollback-step1"
            ),
            context.events
        );

        assertEquals("step3", result.failedStepName().orElseThrow());
        assertEquals("step3 failed", result.failureReason().orElseThrow());
        assertTrue(result.failureCause().isEmpty());

        assertTrue(result.rollbackAttempted());
        assertTrue(result.rollbackSucceeded());
        assertFalse(result.hasRollbackFailure());

        RollbackResult rollbackResult = result.rollback();
        assertTrue(rollbackResult.attempted());
        assertTrue(rollbackResult.isSucceeded());
        assertFalse(rollbackResult.isFailed());
        assertTrue(rollbackResult.failedStepName().isEmpty());
        assertTrue(rollbackResult.failureCause().isEmpty());
    }

    @Test
    void shouldStopRollbackWhenStep2RollbackFailsAndNotRunStep1Rollback() {
        TestContext context = new TestContext();

        RuntimeException rollbackException = new RuntimeException("rollback step2 failed");

        Step<TestContext> step1 = Step.<TestContext>builder()
            .name("step1")
            .forward(ctx -> {
                ctx.events.add("forward-step1");
                return StepOutcome.success();
            })
            .rollback(ctx -> ctx.events.add("rollback-step1"))
            .build();

        Step<TestContext> step2 = Step.<TestContext>builder()
            .name("step2")
            .forward(ctx -> {
                ctx.events.add("forward-step2");
                return StepOutcome.success();
            })
            .rollback(ctx -> {
                ctx.events.add("rollback-step2");
                throw rollbackException;
            })
            .build();

        Step<TestContext> step3 = Step.<TestContext>builder()
            .name("step3")
            .forward(ctx -> {
                ctx.events.add("forward-step3");
                return StepOutcome.permanentFailure("step3 failed");
            })
            .build();

        Workflow<TestContext> workflow = Workflow.<TestContext>builder()
            .step(step1)
            .step(step2)
            .step(step3)
            .build();

        WorkflowResult<TestContext> result = workflow.run(context);

        assertTrue(result.isFailed());
        assertEquals(
            List.of(
                "forward-step1",
                "forward-step2",
                "forward-step3",
                "rollback-step2"
            ),
            context.events
        );

        assertEquals("step3", result.failedStepName().orElseThrow());
        assertEquals("step3 failed", result.failureReason().orElseThrow());
        assertTrue(result.failureCause().isEmpty());

        assertTrue(result.rollbackAttempted());
        assertFalse(result.rollbackSucceeded());
        assertTrue(result.hasRollbackFailure());

        RollbackResult rollbackResult = result.rollback();
        assertTrue(rollbackResult.attempted());
        assertFalse(rollbackResult.isSucceeded());
        assertTrue(rollbackResult.isFailed());
        assertEquals("step2", rollbackResult.failedStepName().orElseThrow());
        assertSame(rollbackException, rollbackResult.failureCause().orElseThrow());

        assertFalse(context.events.contains("rollback-step1"));
    }

    @Test
    void shouldReportRollbackFailureWhenStep1RollbackFailsAfterStep2RollbackSucceeds() {
        TestContext context = new TestContext();

        RuntimeException rollbackException = new RuntimeException("rollback step1 failed");

        Step<TestContext> step1 = Step.<TestContext>builder()
            .name("step1")
            .forward(ctx -> {
                ctx.events.add("forward-step1");
                return StepOutcome.success();
            })
            .rollback(ctx -> {
                ctx.events.add("rollback-step1");
                throw rollbackException;
            })
            .build();

        Step<TestContext> step2 = Step.<TestContext>builder()
            .name("step2")
            .forward(ctx -> {
                ctx.events.add("forward-step2");
                return StepOutcome.success();
            })
            .rollback(ctx -> ctx.events.add("rollback-step2"))
            .build();

        Step<TestContext> step3 = Step.<TestContext>builder()
            .name("step3")
            .forward(ctx -> {
                ctx.events.add("forward-step3");
                return StepOutcome.permanentFailure("step3 failed");
            })
            .build();

        Workflow<TestContext> workflow = Workflow.<TestContext>builder()
            .step(step1)
            .step(step2)
            .step(step3)
            .build();

        WorkflowResult<TestContext> result = workflow.run(context);

        assertTrue(result.isFailed());
        assertEquals(
            List.of(
                "forward-step1",
                "forward-step2",
                "forward-step3",
                "rollback-step2",
                "rollback-step1"
            ),
            context.events
        );

        assertEquals("step3", result.failedStepName().orElseThrow());
        assertEquals("step3 failed", result.failureReason().orElseThrow());
        assertTrue(result.failureCause().isEmpty());

        assertTrue(result.rollbackAttempted());
        assertFalse(result.rollbackSucceeded());
        assertTrue(result.hasRollbackFailure());

        RollbackResult rollbackResult = result.rollback();
        assertTrue(rollbackResult.attempted());
        assertFalse(rollbackResult.isSucceeded());
        assertTrue(rollbackResult.isFailed());
        assertEquals("step1", rollbackResult.failedStepName().orElseThrow());
        assertSame(rollbackException, rollbackResult.failureCause().orElseThrow());
    }

    @Test
    void shouldRetryStep3ThenRollbackWhenRetryExhausted() {
        TestContext context = new TestContext();

        Step<TestContext> step1 = Step.<TestContext>builder()
            .name("step1")
            .forward(ctx -> {
                ctx.events.add("forward-step1");
                return StepOutcome.success();
            })
            .rollback(ctx -> ctx.events.add("rollback-step1"))
            .build();

        Step<TestContext> step2 = Step.<TestContext>builder()
            .name("step2")
            .forward(ctx -> {
                ctx.events.add("forward-step2");
                return StepOutcome.success();
            })
            .rollback(ctx -> ctx.events.add("rollback-step2"))
            .build();

        Step<TestContext> step3 = Step.<TestContext>builder()
            .name("step3")
            .forward(ctx -> {
                ctx.step3Attempts++;
                ctx.events.add("forward-step3-attempt-" + ctx.step3Attempts);
                return StepOutcome.retryableFailure("step3 temporary failure");
            })
            .retryPolicy(RetryPolicy.exponentialBackoff(3, Duration.ZERO))
            .build();

        Workflow<TestContext> workflow = Workflow.<TestContext>builder()
            .steps(step1, step2, step3)
            .build();

        WorkflowResult<TestContext> result = workflow.run(context);

        assertTrue(result.isFailed());

        assertEquals(
            List.of(
                "forward-step1",
                "forward-step2",
                "forward-step3-attempt-1",
                "forward-step3-attempt-2",
                "forward-step3-attempt-3",
                "rollback-step2",
                "rollback-step1"
            ),
            context.events
        );

        assertEquals(3, context.step3Attempts);

        assertEquals("step3", result.failedStepName().orElseThrow());
        assertEquals("step3 temporary failure", result.failureReason().orElseThrow());
        assertTrue(result.failureCause().isEmpty());

        assertTrue(result.rollbackAttempted());
        assertTrue(result.rollbackSucceeded());
        assertFalse(result.hasRollbackFailure());

        RollbackResult rollbackResult = result.rollback();
        assertTrue(rollbackResult.attempted());
        assertTrue(rollbackResult.isSucceeded());
        assertFalse(rollbackResult.isFailed());
        assertTrue(rollbackResult.failedStepName().isEmpty());
        assertTrue(rollbackResult.failureCause().isEmpty());
    }

    @Test
    void shouldRetryStep3ExceptionThenRollbackWhenRetryExhausted() {
        TestContext context = new TestContext();

        Step<TestContext> step1 = Step.<TestContext>builder()
            .name("step1")
            .forward(ctx -> {
                ctx.events.add("forward-step1");
                return StepOutcome.success();
            })
            .rollback(ctx -> ctx.events.add("rollback-step1"))
            .build();

        Step<TestContext> step2 = Step.<TestContext>builder()
            .name("step2")
            .forward(ctx -> {
                ctx.events.add("forward-step2");
                return StepOutcome.success();
            })
            .rollback(ctx -> ctx.events.add("rollback-step2"))
            .build();

        RuntimeException step3Exception = new RuntimeException("step3 transient exception");

        Step<TestContext> step3 = Step.<TestContext>builder()
            .name("step3")
            .forward(ctx -> {
                ctx.step3Attempts++;
                ctx.events.add("forward-step3-attempt-" + ctx.step3Attempts);
                throw step3Exception;
            })
            .retryPolicy(RetryPolicy.exponentialBackoff(
                3,
                Duration.ZERO,
                ex -> ex instanceof RuntimeException
            ))
            .build();

        Workflow<TestContext> workflow = Workflow.<TestContext>builder()
            .steps(step1, step2, step3)
            .build();

        WorkflowResult<TestContext> result = workflow.run(context);

        assertTrue(result.isFailed());

        assertEquals(
            List.of(
                "forward-step1",
                "forward-step2",
                "forward-step3-attempt-1",
                "forward-step3-attempt-2",
                "forward-step3-attempt-3",
                "rollback-step2",
                "rollback-step1"
            ),
            context.events
        );

        assertEquals(3, context.step3Attempts);

        assertEquals("step3", result.failedStepName().orElseThrow());
        assertTrue(result.failureReason().orElseThrow().contains("RuntimeException"));
        assertSame(step3Exception, result.failureCause().orElseThrow());

        assertTrue(result.rollbackAttempted());
        assertTrue(result.rollbackSucceeded());
        assertFalse(result.hasRollbackFailure());

        RollbackResult rollbackResult = result.rollback();
        assertTrue(rollbackResult.attempted());
        assertTrue(rollbackResult.isSucceeded());
        assertFalse(rollbackResult.isFailed());
        assertTrue(rollbackResult.failedStepName().isEmpty());
        assertTrue(rollbackResult.failureCause().isEmpty());
    }

    @Test
    void shouldRollbackImmediatelyWhenStep3ThrowsNonRetryableException() {
        TestContext context = new TestContext();

        Step<TestContext> step1 = Step.<TestContext>builder()
            .name("step1")
            .forward(ctx -> {
                ctx.events.add("forward-step1");
                return StepOutcome.success();
            })
            .rollback(ctx -> ctx.events.add("rollback-step1"))
            .build();

        Step<TestContext> step2 = Step.<TestContext>builder()
            .name("step2")
            .forward(ctx -> {
                ctx.events.add("forward-step2");
                return StepOutcome.success();
            })
            .rollback(ctx -> ctx.events.add("rollback-step2"))
            .build();

        IllegalArgumentException step3Exception = new IllegalArgumentException("step3 bad request");

        Step<TestContext> step3 = Step.<TestContext>builder()
            .name("step3")
            .forward(ctx -> {
                ctx.step3Attempts++;
                ctx.events.add("forward-step3-attempt-" + ctx.step3Attempts);
                throw step3Exception;
            })
            .retryPolicy(RetryPolicy.exponentialBackoff(
                3,
                Duration.ZERO,
                ex -> ex instanceof java.net.SocketTimeoutException
            ))
            .build();

        Workflow<TestContext> workflow = Workflow.<TestContext>builder()
            .steps(step1, step2, step3)
            .build();

        WorkflowResult<TestContext> result = workflow.run(context);

        assertTrue(result.isFailed());

        assertEquals(
            List.of(
                "forward-step1",
                "forward-step2",
                "forward-step3-attempt-1",
                "rollback-step2",
                "rollback-step1"
            ),
            context.events
        );

        assertEquals(1, context.step3Attempts);

        assertEquals("step3", result.failedStepName().orElseThrow());
        assertTrue(result.failureReason().orElseThrow().contains("IllegalArgumentException"));
        assertSame(step3Exception, result.failureCause().orElseThrow());

        assertTrue(result.rollbackAttempted());
        assertTrue(result.rollbackSucceeded());
        assertFalse(result.hasRollbackFailure());

        RollbackResult rollbackResult = result.rollback();
        assertTrue(rollbackResult.attempted());
        assertTrue(rollbackResult.isSucceeded());
        assertFalse(rollbackResult.isFailed());
        assertTrue(rollbackResult.failedStepName().isEmpty());
        assertTrue(rollbackResult.failureCause().isEmpty());
    }

    private static final class TestContext {
        private final List<String> events = new ArrayList<>();
        private int step3Attempts;
    }
}