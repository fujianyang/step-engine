package io.github.fujianyang.stepengine.reactor;

import io.github.fujianyang.stepengine.exception.StepTimeoutException;
import io.github.fujianyang.stepengine.retry.ExponentialBackoffRetryPolicy;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactiveStepEngineTimeoutTest {

    @Test
    void shouldNotTimeoutWhenStepCompletesInTime() {
        TestContext context = new TestContext();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step(
                ReactiveStep.<TestContext>builder()
                    .name("fast-step")
                    .forward(ctx -> Mono.fromRunnable(() -> ctx.events.add("done")))
                    .timeout(Duration.ofSeconds(5))
                    .build()
            )
            .build();

        StepVerifier.create(engine.execute(context))
            .verifyComplete();

        assertEquals(List.of("done"), context.events);
    }

    @Test
    void shouldRetryAfterTimeout() {
        TestContext context = new TestContext();
        AtomicInteger attempts = new AtomicInteger();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step(
                ReactiveStep.<TestContext>builder()
                    .name("eventually-fast")
                    .forward(ctx -> Mono.defer(() -> {
                        int attempt = attempts.incrementAndGet();
                        if (attempt < 3) {
                            return Mono.delay(Duration.ofSeconds(5)).then();
                        }
                        ctx.events.add("done");
                        return Mono.empty();
                    }))
                    .timeout(Duration.ofMillis(100))
                    .build()
            )
            .retryPolicy(
                ExponentialBackoffRetryPolicy.builder()
                    .maxAttempts(3)
                    .initialDelay(Duration.ZERO)
                    .maxDelay(Duration.ZERO)
                    .retryOn(t -> t instanceof StepTimeoutException)
                    .build()
            )
            .build();

        StepVerifier.create(engine.execute(context))
            .verifyComplete();

        assertEquals(3, attempts.get());
        assertEquals(List.of("done"), context.events);
    }

    @Test
    void shouldTimeoutAndFailAfterRetriesExhausted() {
        AtomicInteger attempts = new AtomicInteger();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step(
                ReactiveStep.<TestContext>builder()
                    .name("always-slow")
                    .forward(ctx -> Mono.defer(() -> {
                        attempts.incrementAndGet();
                        return Mono.delay(Duration.ofSeconds(5)).then();
                    }))
                    .timeout(Duration.ofMillis(100))
                    .build()
            )
            .retryPolicy(
                ExponentialBackoffRetryPolicy.builder()
                    .maxAttempts(2)
                    .initialDelay(Duration.ZERO)
                    .maxDelay(Duration.ZERO)
                    .retryOn(t -> t instanceof StepTimeoutException)
                    .build()
            )
            .build();

        StepVerifier.create(engine.execute(new TestContext()))
            .expectError(StepTimeoutException.class)
            .verify();

        assertEquals(2, attempts.get());
    }

    @Test
    void shouldCompensateAfterTimeout() {
        TestContext context = new TestContext();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step(
                ReactiveStep.<TestContext>builder()
                    .name("step-1")
                    .forward(ctx -> Mono.fromRunnable(() -> ctx.events.add("step-1-forward")))
                    .compensate(ctx -> Mono.fromRunnable(() -> ctx.events.add("step-1-compensate")))
                    .build()
            )
            .step(
                ReactiveStep.<TestContext>builder()
                    .name("slow-step")
                    .forward(ctx -> Mono.delay(Duration.ofSeconds(5)).then())
                    .timeout(Duration.ofMillis(100))
                    .build()
            )
            .build();

        StepVerifier.create(engine.execute(context))
            .expectError(StepTimeoutException.class)
            .verify();

        assertTrue(context.events.contains("step-1-forward"));
        assertTrue(context.events.contains("step-1-compensate"));
    }

    @Test
    void shouldTimeoutOnSlowCompensate() {
        TestContext context = new TestContext();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step(
                ReactiveStep.<TestContext>builder()
                    .name("step-1")
                    .forward(ctx -> Mono.fromRunnable(() -> ctx.events.add("step-1-forward")))
                    .compensate(ctx -> Mono.delay(Duration.ofSeconds(5)).then())
                    .timeout(Duration.ofMillis(100))
                    .build()
            )
            .step("step-2", ctx -> Mono.error(new IOException("trigger-compensate")))
            .build();

        StepVerifier.create(engine.execute(context))
            .expectErrorSatisfies(error -> {
                assertInstanceOf(IOException.class, error);
                assertTrue(context.events.contains("step-1-forward"));
                assertEquals(1, error.getSuppressed().length);
                assertInstanceOf(StepTimeoutException.class, error.getSuppressed()[0]);
            })
            .verify();
    }

    @Test
    void shouldTimeoutInParallelGroup() {
        TestContext context = new TestContext();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .parallel(
                ReactiveParallelGroup.<TestContext>builder()
                    .step(
                        ReactiveStep.<TestContext>builder()
                            .name("fast")
                            .forward(ctx -> Mono.fromRunnable(() -> ctx.events.add("fast-done")))
                            .compensate(ctx -> Mono.fromRunnable(() -> ctx.events.add("fast-compensate")))
                            .build()
                    )
                    .step(
                        ReactiveStep.<TestContext>builder()
                            .name("slow")
                            .forward(ctx -> Mono.delay(Duration.ofSeconds(5)).then())
                            .timeout(Duration.ofMillis(100))
                            .build()
                    )
                    .build()
            )
            .build();

        StepVerifier.create(engine.execute(context))
            .expectErrorSatisfies(error -> {
                assertInstanceOf(StepTimeoutException.class, error);
                StepTimeoutException timeoutException = (StepTimeoutException) error;
                assertEquals("slow", timeoutException.getStepName());
                assertTrue(context.events.contains("fast-done"));
                assertTrue(context.events.contains("fast-compensate"));
            })
            .verify();
    }

    @Test
    void shouldNotAddOverheadWithoutTimeout() {
        TestContext context = new TestContext();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step(
                ReactiveStep.<TestContext>builder()
                    .name("no-timeout")
                    .forward(ctx -> Mono.fromRunnable(() -> ctx.events.add("done")))
                    .build()
            )
            .build();

        StepVerifier.create(engine.execute(context))
            .verifyComplete();

        assertEquals(List.of("done"), context.events);
        assertFalse(context.events.isEmpty());
    }

    private static final class TestContext {
        private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    }
}
