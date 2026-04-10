package io.github.fujianyang.stepengine;

import io.github.fujianyang.stepengine.exception.ServiceException;
import io.github.fujianyang.stepengine.retry.ExponentialBackoffRetryPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StepEngineParallelTest {

    @Test
    void shouldExecuteParallelStepsSuccessfully() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("before", ctx -> ctx.events.add("before"))
            .parallel(
                ParallelGroup.<TestContext>builder()
                    .step(Step.of("a", ctx -> ctx.events.add("a")))
                    .step(Step.of("b", ctx -> ctx.events.add("b")))
                    .build()
            )
            .step("after", ctx -> ctx.events.add("after"))
            .build();

        engine.execute(context);

        assertEquals("before", context.events.getFirst());
        assertEquals("after", context.events.getLast());
        assertTrue(context.events.containsAll(List.of("a", "b")));
        assertEquals(4, context.events.size());
    }

    @Test
    void shouldExecuteParallelStepsConcurrently() throws InterruptedException {
        TestContext context = new TestContext();
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch proceed = new CountDownLatch(1);
        Set<Long> threadIds = ConcurrentHashMap.newKeySet();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .parallel(
                ParallelGroup.<TestContext>builder()
                    .step(Step.of("a", ctx -> {
                        threadIds.add(Thread.currentThread().threadId());
                        bothStarted.countDown();
                        proceed.await();
                        ctx.events.add("a");
                    }))
                    .step(Step.of("b", ctx -> {
                        threadIds.add(Thread.currentThread().threadId());
                        bothStarted.countDown();
                        proceed.await();
                        ctx.events.add("b");
                    }))
                    .build()
            )
            .build();

        Thread runner = new Thread(() -> engine.execute(context));
        runner.start();

        bothStarted.await();
        // Both steps are running concurrently at this point
        proceed.countDown();
        runner.join(5000);

        assertEquals(2, threadIds.size(), "steps should run on different threads");
        assertTrue(context.events.containsAll(List.of("a", "b")));
    }

    @Test
    void shouldRollbackCompletedParallelStepsOnFailure() {
        TestContext context = new TestContext();
        CountDownLatch aCompleted = new CountDownLatch(1);

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .parallel(
                ParallelGroup.<TestContext>builder()
                    .step(Step.of("a",
                        ctx -> {
                            ctx.events.add("a-forward");
                            aCompleted.countDown();
                        },
                        ctx -> ctx.events.add("a-rollback")))
                    .step(Step.of("b", ctx -> {
                        aCompleted.await();
                        ctx.events.add("b-forward");
                        throw new IOException("b-failed");
                    }))
                    .build()
            )
            .build();

        IOException exception = assertThrows(
            IOException.class,
            () -> engine.execute(context)
        );

        assertEquals("b-failed", exception.getMessage());
        assertTrue(context.events.contains("a-forward"));
        assertTrue(context.events.contains("a-rollback"));
        assertTrue(context.events.contains("b-forward"));
        assertFalse(context.events.contains("b-rollback"));
    }

    @Test
    void shouldRollbackPriorSequentialStepsAfterParallelGroupFails() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("seq-1",
                ctx -> ctx.events.add("seq-1-forward"),
                ctx -> ctx.events.add("seq-1-rollback"))
            .parallel(
                ParallelGroup.<TestContext>builder()
                    .step(Step.of("a", ctx -> ctx.events.add("a-forward"),
                        ctx -> ctx.events.add("a-rollback")))
                    .step(Step.of("b", ctx -> {
                        throw new IOException("b-failed");
                    }))
                    .build()
            )
            .build();

        assertThrows(IOException.class, () -> engine.execute(context));

        assertTrue(context.events.contains("seq-1-forward"));
        assertTrue(context.events.contains("seq-1-rollback"));
        assertTrue(context.events.contains("a-forward"));
        assertTrue(context.events.contains("a-rollback"));
    }

    @Test
    void shouldRollbackParallelGroupWhenLaterSequentialStepFails() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .parallel(
                ParallelGroup.<TestContext>builder()
                    .step(Step.of("a", ctx -> ctx.events.add("a-forward"),
                        ctx -> ctx.events.add("a-rollback")))
                    .step(Step.of("b", ctx -> ctx.events.add("b-forward"),
                        ctx -> ctx.events.add("b-rollback")))
                    .build()
            )
            .step("after", ctx -> {
                throw new IOException("after-failed");
            })
            .build();

        assertThrows(IOException.class, () -> engine.execute(context));

        assertTrue(context.events.contains("a-forward"));
        assertTrue(context.events.contains("b-forward"));
        assertTrue(context.events.contains("a-rollback"));
        assertTrue(context.events.contains("b-rollback"));
    }

    @Test
    void shouldDoomGroupOnServiceException() {
        TestContext context = new TestContext();
        AtomicInteger bAttempts = new AtomicInteger();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .parallel(
                ParallelGroup.<TestContext>builder()
                    .step(Step.of("a", ctx -> {
                        throw new InvalidRequestException("INVALID", "bad input");
                    }))
                    .step(Step.<TestContext>builder()
                        .name("b")
                        .execute(ctx -> {
                            bAttempts.incrementAndGet();
                            throw new IOException("transient");
                        })
                        .retryPolicy(
                            ExponentialBackoffRetryPolicy.builder()
                                .maxAttempts(5)
                                .initialDelay(Duration.ofMillis(50))
                                .maxDelay(Duration.ofMillis(50))
                                .retryOn(t -> t instanceof IOException)
                                .build()
                        )
                        .build())
                    .build()
            )
            .build();

        InvalidRequestException exception = assertThrows(
            InvalidRequestException.class,
            () -> engine.execute(context)
        );

        assertEquals("bad input", exception.getMessage());
        assertTrue(bAttempts.get() < 5, "b should stop retrying when group is doomed, got " + bAttempts.get());
    }

    @Test
    void shouldRetryIndependentlyWithinParallelGroup() {
        TestContext context = new TestContext();
        AtomicInteger aAttempts = new AtomicInteger();
        AtomicInteger bAttempts = new AtomicInteger();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .parallel(
                ParallelGroup.<TestContext>builder()
                    .step(Step.of("a", ctx -> {
                        int attempt = aAttempts.incrementAndGet();
                        if (attempt < 2) {
                            throw new IOException("a-transient");
                        }
                        ctx.events.add("a-done");
                    }))
                    .step(Step.of("b", ctx -> {
                        int attempt = bAttempts.incrementAndGet();
                        if (attempt < 3) {
                            throw new IOException("b-transient");
                        }
                        ctx.events.add("b-done");
                    }))
                    .build()
            )
            .retryPolicy(
                ExponentialBackoffRetryPolicy.builder()
                    .maxAttempts(3)
                    .initialDelay(Duration.ZERO)
                    .maxDelay(Duration.ZERO)
                    .retryOn(t -> t instanceof IOException)
                    .build()
            )
            .build();

        engine.execute(context);

        assertEquals(2, aAttempts.get());
        assertEquals(3, bAttempts.get());
        assertTrue(context.events.containsAll(List.of("a-done", "b-done")));
    }

    @Test
    void shouldSuppressMultipleFailures() {
        TestContext context = new TestContext();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .parallel(
                ParallelGroup.<TestContext>builder()
                    .step(Step.of("a", ctx -> {
                        throw new IOException("a-failed");
                    }))
                    .step(Step.of("b", ctx -> {
                        throw new IOException("b-failed");
                    }))
                    .build()
            )
            .build();

        IOException exception = assertThrows(
            IOException.class,
            () -> engine.execute(context)
        );

        // One is primary, other is suppressed
        assertEquals(1, exception.getSuppressed().length);
        Set<String> messages = Set.of(
            exception.getMessage(),
            exception.getSuppressed()[0].getMessage()
        );
        assertEquals(Set.of("a-failed", "b-failed"), messages);
    }

    @Test
    void shouldRejectDuplicateNamesAcrossSequentialAndParallel() {
        assertThrows(
            IllegalArgumentException.class,
            () -> StepEngine.<TestContext>builder()
                .step("dup", ctx -> {})
                .parallel(
                    ParallelGroup.<TestContext>builder()
                        .step(Step.of("dup", ctx -> {}))
                        .step(Step.of("other", ctx -> {}))
                        .build()
                )
                .build()
        );
    }

    @Test
    void shouldRejectParallelGroupWithFewerThanTwoSteps() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ParallelGroup.<TestContext>builder()
                .step(Step.of("only-one", ctx -> {}))
                .build()
        );
    }

    private static final class TestContext {
        private final List<String> events = Collections.synchronizedList(new ArrayList<>());
        private String value;
    }

    private static final class InvalidRequestException extends ServiceException {
        private InvalidRequestException(String errorCode, String message) {
            super(errorCode, message);
        }
    }
}
