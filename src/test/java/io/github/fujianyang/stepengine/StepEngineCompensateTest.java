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

class StepEngineCompensateTest {

    @Test
    void shouldCompensateCompletedStepsInReverseOrderOnServiceException() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> ctx.events.add("compensate-step-1"))
            .step("step-2",
                ctx -> ctx.events.add("execute-step-2"),
                ctx -> ctx.events.add("compensate-step-2"))
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
                "compensate-step-2",
                "compensate-step-1"
            ),
            context.events
        );
    }

    @Test
    void shouldCompensateCompletedStepsInReverseOrderOnUnexpectedException() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> ctx.events.add("compensate-step-1"))
            .step("step-2",
                ctx -> ctx.events.add("execute-step-2"),
                ctx -> ctx.events.add("compensate-step-2"))
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
                "compensate-step-2",
                "compensate-step-1"
            ),
            context.events
        );
    }

    @Test
    void shouldSkipStepsWithoutCompensateHandler() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> ctx.events.add("compensate-step-1"))
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
                "compensate-step-1"
            ),
            context.events
        );
    }

    @Test
    void shouldAttachCompensateFailureAsSuppressedExceptionToServiceException() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> {
                    ctx.events.add("compensate-step-1");
                    throw new IllegalStateException("compensate failed");
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
    void shouldAttachCompensateFailureAsSuppressedExceptionToOriginalException() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> {
                    ctx.events.add("compensate-step-1");
                    throw new IllegalStateException("compensate failed");
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
            "compensate failed",
            exception.getSuppressed()[0].getMessage()
        );
    }

    @Test
    void shouldPreserveOriginalServiceExceptionWhenCompensateFails() {
        TestContext context = new TestContext();
        InvalidRequestException expected = new InvalidRequestException("INVALID", "bad input");

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> {
                    ctx.events.add("compensate-step-1");
                    throw new IllegalStateException("compensate failed");
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
    void shouldPreserveOriginalUnexpectedExceptionWhenCompensateFails() {
        TestContext context = new TestContext();
        IOException expected = new IOException("downstream fail");

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> {
                    ctx.events.add("compensate-step-1");
                    throw new IllegalStateException("compensate failed");
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
    void shouldRetryCompensateWhenCompensateRetryPolicyIsSet() {
        TestContext context = new TestContext();
        AtomicInteger compensateAttempts = new AtomicInteger(0);

        Step<TestContext> step1 = Step.<TestContext>builder()
            .name("step-1")
            .forward(ctx -> ctx.events.add("execute-step-1"))
            .compensate(ctx -> {
                int attempt = compensateAttempts.incrementAndGet();
                ctx.events.add("compensate-step-1-attempt-" + attempt);
                if (attempt < 3) {
                    throw new IOException("compensate transient failure");
                }
            })
            .compensateRetryPolicy(new ImmediateRetryPolicy(3))
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
                "compensate-step-1-attempt-1",
                "compensate-step-1-attempt-2",
                "compensate-step-1-attempt-3"
            ),
            context.events
        );
        assertEquals(3, compensateAttempts.get());
    }

    @Test
    void shouldNotRetryCompensateWhenNoCompensateRetryPolicyIsSet() {
        TestContext context = new TestContext();
        AtomicInteger compensateAttempts = new AtomicInteger(0);

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> {
                    compensateAttempts.incrementAndGet();
                    ctx.events.add("compensate-step-1");
                    throw new IOException("compensate failure");
                })
            .step("step-2", ctx -> {
                ctx.events.add("execute-step-2");
                throw new IOException("step-2 failure");
            })
            .build();

        IOException exception = assertThrows(IOException.class, () -> engine.execute(context));

        assertEquals(1, compensateAttempts.get());
        assertEquals(1, exception.getSuppressed().length);
    }

    @Test
    void shouldSuppressCompensateExceptionAfterRetriesExhausted() {
        TestContext context = new TestContext();
        AtomicInteger compensateAttempts = new AtomicInteger(0);

        Step<TestContext> step1 = Step.<TestContext>builder()
            .name("step-1")
            .forward(ctx -> ctx.events.add("execute-step-1"))
            .compensate(ctx -> {
                compensateAttempts.incrementAndGet();
                throw new IOException("compensate always fails");
            })
            .compensateRetryPolicy(new ImmediateRetryPolicy(2))
            .build();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step(step1)
            .step("step-2", ctx -> {
                ctx.events.add("execute-step-2");
                throw new IOException("step-2 failure");
            })
            .build();

        IOException exception = assertThrows(IOException.class, () -> engine.execute(context));

        assertEquals(2, compensateAttempts.get());
        assertEquals(1, exception.getSuppressed().length);
        assertEquals("compensate always fails", exception.getSuppressed()[0].getMessage());
    }

    @Test
    void shouldRetryCompensateIndependentlyFromForwardRetryPolicy() {
        TestContext context = new TestContext();
        AtomicInteger forwardAttempts = new AtomicInteger(0);
        AtomicInteger compensateAttempts = new AtomicInteger(0);

        Step<TestContext> step1 = Step.<TestContext>builder()
            .name("step-1")
            .forward(ctx -> ctx.events.add("execute-step-1"))
            .compensate(ctx -> {
                int attempt = compensateAttempts.incrementAndGet();
                if (attempt < 2) {
                    throw new IOException("compensate transient failure");
                }
                ctx.events.add("compensate-step-1");
            })
            .retryPolicy(new ImmediateRetryPolicy(5))
            .compensateRetryPolicy(new ImmediateRetryPolicy(2))
            .build();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step(step1)
            .step("step-2", ctx -> {
                ctx.events.add("execute-step-2");
                throw new IOException("step-2 failure");
            })
            .build();

        assertThrows(IOException.class, () -> engine.execute(context));

        assertEquals(2, compensateAttempts.get());
        assertTrue(context.events.contains("compensate-step-1"));
    }

    @Test
    void shouldContinueCompensationWhenConfiguredAsContinue() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> ctx.events.add("compensate-step-1"))
            .step("step-2",
                ctx -> ctx.events.add("execute-step-2"),
                ctx -> {
                    ctx.events.add("compensate-step-2");
                    throw new IllegalStateException("compensate-step-2 failed");
                })
            .step("step-3", ctx -> {
                ctx.events.add("execute-step-3");
                throw new IOException("step-3 failure");
            })
            .compensateOnError(CompensateOnError.CONTINUE)
            .build();

        IOException exception = assertThrows(IOException.class, () -> engine.execute(context));

        assertEquals(
            List.of(
                "execute-step-1",
                "execute-step-2",
                "execute-step-3",
                "compensate-step-2",
                "compensate-step-1"
            ),
            context.events
        );
        assertEquals(1, exception.getSuppressed().length);
        assertInstanceOf(IllegalStateException.class, exception.getSuppressed()[0]);
    }

    @Test
    void shouldStopCompensationOnFailureForSequentialSteps() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> ctx.events.add("execute-step-1"),
                ctx -> ctx.events.add("compensate-step-1"))
            .step("step-2",
                ctx -> ctx.events.add("execute-step-2"),
                ctx -> {
                    ctx.events.add("compensate-step-2");
                    throw new IllegalStateException("compensate-step-2 failed");
                })
            .step("step-3", ctx -> {
                ctx.events.add("execute-step-3");
                throw new IOException("step-3 failure");
            })
            .build();

        IOException exception = assertThrows(IOException.class, () -> engine.execute(context));

        // step-2 compensation fails, so step-1 compensation is never attempted
        assertEquals(
            List.of(
                "execute-step-1",
                "execute-step-2",
                "execute-step-3",
                "compensate-step-2"
            ),
            context.events
        );
        assertEquals(1, exception.getSuppressed().length);
        assertInstanceOf(IllegalStateException.class, exception.getSuppressed()[0]);
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
