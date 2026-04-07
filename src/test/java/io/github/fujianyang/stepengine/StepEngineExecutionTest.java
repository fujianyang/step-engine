package io.github.fujianyang.stepengine;

import io.github.fujianyang.stepengine.exception.ServiceException;
import io.github.fujianyang.stepengine.exception.WorkflowException;
import io.github.fujianyang.stepengine.retry.ExponentialBackoffRetryPolicy;
import io.github.fujianyang.stepengine.retry.NoRetryPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StepEngineExecutionTest {

    @Test
    void shouldExecuteAllStepsSuccessfully() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1", ctx -> {
                ctx.events.add("step-1");
                ctx.value = "a";
            })
            .step("step-2", ctx -> {
                ctx.events.add("step-2");
                ctx.value = ctx.value + "b";
            })
            .build();

        engine.execute(context);

        assertEquals(List.of("step-1", "step-2"), context.events);
        assertEquals("ab", context.value);
    }

    @Test
    void shouldWrapUnexpectedExceptionInWorkflowException() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("call-downstream", ctx -> {
                throw new IOException("boom");
            })
            .retryPolicy(new NoRetryPolicy())
            .build();

        WorkflowException exception = assertThrows(
            WorkflowException.class,
            () -> engine.execute(context)
        );

        assertEquals("Workflow failed at step 'call-downstream'", exception.getMessage());
        assertInstanceOf(IOException.class, exception.getCause());
        assertEquals("boom", exception.getCause().getMessage());
    }

    @Test
    void shouldRetryAndEventuallySucceed() {
        TestContext context = new TestContext();
        AtomicInteger attempts = new AtomicInteger();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("unstable-step", ctx -> {
                int current = attempts.incrementAndGet();
                ctx.events.add("attempt-" + current);
                if (current < 3) {
                    throw new IOException("temporary failure");
                }
                ctx.value = "success";
            })
            .retryPolicy(
                ExponentialBackoffRetryPolicy.builder()
                    .maxAttempts(3)
                    .initialDelay(Duration.ZERO)
                    .maxDelay(Duration.ZERO)
                    .multiplier(2.0)
                    .jitterEnabled(false)
                    .retryOn(t -> t instanceof IOException)
                    .build()
            )
            .build();

        engine.execute(context);

        assertEquals(3, attempts.get());
        assertEquals("success", context.value);
        assertEquals(List.of("attempt-1", "attempt-2", "attempt-3"), context.events);
    }

    @Test
    void shouldFailWhenRetriesAreExhausted() {
        TestContext context = new TestContext();
        AtomicInteger attempts = new AtomicInteger();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("unstable-step", ctx -> {
                attempts.incrementAndGet();
                throw new IOException("still failing");
            })
            .retryPolicy(
                ExponentialBackoffRetryPolicy.builder()
                    .maxAttempts(3)
                    .initialDelay(Duration.ZERO)
                    .maxDelay(Duration.ZERO)
                    .multiplier(2.0)
                    .jitterEnabled(false)
                    .retryOn(t -> t instanceof IOException)
                    .build()
            )
            .build();

        WorkflowException exception = assertThrows(
            WorkflowException.class,
            () -> engine.execute(context)
        );

        assertEquals(3, attempts.get());
        assertInstanceOf(IOException.class, exception.getCause());
        assertEquals("still failing", exception.getCause().getMessage());
    }

    @Test
    void shouldNotRetryServiceException() {
        TestContext context = new TestContext();
        AtomicInteger attempts = new AtomicInteger();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("validate", ctx -> {
                attempts.incrementAndGet();
                throw new InvalidRequestException("INVALID", "bad input");
            })
            .retryPolicy(
                ExponentialBackoffRetryPolicy.builder()
                    .maxAttempts(5)
                    .initialDelay(Duration.ZERO)
                    .maxDelay(Duration.ZERO)
                    .multiplier(2.0)
                    .jitterEnabled(false)
                    .retryOn(t -> true)
                    .build()
            )
            .build();

        InvalidRequestException exception = assertThrows(
            InvalidRequestException.class,
            () -> engine.execute(context)
        );

        assertEquals(1, attempts.get());
        assertEquals("bad input", exception.getMessage());
    }

    @Test
    void shouldRejectDuplicateStepNames() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> StepEngine.<TestContext>builder()
                .step("duplicate", ctx -> {})
                .step("duplicate", ctx -> {})
                .build()
        );

        assertEquals("duplicate step name: duplicate", exception.getMessage());
    }

    @Test
    void shouldRejectEmptyStepEngine() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> StepEngine.<TestContext>builder().build()
        );

        assertEquals("steps must not be empty", exception.getMessage());
    }

    @Test
    void shouldRethrowServiceExceptionAsIs() {
        TestContext context = new TestContext();
        InvalidRequestException expected = new InvalidRequestException("INVALID", "bad request");

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("validate", ctx -> {
                throw expected;
            })
            .build();

        InvalidRequestException actual = assertThrows(
            InvalidRequestException.class,
            () -> engine.execute(context)
        );

        assertSame(expected, actual);
    }

    @Test
    void shouldPreserveOriginalUnexpectedExceptionAsCause() {
        TestContext context = new TestContext();
        IOException expected = new IOException("boom");

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("call-downstream", ctx -> {
                throw expected;
            })
            .build();

        WorkflowException exception = assertThrows(
            WorkflowException.class,
            () -> engine.execute(context)
        );

        assertSame(expected, exception.getCause());
    }

    private static final class TestContext {
        private final List<String> events = new ArrayList<>();
        private String value;
    }

    private static final class InvalidRequestException extends ServiceException {
        private InvalidRequestException(String errorCode, String message) {
            super(errorCode, message);
        }
    }
}