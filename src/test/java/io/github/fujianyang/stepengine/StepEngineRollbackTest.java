package io.github.fujianyang.stepengine;

import io.github.fujianyang.stepengine.exception.ServiceException;
import io.github.fujianyang.stepengine.retry.NoRetryPolicy;
import io.github.fujianyang.stepengine.retry.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StepEngineRollbackTest {

    @Test
    void shouldRollbackCompletedStepsInReverseOrderOnServiceException() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> ctx.events.add("rollback-step-1"))
            .step("step-2",
                ctx -> ctx.events.add("execute-step-2"),
                ctx -> ctx.events.add("rollback-step-2"))
            .step("step-3", ctx -> {
                ctx.events.add("execute-step-3");
                throw new InvalidRequestException("INVALID", "bad input");
            })
            .retryPolicy(new NoRetryPolicy())
            .build();

        InvalidRequestException exception = assertThrows(
            InvalidRequestException.class,
            () -> engine.execute(context)
        );

        assertEquals("bad input", exception.getMessage());
        assertEquals(
            List.of(
                "execute-step-1",
                "execute-step-2",
                "execute-step-3",
                "rollback-step-2",
                "rollback-step-1"
            ),
            context.events
        );
    }

    @Test
    void shouldRollbackCompletedStepsInReverseOrderOnUnexpectedException() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> ctx.events.add("rollback-step-1"))
            .step("step-2",
                ctx -> ctx.events.add("execute-step-2"),
                ctx -> ctx.events.add("rollback-step-2"))
            .step("step-3", ctx -> {
                ctx.events.add("execute-step-3");
                throw new IOException("downstream failure");
            })
            .retryPolicy(new NoRetryPolicy())
            .build();

        assertThrows(
            IOException.class,
            () -> engine.execute(context)
        );

        assertEquals(
            List.of(
                "execute-step-1",
                "execute-step-2",
                "execute-step-3",
                "rollback-step-2",
                "rollback-step-1"
            ),
            context.events
        );
    }

    @Test
    void shouldSkipStepsWithoutRollbackHandler() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> ctx.events.add("rollback-step-1"))
            .step("step-2", ctx -> ctx.events.add("execute-step-2"))
            .step("step-3", ctx -> {
                ctx.events.add("execute-step-3");
                throw new IOException("fail");
            })
            .build();

        assertThrows(IOException.class, () -> engine.execute(context));

        assertEquals(
            List.of(
                "execute-step-1",
                "execute-step-2",
                "execute-step-3",
                "rollback-step-1"
            ),
            context.events
        );
    }

    @Test
    void shouldAttachRollbackFailureAsSuppressedExceptionToServiceException() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> {
                    ctx.events.add("rollback-step-1");
                    throw new IllegalStateException("rollback failed");
                })
            .step("step-2", ctx -> {
                ctx.events.add("execute-step-2");
                throw new InvalidRequestException("INVALID", "bad input");
            })
            .build();

        InvalidRequestException exception = assertThrows(
            InvalidRequestException.class,
            () -> engine.execute(context)
        );

        assertEquals(1, exception.getSuppressed().length);
        assertInstanceOf(IllegalStateException.class, exception.getSuppressed()[0]);
    }

    @Test
    void shouldAttachRollbackFailureAsSuppressedExceptionToWorkflowException() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> {
                    ctx.events.add("rollback-step-1");
                    throw new IllegalStateException("rollback failed");
                })
            .step("step-2", ctx -> {
                ctx.events.add("execute-step-2");
                throw new IOException("downstream fail");
            })
            .build();

        IOException exception = assertThrows(
            IOException.class,
            () -> engine.execute(context)
        );

        assertEquals(1, exception.getSuppressed().length);
        assertInstanceOf(IllegalStateException.class, exception.getSuppressed()[0]);
        assertEquals(
            "rollback failed",
            exception.getSuppressed()[0].getMessage()
        );
    }

    @Test
    void shouldPreserveOriginalServiceExceptionWhenRollbackFails() {
        TestContext context = new TestContext();
        InvalidRequestException expected = new InvalidRequestException("INVALID", "bad input");

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> {
                    ctx.events.add("rollback-step-1");
                    throw new IllegalStateException("rollback failed");
                })
            .step("step-2", ctx -> {
                ctx.events.add("execute-step-2");
                throw expected;
            })
            .build();

        InvalidRequestException actual = assertThrows(
            InvalidRequestException.class,
            () -> engine.execute(context)
        );

        assertSame(expected, actual);
        assertEquals(1, actual.getSuppressed().length);
        assertInstanceOf(IllegalStateException.class, actual.getSuppressed()[0]);
    }

    @Test
    void shouldPreserveOriginalUnexpectedExceptionWhenRollbackFails() {
        TestContext context = new TestContext();
        IOException expected = new IOException("downstream fail");

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> {
                    ctx.events.add("rollback-step-1");
                    throw new IllegalStateException("rollback failed");
                })
            .step("step-2", ctx -> {
                ctx.events.add("execute-step-2");
                throw expected;
            })
            .build();

        IOException exception = assertThrows(
            IOException.class,
            () -> engine.execute(context)
        );

        assertSame(expected, exception);
        assertEquals(1, exception.getSuppressed().length);
        assertInstanceOf(IllegalStateException.class, exception.getSuppressed()[0]);
    }

    @Test
    void shouldRetryRollbackWhenRollbackRetryPolicyIsSet() {
        TestContext context = new TestContext();
        AtomicInteger rollbackAttempts = new AtomicInteger(0);

        Step<TestContext> step1 = Step.<TestContext>builder()
            .name("step-1")
            .execute(ctx -> ctx.events.add("execute-step-1"))
            .rollback(ctx -> {
                int attempt = rollbackAttempts.incrementAndGet();
                ctx.events.add("rollback-step-1-attempt-" + attempt);
                if (attempt < 3) {
                    throw new IOException("rollback transient failure");
                }
            })
            .rollbackRetryPolicy(new ImmediateRetryPolicy(3))
            .build();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step(step1)
            .step("step-2", ctx -> {
                ctx.events.add("execute-step-2");
                throw new IOException("step-2 failure");
            })
            .build();

        assertThrows(IOException.class, () -> engine.execute(context));

        assertEquals(
            List.of(
                "execute-step-1",
                "execute-step-2",
                "rollback-step-1-attempt-1",
                "rollback-step-1-attempt-2",
                "rollback-step-1-attempt-3"
            ),
            context.events
        );
        assertEquals(3, rollbackAttempts.get());
    }

    @Test
    void shouldNotRetryRollbackWhenNoRollbackRetryPolicyIsSet() {
        TestContext context = new TestContext();
        AtomicInteger rollbackAttempts = new AtomicInteger(0);

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> {
                    rollbackAttempts.incrementAndGet();
                    ctx.events.add("rollback-step-1");
                    throw new IOException("rollback failure");
                })
            .step("step-2", ctx -> {
                ctx.events.add("execute-step-2");
                throw new IOException("step-2 failure");
            })
            .build();

        IOException exception = assertThrows(IOException.class, () -> engine.execute(context));

        assertEquals(1, rollbackAttempts.get());
        assertEquals(1, exception.getSuppressed().length);
    }

    @Test
    void shouldSuppressRollbackExceptionAfterRetriesExhausted() {
        TestContext context = new TestContext();
        AtomicInteger rollbackAttempts = new AtomicInteger(0);

        Step<TestContext> step1 = Step.<TestContext>builder()
            .name("step-1")
            .execute(ctx -> ctx.events.add("execute-step-1"))
            .rollback(ctx -> {
                rollbackAttempts.incrementAndGet();
                throw new IOException("rollback always fails");
            })
            .rollbackRetryPolicy(new ImmediateRetryPolicy(2))
            .build();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step(step1)
            .step("step-2", ctx -> {
                ctx.events.add("execute-step-2");
                throw new IOException("step-2 failure");
            })
            .build();

        IOException exception = assertThrows(IOException.class, () -> engine.execute(context));

        assertEquals(2, rollbackAttempts.get());
        assertEquals(1, exception.getSuppressed().length);
        assertEquals("rollback always fails", exception.getSuppressed()[0].getMessage());
    }

    @Test
    void shouldRetryRollbackIndependentlyFromForwardRetryPolicy() {
        TestContext context = new TestContext();
        AtomicInteger forwardAttempts = new AtomicInteger(0);
        AtomicInteger rollbackAttempts = new AtomicInteger(0);

        Step<TestContext> step1 = Step.<TestContext>builder()
            .name("step-1")
            .execute(ctx -> ctx.events.add("execute-step-1"))
            .rollback(ctx -> {
                int attempt = rollbackAttempts.incrementAndGet();
                if (attempt < 2) {
                    throw new IOException("rollback transient failure");
                }
                ctx.events.add("rollback-step-1");
            })
            .retryPolicy(new ImmediateRetryPolicy(5))
            .rollbackRetryPolicy(new ImmediateRetryPolicy(2))
            .build();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step(step1)
            .step("step-2", ctx -> {
                ctx.events.add("execute-step-2");
                throw new IOException("step-2 failure");
            })
            .build();

        assertThrows(IOException.class, () -> engine.execute(context));

        assertEquals(2, rollbackAttempts.get());
        assertTrue(context.events.contains("rollback-step-1"));
    }

    private static final class TestContext {
        private final List<String> events = new ArrayList<>();
    }

    private static final class InvalidRequestException extends ServiceException {
        private InvalidRequestException(String errorCode, String message) {
            super(errorCode, message);
        }
    }

    private static final class ImmediateRetryPolicy implements RetryPolicy {
        private final int maxAttempts;

        private ImmediateRetryPolicy(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        @Override
        public boolean shouldRetry(Throwable throwable, int attemptNumber) {
            return attemptNumber < maxAttempts;
        }

        @Override
        public Duration backoffDelay(int attemptNumber) {
            return Duration.ZERO;
        }
    }
}