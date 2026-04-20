package io.github.fujianyang.stepengine.reactor;

import io.github.fujianyang.stepengine.exception.ServiceException;
import io.github.fujianyang.stepengine.retry.ExponentialBackoffRetryPolicy;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactiveStepEngineParallelTest {

    @Test
    void shouldExecuteParallelStepsSuccessfully() {
        TestContext context = new TestContext();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step("before", ctx -> Mono.fromRunnable(() -> ctx.events.add("before")))
            .parallel(
                ReactiveParallelGroup.<TestContext>builder()
                    .step(ReactiveStep.of("a", ctx -> Mono.fromRunnable(() -> ctx.events.add("a"))))
                    .step(ReactiveStep.of("b", ctx -> Mono.fromRunnable(() -> ctx.events.add("b"))))
                    .build()
            )
            .step("after", ctx -> Mono.fromRunnable(() -> ctx.events.add("after")))
            .build();

        StepVerifier.create(engine.execute(context))
            .verifyComplete();

        assertEquals("before", context.events.getFirst());
        assertEquals("after", context.events.getLast());
        assertTrue(context.events.containsAll(List.of("a", "b")));
        assertEquals(4, context.events.size());
    }

    @Test
    void shouldExecuteParallelStepsConcurrentlyWhenGroupSchedulerIsProvided() throws InterruptedException {
        TestContext context = new TestContext();
        Scheduler groupScheduler = Schedulers.newParallel("group-scheduler", 2);
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch proceed = new CountDownLatch(1);
        Set<String> threadNames = ConcurrentHashMap.newKeySet();

        try {
            ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
                .parallel(
                    ReactiveParallelGroup.<TestContext>builder()
                        .scheduler(groupScheduler)
                        .step(ReactiveStep.of("a", ctx -> Mono.fromRunnable(() -> {
                            threadNames.add(Thread.currentThread().getName());
                            bothStarted.countDown();
                            await(proceed);
                            ctx.events.add("a");
                        })))
                        .step(ReactiveStep.of("b", ctx -> Mono.fromRunnable(() -> {
                            threadNames.add(Thread.currentThread().getName());
                            bothStarted.countDown();
                            await(proceed);
                            ctx.events.add("b");
                        })))
                        .build()
                )
                .build();

            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread runner = new Thread(() -> engine.execute(context).block(), "reactive-parallel-runner");
            runner.setUncaughtExceptionHandler((thread, throwable) -> failure.set(throwable));
            runner.start();

            assertTrue(bothStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            proceed.countDown();
            runner.join(5000);

            if (failure.get() != null) {
                throw new AssertionError("parallel run failed", failure.get());
            }

            assertEquals(2, threadNames.size());
            assertTrue(context.events.containsAll(List.of("a", "b")));
        } finally {
            groupScheduler.dispose();
        }
    }

    @Test
    void shouldCompensateCompletedParallelStepsOnFailure() {
        TestContext context = new TestContext();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .parallel(
                ReactiveParallelGroup.<TestContext>builder()
                    .step(ReactiveStep.of(
                        "a",
                        ctx -> Mono.fromRunnable(() -> ctx.events.add("a-forward")),
                        ctx -> Mono.fromRunnable(() -> ctx.events.add("a-compensate"))
                    ))
                    .step(ReactiveStep.of("b", ctx -> Mono.defer(() -> {
                        ctx.events.add("b-forward");
                        return Mono.error(new IOException("b-failed"));
                    })))
                    .build()
            )
            .build();

        StepVerifier.create(engine.execute(context))
            .expectErrorSatisfies(error -> {
                assertInstanceOf(IOException.class, error);
                assertEquals("b-failed", error.getMessage());
            })
            .verify();

        assertTrue(context.events.contains("a-forward"));
        assertTrue(context.events.contains("a-compensate"));
        assertTrue(context.events.contains("b-forward"));
        assertFalse(context.events.contains("b-compensate"));
    }

    @Test
    void shouldCompensatePriorSequentialStepsAfterParallelGroupFails() {
        TestContext context = new TestContext();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step("seq-1",
                ctx -> Mono.fromRunnable(() -> ctx.events.add("seq-1-forward")),
                ctx -> Mono.fromRunnable(() -> ctx.events.add("seq-1-compensate")))
            .parallel(
                ReactiveParallelGroup.<TestContext>builder()
                    .step(ReactiveStep.of(
                        "a",
                        ctx -> Mono.fromRunnable(() -> ctx.events.add("a-forward")),
                        ctx -> Mono.fromRunnable(() -> ctx.events.add("a-compensate"))
                    ))
                    .step(ReactiveStep.of("b", ctx -> Mono.error(new IOException("b-failed"))))
                    .build()
            )
            .build();

        StepVerifier.create(engine.execute(context))
            .expectError(IOException.class)
            .verify();

        assertTrue(context.events.contains("seq-1-forward"));
        assertTrue(context.events.contains("seq-1-compensate"));
        assertTrue(context.events.contains("a-forward"));
        assertTrue(context.events.contains("a-compensate"));
    }

    @Test
    void shouldCompensateParallelGroupWhenLaterSequentialStepFails() {
        TestContext context = new TestContext();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .parallel(
                ReactiveParallelGroup.<TestContext>builder()
                    .step(ReactiveStep.of(
                        "a",
                        ctx -> Mono.fromRunnable(() -> ctx.events.add("a-forward")),
                        ctx -> Mono.fromRunnable(() -> ctx.events.add("a-compensate"))
                    ))
                    .step(ReactiveStep.of(
                        "b",
                        ctx -> Mono.fromRunnable(() -> ctx.events.add("b-forward")),
                        ctx -> Mono.fromRunnable(() -> ctx.events.add("b-compensate"))
                    ))
                    .build()
            )
            .step("after", ctx -> Mono.error(new IOException("after-failed")))
            .build();

        StepVerifier.create(engine.execute(context))
            .expectError(IOException.class)
            .verify();

        assertTrue(context.events.containsAll(List.of("a-forward", "b-forward", "a-compensate", "b-compensate")));
    }

    @Test
    void shouldDoomGroupOnServiceException() {
        AtomicInteger bAttempts = new AtomicInteger();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .parallel(
                ReactiveParallelGroup.<TestContext>builder()
                    .step(ReactiveStep.of("a", ctx -> Mono.error(new InvalidRequestException("INVALID", "bad input"))))
                    .step(
                        ReactiveStep.<TestContext>builder()
                            .name("b")
                            .forward(ctx -> Mono.defer(() -> {
                                bAttempts.incrementAndGet();
                                return Mono.error(new IOException("transient"));
                            }))
                            .retryPolicy(
                                ExponentialBackoffRetryPolicy.builder()
                                    .maxAttempts(5)
                                    .initialDelay(Duration.ofMillis(50))
                                    .maxDelay(Duration.ofMillis(50))
                                    .retryOn(t -> t instanceof IOException)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build();

        StepVerifier.create(engine.execute(new TestContext()))
            .expectErrorSatisfies(error -> {
                assertInstanceOf(InvalidRequestException.class, error);
                assertEquals("bad input", error.getMessage());
            })
            .verify();

        assertTrue(bAttempts.get() < 5, "b should stop retrying when group is doomed, got " + bAttempts.get());
    }

    @Test
    void shouldRetryIndependentlyWithinParallelGroup() {
        TestContext context = new TestContext();
        AtomicInteger aAttempts = new AtomicInteger();
        AtomicInteger bAttempts = new AtomicInteger();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .parallel(
                ReactiveParallelGroup.<TestContext>builder()
                    .step(ReactiveStep.of("a", ctx -> Mono.defer(() -> {
                        int attempt = aAttempts.incrementAndGet();
                        if (attempt < 2) {
                            return Mono.error(new IOException("a-transient"));
                        }
                        ctx.events.add("a-done");
                        return Mono.empty();
                    })))
                    .step(ReactiveStep.of("b", ctx -> Mono.defer(() -> {
                        int attempt = bAttempts.incrementAndGet();
                        if (attempt < 3) {
                            return Mono.error(new IOException("b-transient"));
                        }
                        ctx.events.add("b-done");
                        return Mono.empty();
                    })))
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

        StepVerifier.create(engine.execute(context))
            .verifyComplete();

        assertEquals(2, aAttempts.get());
        assertEquals(3, bAttempts.get());
        assertTrue(context.events.containsAll(List.of("a-done", "b-done")));
    }

    @Test
    void shouldSuppressMultipleFailures() {
        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .parallel(
                ReactiveParallelGroup.<TestContext>builder()
                    .step(ReactiveStep.of("a", ctx -> Mono.error(new IOException("a-failed"))))
                    .step(ReactiveStep.of("b", ctx -> Mono.error(new IOException("b-failed"))))
                    .build()
            )
            .build();

        StepVerifier.create(engine.execute(new TestContext()))
            .expectErrorSatisfies(error -> {
                assertInstanceOf(IOException.class, error);
                assertEquals(1, error.getSuppressed().length);
                Set<String> messages = Set.of(error.getMessage(), error.getSuppressed()[0].getMessage());
                assertEquals(Set.of("a-failed", "b-failed"), messages);
            })
            .verify();
    }

    @Test
    void shouldUseGroupSchedulerForStepsWithoutOwnScheduler() {
        Scheduler groupScheduler = Schedulers.newSingle("group-scheduler");
        List<String> threadNames = Collections.synchronizedList(new ArrayList<>());

        try {
            ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
                .parallel(
                    ReactiveParallelGroup.<TestContext>builder()
                        .scheduler(groupScheduler)
                        .step(ReactiveStep.of("a", ctx -> Mono.fromRunnable(() ->
                            threadNames.add(Thread.currentThread().getName()))))
                        .step(ReactiveStep.of("b", ctx -> Mono.fromRunnable(() ->
                            threadNames.add(Thread.currentThread().getName()))))
                        .build()
                )
                .build();

            StepVerifier.create(engine.execute(new TestContext()))
                .verifyComplete();

            assertEquals(2, threadNames.size());
            assertTrue(threadNames.stream().allMatch(name -> name.startsWith("group-scheduler-")));
        } finally {
            groupScheduler.dispose();
        }
    }

    @Test
    void shouldPreferStepSchedulerOverGroupScheduler() {
        Scheduler groupScheduler = Schedulers.newSingle("group-scheduler");
        Scheduler stepScheduler = Schedulers.newSingle("step-scheduler");
        Map<String, String> threadNames = new ConcurrentHashMap<>();

        try {
            ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
                .parallel(
                    ReactiveParallelGroup.<TestContext>builder()
                        .scheduler(groupScheduler)
                        .step(
                            ReactiveStep.<TestContext>builder()
                                .name("a")
                                .scheduler(stepScheduler)
                                .forward(ctx -> Mono.fromRunnable(() ->
                                    threadNames.put("a", Thread.currentThread().getName())))
                                .build()
                        )
                        .step(ReactiveStep.of("b", ctx -> Mono.fromRunnable(() ->
                            threadNames.put("b", Thread.currentThread().getName()))))
                        .build()
                )
                .build();

            StepVerifier.create(engine.execute(new TestContext()))
                .verifyComplete();

            assertTrue(threadNames.get("a").startsWith("step-scheduler-"));
            assertTrue(threadNames.get("b").startsWith("group-scheduler-"));
        } finally {
            stepScheduler.dispose();
            groupScheduler.dispose();
        }
    }

    private static final class TestContext {
        private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    }

    private static final class InvalidRequestException extends ServiceException {
        private InvalidRequestException(String errorCode, String message) {
            super(errorCode, message);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            throw new RuntimeException(exception);
        }
    }
}
