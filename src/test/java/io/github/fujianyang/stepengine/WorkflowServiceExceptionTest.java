package io.github.fujianyang.stepengine;

import io.github.fujianyang.stepengine.exception.ServiceException;
import io.github.fujianyang.stepengine.outcome.StepOutcome;
import io.github.fujianyang.stepengine.retry.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowServiceExceptionTest {

    @Test
    void shouldRethrowServiceExceptionWhenFirstStepFails() {
        TestServiceException serviceException =
            new TestServiceException("INVALID_REQUEST", "request is invalid");

        Workflow<TestContext> workflow = Workflow.<TestContext>builder()
            .defaultRetryPolicy(RetryPolicy.noRetry())
            .step(
                Step.of("validate-request", context -> {
                    throw serviceException;
                })
            )
            .build();

        TestContext context = new TestContext();

        TestServiceException thrown = assertThrows(
            TestServiceException.class,
            () -> workflow.run(context)
        );

        assertSame(serviceException, thrown);
        assertEquals("INVALID_REQUEST", thrown.getErrorCode());
        assertEquals("request is invalid", thrown.getMessage());
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    void shouldRollbackCompletedStepsAndRethrowOriginalServiceException() {
        TestServiceException serviceException =
            new TestServiceException("INVALID_REQUEST", "request is invalid");

        List<String> events = new ArrayList<>();

        Step<TestContext> step1 = Step.<TestContext>builder()
            .name("step-1")
            .forward(context -> {
                events.add("step-1-forward");
                return StepOutcome.success();
            })
            .rollback(context -> events.add("step-1-rollback"))
            .build();

        Step<TestContext> step2 = Step.of("step-2", context -> {
            events.add("step-2-forward");
            return StepOutcome.success();
        });

        Step<TestContext> step3 = Step.of("step-3", context -> {
            events.add("step-3-forward");
            throw serviceException;
        });

        Workflow<TestContext> workflow = Workflow.<TestContext>builder()
            .defaultRetryPolicy(RetryPolicy.noRetry())
            .steps(step1, step2, step3)
            .build();

        TestContext context = new TestContext();

        TestServiceException thrown = assertThrows(
            TestServiceException.class,
            () -> workflow.run(context)
        );

        assertSame(serviceException, thrown);
        assertEquals(
            List.of(
                "step-1-forward",
                "step-2-forward",
                "step-3-forward",
                "step-1-rollback"
            ),
            events
        );
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    void shouldAttachRollbackFailureAsSuppressedAndRethrowOriginalServiceException() {
        TestServiceException serviceException =
            new TestServiceException("INVALID_REQUEST", "request is invalid");

        RuntimeException rollbackFailure = new RuntimeException("rollback failed");

        List<String> events = new ArrayList<>();

        Step<TestContext> step1 = Step.<TestContext>builder()
            .name("step-1")
            .forward(context -> {
                events.add("step-1-forward");
                return StepOutcome.success();
            })
            .rollback(context -> {
                events.add("step-1-rollback");
                throw rollbackFailure;
            })
            .build();

        Step<TestContext> step2 = Step.of("step-2", context -> {
            events.add("step-2-forward");
            throw serviceException;
        });

        Workflow<TestContext> workflow = Workflow.<TestContext>builder()
            .defaultRetryPolicy(RetryPolicy.noRetry())
            .steps(step1, step2)
            .build();

        TestContext context = new TestContext();

        TestServiceException thrown = assertThrows(
            TestServiceException.class,
            () -> workflow.run(context)
        );

        assertSame(serviceException, thrown);
        assertEquals(
            List.of(
                "step-1-forward",
                "step-2-forward",
                "step-1-rollback"
            ),
            events
        );

        Throwable[] suppressed = thrown.getSuppressed();
        assertEquals(1, suppressed.length);
        assertNotNull(suppressed[0]);
    }

    @Test
    void shouldNotRetryOnServiceException() {
        TestServiceException serviceException =
            new TestServiceException("INVALID_REQUEST", "request is invalid");

        AtomicInteger attempts = new AtomicInteger();

        Step<TestContext> step = Step.of("step", context -> {
            attempts.incrementAndGet();
            throw serviceException;
        });

        Workflow<TestContext> workflow = Workflow.<TestContext>builder()
            .defaultRetryPolicy(
                // NO retry on ServiceException, even with retry on all throwable
                RetryPolicy.exponentialBackoff(3, Duration.ofMillis(100), t -> true))
            .step(step)
            .build();

        TestContext context = new TestContext();

        TestServiceException thrown = assertThrows(
            TestServiceException.class,
            () -> workflow.run(context)
        );

        assertSame(serviceException, thrown);

        // key assertion: only executed once
        assertEquals(1, attempts.get());
    }

    private static final class TestContext {
    }

    private static final class TestServiceException extends ServiceException {
        TestServiceException(String errorCode, String message) {
            super(errorCode, message);
        }
    }
}