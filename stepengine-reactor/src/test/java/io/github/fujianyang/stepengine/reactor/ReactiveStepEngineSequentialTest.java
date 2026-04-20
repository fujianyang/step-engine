package io.github.fujianyang.stepengine.reactor;

import io.github.fujianyang.stepengine.CompensateOnError;
import io.github.fujianyang.stepengine.exception.ServiceException;
import io.github.fujianyang.stepengine.exception.StepTimeoutException;
import io.github.fujianyang.stepengine.retry.ExponentialBackoffRetryPolicy;
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

class ReactiveStepEngineSequentialTest {

    @Test
    void shouldExecuteSequentialStepsSuccessfully() {
        TestContext context = new TestContext();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step("step-1", ctx -> Mono.fromRunnable(() -> {
                ctx.events.add("step-1");
                ctx.value = "a";
            }))
            .step("step-2", ctx -> Mono.fromRunnable(() -> {
                ctx.events.add("step-2");
                ctx.value = ctx.value + "b";
            }))
            .build();

        StepVerifier.create(engine.execute(context))
            .verifyComplete();

        assertEquals(List.of("step-1", "step-2"), context.events);
        assertEquals("ab", context.value);
    }

    @Test
    void shouldRetryAndEventuallySucceed() {
        TestContext context = new TestContext();
        AtomicInteger attempts = new AtomicInteger();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step("unstable-step", ctx -> Mono.defer(() -> {
                int current = attempts.incrementAndGet();
                ctx.events.add("attempt-" + current);
                if (current < 3) {
                    return Mono.error(new IOException("temporary failure"));
                }
                ctx.value = "success";
                return Mono.empty();
            }))
            .retryPolicy(
                ExponentialBackoffRetryPolicy.builder()
                    .maxAttempts(3)
                    .initialDelay(Duration.ZERO)
                    .maxDelay(Duration.ZERO)
                    .retryOn(t -> t instanceof IOException)
                    .build()
            )
            .build();

        StepVerifier.create(engine.execute(context))
            .verifyComplete();

        assertEquals(3, attempts.get());
        assertEquals("success", context.value);
        assertEquals(List.of("attempt-1", "attempt-2", "attempt-3"), context.events);
    }

    @Test
    void shouldFailWhenRetriesAreExhausted() {
        AtomicInteger attempts = new AtomicInteger();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step("unstable-step", ctx -> Mono.defer(() -> {
                attempts.incrementAndGet();
                return Mono.error(new IOException("still failing"));
            }))
            .retryPolicy(
                ExponentialBackoffRetryPolicy.builder()
                    .maxAttempts(3)
                    .initialDelay(Duration.ZERO)
                    .maxDelay(Duration.ZERO)
                    .retryOn(t -> t instanceof IOException)
                    .build()
            )
            .build();

        StepVerifier.create(engine.execute(new TestContext()))
            .expectErrorSatisfies(error -> {
                assertInstanceOf(IOException.class, error);
                assertEquals("still failing", error.getMessage());
            })
            .verify();

        assertEquals(3, attempts.get());
    }

    @Test
    void shouldNotRetryServiceException() {
        AtomicInteger attempts = new AtomicInteger();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step("validate", ctx -> Mono.defer(() -> {
                attempts.incrementAndGet();
                return Mono.error(new InvalidRequestException("INVALID", "bad input"));
            }))
            .retryPolicy(
                ExponentialBackoffRetryPolicy.builder()
                    .maxAttempts(5)
                    .initialDelay(Duration.ZERO)
                    .maxDelay(Duration.ZERO)
                    .retryOn(t -> true)
                    .build()
            )
            .build();

        StepVerifier.create(engine.execute(new TestContext()))
            .expectErrorSatisfies(error -> {
                assertInstanceOf(InvalidRequestException.class, error);
                assertEquals("bad input", error.getMessage());
            })
            .verify();

        assertEquals(1, attempts.get());
    }

    @Test
    void shouldRethrowServiceExceptionAsIs() {
        InvalidRequestException expected = new InvalidRequestException("INVALID", "bad request");

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step("validate", ctx -> Mono.error(expected))
            .build();

        StepVerifier.create(engine.execute(new TestContext()))
            .expectErrorSatisfies(error -> assertSame(expected, error))
            .verify();
    }

    @Test
    void shouldTimeoutOnSlowForward() {
        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step(
                ReactiveStep.<TestContext>builder()
                    .name("slow-step")
                    .forward(ctx -> Mono.delay(Duration.ofSeconds(5)).then())
                    .timeout(Duration.ofMillis(100))
                    .build()
            )
            .build();

        StepVerifier.create(engine.execute(new TestContext()))
            .expectErrorSatisfies(error -> {
                assertInstanceOf(StepTimeoutException.class, error);
                StepTimeoutException timeoutException = (StepTimeoutException) error;
                assertEquals("slow-step", timeoutException.getStepName());
                assertEquals(Duration.ofMillis(100), timeoutException.getTimeout());
            })
            .verify();
    }

    @Test
    void shouldCompensateCompletedSequentialStepsOnFailure() {
        TestContext context = new TestContext();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> Mono.fromRunnable(() -> ctx.events.add("execute-step-1")),
                ctx -> Mono.fromRunnable(() -> ctx.events.add("compensate-step-1")))
            .step("step-2", ctx -> Mono.defer(() -> {
                ctx.events.add("execute-step-2");
                return Mono.error(new IOException("step-2-failure"));
            }))
            .build();

        StepVerifier.create(engine.execute(context))
            .expectErrorSatisfies(error -> {
                assertInstanceOf(IOException.class, error);
                assertEquals("step-2-failure", error.getMessage());
            })
            .verify();

        assertEquals(
            List.of("execute-step-1", "execute-step-2", "compensate-step-1"),
            context.events
        );
    }

    @Test
    void shouldAttachCompensateFailureAsSuppressedException() {
        IllegalStateException compensateFailure = new IllegalStateException("compensate failed");

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> Mono.empty(),
                ctx -> Mono.error(compensateFailure))
            .step("step-2", ctx -> Mono.error(new IOException("boom")))
            .build();

        StepVerifier.create(engine.execute(new TestContext()))
            .expectErrorSatisfies(error -> {
                assertInstanceOf(IOException.class, error);
                assertEquals("boom", error.getMessage());
                assertEquals(1, error.getSuppressed().length);
                assertSame(compensateFailure, error.getSuppressed()[0]);
            })
            .verify();
    }

    @Test
    void shouldStopSequentialCompensationOnFirstCompensateFailureByDefault() {
        TestContext context = new TestContext();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> Mono.fromRunnable(() -> ctx.events.add("execute-step-1")),
                ctx -> Mono.fromRunnable(() -> ctx.events.add("compensate-step-1")))
            .step("step-2",
                ctx -> Mono.fromRunnable(() -> ctx.events.add("execute-step-2")),
                ctx -> Mono.defer(() -> {
                    ctx.events.add("compensate-step-2");
                    return Mono.error(new IllegalStateException("compensate-step-2-failed"));
                }))
            .step("step-3", ctx -> Mono.defer(() -> {
                ctx.events.add("execute-step-3");
                return Mono.error(new IOException("boom"));
            }))
            .build();

        StepVerifier.create(engine.execute(context))
            .expectErrorSatisfies(error -> {
                assertInstanceOf(IOException.class, error);
                assertEquals("boom", error.getMessage());
                assertEquals(1, error.getSuppressed().length);
                assertEquals("compensate-step-2-failed", error.getSuppressed()[0].getMessage());
            })
            .verify();

        assertEquals(
            List.of("execute-step-1", "execute-step-2", "execute-step-3", "compensate-step-2"),
            context.events
        );
    }

    @Test
    void shouldContinueSequentialCompensationWhenConfigured() {
        TestContext context = new TestContext();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step("step-1",
                ctx -> Mono.fromRunnable(() -> ctx.events.add("execute-step-1")),
                ctx -> Mono.fromRunnable(() -> ctx.events.add("compensate-step-1")))
            .step("step-2",
                ctx -> Mono.fromRunnable(() -> ctx.events.add("execute-step-2")),
                ctx -> Mono.defer(() -> {
                    ctx.events.add("compensate-step-2");
                    return Mono.error(new IllegalStateException("compensate-step-2-failed"));
                }))
            .step("step-3", ctx -> Mono.defer(() -> {
                ctx.events.add("execute-step-3");
                return Mono.error(new IOException("boom"));
            }))
            .compensateOnError(CompensateOnError.CONTINUE)
            .build();

        StepVerifier.create(engine.execute(context))
            .expectErrorSatisfies(error -> {
                assertInstanceOf(IOException.class, error);
                assertEquals("boom", error.getMessage());
                assertEquals(1, error.getSuppressed().length);
                assertEquals("compensate-step-2-failed", error.getSuppressed()[0].getMessage());
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
    void shouldPreserveOriginalUnexpectedExceptionInstance() {
        IOException expected = new IOException("boom");

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step("call-downstream", ctx -> Mono.error(expected))
            .build();

        StepVerifier.create(engine.execute(new TestContext()))
            .expectErrorSatisfies(error -> {
                assertSame(expected, error);
                assertTrue(error instanceof IOException);
            })
            .verify();
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
