package io.github.fujianyang.stepengine.reactor;

import io.github.fujianyang.stepengine.exception.ServiceException;
import io.github.fujianyang.stepengine.retry.RetryPolicy;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactiveStepEngineCompensateTest {

    @Test
    void shouldCompensateCompletedStepsInReverseOrderOnServiceException() {
        TestContext context = new TestContext();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> Mono.fromRunnable(() -> ctx.events.add("execute-step-1")),
                ctx -> Mono.fromRunnable(() -> ctx.events.add("compensate-step-1")))
            .step("step-2",
                ctx -> Mono.fromRunnable(() -> ctx.events.add("execute-step-2")),
                ctx -> Mono.fromRunnable(() -> ctx.events.add("compensate-step-2")))
            .step("step-3", ctx -> Mono.defer(() -> {
                ctx.events.add("execute-step-3");
                return Mono.error(new InvalidRequestException("INVALID", "bad input"));
            }))
            .build();

        StepVerifier.create(engine.execute(context))
            .expectErrorSatisfies(error -> {
                assertInstanceOf(InvalidRequestException.class, error);
                assertEquals("bad input", error.getMessage());
            })
            .verify();

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

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> Mono.fromRunnable(() -> ctx.events.add("execute-step-1")),
                ctx -> Mono.fromRunnable(() -> ctx.events.add("compensate-step-1")))
            .step("step-2",
                ctx -> Mono.fromRunnable(() -> ctx.events.add("execute-step-2")),
                ctx -> Mono.fromRunnable(() -> ctx.events.add("compensate-step-2")))
            .step("step-3", ctx -> Mono.defer(() -> {
                ctx.events.add("execute-step-3");
                return Mono.error(new IOException("downstream failure"));
            }))
            .build();

        StepVerifier.create(engine.execute(context))
            .expectError(IOException.class)
            .verify();

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

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> Mono.fromRunnable(() -> ctx.events.add("execute-step-1")),
                ctx -> Mono.fromRunnable(() -> ctx.events.add("compensate-step-1")))
            .step("step-2", ctx -> Mono.fromRunnable(() -> ctx.events.add("execute-step-2")))
            .step("step-3", ctx -> Mono.defer(() -> {
                ctx.events.add("execute-step-3");
                return Mono.error(new IOException("fail"));
            }))
            .build();

        StepVerifier.create(engine.execute(context))
            .expectError(IOException.class)
            .verify();

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

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> Mono.fromRunnable(() -> ctx.events.add("execute-step-1")),
                ctx -> Mono.defer(() -> {
                    ctx.events.add("compensate-step-1");
                    return Mono.error(new IllegalStateException("compensate failed"));
                }))
            .step("step-2", ctx -> Mono.defer(() -> {
                ctx.events.add("execute-step-2");
                return Mono.error(new InvalidRequestException("INVALID", "bad input"));
            }))
            .build();

        StepVerifier.create(engine.execute(context))
            .expectErrorSatisfies(error -> {
                assertInstanceOf(InvalidRequestException.class, error);
                assertEquals(1, error.getSuppressed().length);
                assertInstanceOf(IllegalStateException.class, error.getSuppressed()[0]);
            })
            .verify();
    }

    @Test
    void shouldPreserveOriginalServiceExceptionWhenCompensateFails() {
        TestContext context = new TestContext();
        InvalidRequestException expected = new InvalidRequestException("INVALID", "bad input");

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> Mono.fromRunnable(() -> ctx.events.add("execute-step-1")),
                ctx -> Mono.defer(() -> {
                    ctx.events.add("compensate-step-1");
                    return Mono.error(new IllegalStateException("compensate failed"));
                }))
            .step("step-2", ctx -> Mono.defer(() -> {
                ctx.events.add("execute-step-2");
                return Mono.error(expected);
            }))
            .build();

        StepVerifier.create(engine.execute(context))
            .expectErrorSatisfies(error -> {
                assertSame(expected, error);
                assertEquals(1, error.getSuppressed().length);
                assertInstanceOf(IllegalStateException.class, error.getSuppressed()[0]);
            })
            .verify();
    }

    @Test
    void shouldRetryCompensateWhenCompensateRetryPolicyIsSet() {
        TestContext context = new TestContext();
        AtomicInteger compensateAttempts = new AtomicInteger();

        ReactiveStep<TestContext> step1 = ReactiveStep.<TestContext>builder()
            .name("step-1")
            .forward(ctx -> Mono.fromRunnable(() -> ctx.events.add("execute-step-1")))
            .compensate(ctx -> Mono.defer(() -> {
                int attempt = compensateAttempts.incrementAndGet();
                ctx.events.add("compensate-step-1-attempt-" + attempt);
                if (attempt < 3) {
                    return Mono.error(new IOException("compensate transient failure"));
                }
                return Mono.empty();
            }))
            .compensateRetryPolicy(new ImmediateRetryPolicy(3))
            .build();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step(step1)
            .step("step-2", ctx -> Mono.defer(() -> {
                ctx.events.add("execute-step-2");
                return Mono.error(new IOException("step-2 failure"));
            }))
            .build();

        StepVerifier.create(engine.execute(context))
            .expectError(IOException.class)
            .verify();

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
        AtomicInteger compensateAttempts = new AtomicInteger();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> Mono.fromRunnable(() -> ctx.events.add("execute-step-1")),
                ctx -> Mono.defer(() -> {
                    compensateAttempts.incrementAndGet();
                    ctx.events.add("compensate-step-1");
                    return Mono.error(new IOException("compensate failure"));
                }))
            .step("step-2", ctx -> Mono.defer(() -> {
                ctx.events.add("execute-step-2");
                return Mono.error(new IOException("step-2 failure"));
            }))
            .build();

        StepVerifier.create(engine.execute(context))
            .expectErrorSatisfies(error -> {
                assertInstanceOf(IOException.class, error);
                assertEquals(1, error.getSuppressed().length);
            })
            .verify();

        assertEquals(1, compensateAttempts.get());
    }

    @Test
    void shouldSuppressCompensateExceptionAfterRetriesExhausted() {
        AtomicInteger compensateAttempts = new AtomicInteger();

        ReactiveStep<TestContext> step1 = ReactiveStep.<TestContext>builder()
            .name("step-1")
            .forward(ctx -> Mono.fromRunnable(() -> ctx.events.add("execute-step-1")))
            .compensate(ctx -> Mono.defer(() -> {
                compensateAttempts.incrementAndGet();
                return Mono.error(new IOException("compensate always fails"));
            }))
            .compensateRetryPolicy(new ImmediateRetryPolicy(2))
            .build();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step(step1)
            .step("step-2", ctx -> Mono.defer(() -> {
                ctx.events.add("execute-step-2");
                return Mono.error(new IOException("step-2 failure"));
            }))
            .build();

        StepVerifier.create(engine.execute(new TestContext()))
            .expectErrorSatisfies(error -> {
                assertInstanceOf(IOException.class, error);
                assertEquals(1, error.getSuppressed().length);
                assertEquals("compensate always fails", error.getSuppressed()[0].getMessage());
            })
            .verify();

        assertEquals(2, compensateAttempts.get());
    }

    @Test
    void shouldRetryCompensateIndependentlyFromForwardRetryPolicy() {
        TestContext context = new TestContext();
        AtomicInteger compensateAttempts = new AtomicInteger();

        ReactiveStep<TestContext> step1 = ReactiveStep.<TestContext>builder()
            .name("step-1")
            .forward(ctx -> Mono.fromRunnable(() -> ctx.events.add("execute-step-1")))
            .compensate(ctx -> Mono.defer(() -> {
                int attempt = compensateAttempts.incrementAndGet();
                if (attempt < 2) {
                    return Mono.error(new IOException("compensate transient failure"));
                }
                ctx.events.add("compensate-step-1");
                return Mono.empty();
            }))
            .retryPolicy(new ImmediateRetryPolicy(5))
            .compensateRetryPolicy(new ImmediateRetryPolicy(2))
            .build();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step(step1)
            .step("step-2", ctx -> Mono.defer(() -> {
                ctx.events.add("execute-step-2");
                return Mono.error(new IOException("step-2 failure"));
            }))
            .build();

        StepVerifier.create(engine.execute(context))
            .expectError(IOException.class)
            .verify();

        assertEquals(2, compensateAttempts.get());
        assertTrue(context.events.contains("compensate-step-1"));
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
