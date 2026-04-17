package io.github.fujianyang.stepengine;

import io.github.fujianyang.stepengine.exception.ServiceException;
import io.github.fujianyang.stepengine.exception.StepTimeoutException;
import io.github.fujianyang.stepengine.retry.ExponentialBackoffRetryPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StepEngineTimeoutTest {

    @Test
    void shouldTimeoutOnSlowForward() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step(Step.<TestContext>builder()
                .name("slow-step")
                .execute(ctx -> {
                    Thread.sleep(5000);
                    ctx.events.add("should-not-reach");
                })
                .timeout(Duration.ofMillis(100))
                .build())
            .build();

        StepTimeoutException exception = assertThrows(
            StepTimeoutException.class,
            () -> engine.execute(context)
        );

        assertEquals("slow-step", exception.getStepName());
        assertEquals(Duration.ofMillis(100), exception.getTimeout());
        assertFalse(context.events.contains("should-not-reach"));
    }

    @Test
    void shouldNotTimeoutWhenStepCompletesInTime() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step(Step.<TestContext>builder()
                .name("fast-step")
                .execute(ctx -> ctx.events.add("done"))
                .timeout(Duration.ofSeconds(5))
                .build())
            .build();

        engine.execute(context);

        assertEquals(List.of("done"), context.events);
    }

    @Test
    void shouldRetryAfterTimeout() {
        TestContext context = new TestContext();
        AtomicInteger attempts = new AtomicInteger();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step(Step.<TestContext>builder()
                .name("eventually-fast")
                .execute(ctx -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 3) {
                        Thread.sleep(5000); // will timeout
                    }
                    ctx.events.add("done");
                })
                .timeout(Duration.ofMillis(100))
                .build())
            .retryPolicy(
                ExponentialBackoffRetryPolicy.builder()
                    .maxAttempts(3)
                    .initialDelay(Duration.ZERO)
                    .maxDelay(Duration.ZERO)
                    .retryOn(t -> t instanceof StepTimeoutException)
                    .build()
            )
            .build();

        engine.execute(context);

        assertEquals(3, attempts.get());
        assertEquals(List.of("done"), context.events);
    }

    @Test
    void shouldTimeoutAndFailAfterRetriesExhausted() {
        AtomicInteger attempts = new AtomicInteger();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step(Step.<TestContext>builder()
                .name("always-slow")
                .execute(ctx -> {
                    attempts.incrementAndGet();
                    Thread.sleep(5000);
                })
                .timeout(Duration.ofMillis(100))
                .build())
            .retryPolicy(
                ExponentialBackoffRetryPolicy.builder()
                    .maxAttempts(2)
                    .initialDelay(Duration.ZERO)
                    .maxDelay(Duration.ZERO)
                    .retryOn(t -> t instanceof StepTimeoutException)
                    .build()
            )
            .build();

        assertThrows(StepTimeoutException.class, () -> engine.execute(new TestContext()));
        assertEquals(2, attempts.get());
    }

    @Test
    void shouldCompensateAfterTimeout() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step(Step.<TestContext>builder()
                .name("step-1")
                .execute(ctx -> ctx.events.add("step-1-forward"))
                .compensate(ctx -> ctx.events.add("step-1-compensate"))
                .build())
            .step(Step.<TestContext>builder()
                .name("slow-step")
                .execute(ctx -> Thread.sleep(5000))
                .timeout(Duration.ofMillis(100))
                .build())
            .build();

        assertThrows(StepTimeoutException.class, () -> engine.execute(context));

        assertTrue(context.events.contains("step-1-forward"));
        assertTrue(context.events.contains("step-1-compensate"));
    }

    @Test
    void shouldTimeoutOnSlowCompensate() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step(Step.<TestContext>builder()
                .name("step-1")
                .execute(ctx -> ctx.events.add("step-1-forward"))
                .compensate(ctx -> {
                    Thread.sleep(5000); // slow compensate
                })
                .timeout(Duration.ofMillis(100))
                .build())
            .step("step-2", ctx -> {
                throw new IOException("trigger-compensate");
            })
            .build();

        IOException exception = assertThrows(
            IOException.class,
            () -> engine.execute(context)
        );

        // Compensate timeout should be attached as suppressed
        assertTrue(context.events.contains("step-1-forward"));
        Throwable[] suppressed = exception.getSuppressed();
        assertEquals(1, suppressed.length);
        assertInstanceOf(StepTimeoutException.class, suppressed[0]);
    }

    @Test
    void shouldTimeoutInParallelGroup() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .parallel(
                ParallelGroup.<TestContext>builder()
                    .step(Step.<TestContext>builder()
                        .name("fast")
                        .execute(ctx -> ctx.events.add("fast-done"))
                        .compensate(ctx -> ctx.events.add("fast-compensate"))
                        .build())
                    .step(Step.<TestContext>builder()
                        .name("slow")
                        .execute(ctx -> Thread.sleep(5000))
                        .timeout(Duration.ofMillis(100))
                        .build())
                    .build()
            )
            .build();

        StepTimeoutException exception = assertThrows(
            StepTimeoutException.class,
            () -> engine.execute(context)
        );

        assertEquals("slow", exception.getStepName());
        assertTrue(context.events.contains("fast-done"));
        assertTrue(context.events.contains("fast-compensate"));
    }

    @Test
    void shouldNotAddOverheadWithoutTimeout() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step(Step.<TestContext>builder()
                .name("no-timeout")
                .execute(ctx -> ctx.events.add("done"))
                .build())
            .build();

        engine.execute(context);

        assertEquals(List.of("done"), context.events);
    }

    @Test
    void shouldRejectZeroTimeout() {
        assertThrows(
            IllegalArgumentException.class,
            () -> Step.<TestContext>builder()
                .name("bad")
                .execute(ctx -> {})
                .timeout(Duration.ZERO)
                .build()
        );
    }

    @Test
    void shouldRejectNegativeTimeout() {
        assertThrows(
            IllegalArgumentException.class,
            () -> Step.<TestContext>builder()
                .name("bad")
                .execute(ctx -> {})
                .timeout(Duration.ofMillis(-1))
                .build()
        );
    }

    private static final class TestContext {
        private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    }
}
