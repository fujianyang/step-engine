package io.github.fujianyang.stepengine.reactor;

import io.github.fujianyang.stepengine.retry.ExponentialBackoffRetryPolicy;
import io.github.fujianyang.stepengine.retry.NoRetryPolicy;
import io.github.fujianyang.stepengine.retry.RetryPolicy;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactiveStepTest {

    @Test
    void shouldCreateStepWithoutCompensateUsingOf() {
        ReactiveStep<TestContext> step = ReactiveStep.of(
            "validate",
            context -> Mono.fromRunnable(() -> context.value = "ok")
        );

        assertEquals("validate", step.name());
        assertFalse(step.supportsCompensate());
        assertTrue(step.compensateHandler().isEmpty());
        assertNotNull(step.handler());
    }

    @Test
    void shouldCreateStepWithCompensateUsingOf() {
        ReactiveStep<TestContext> step = ReactiveStep.of(
            "persist",
            context -> Mono.fromRunnable(() -> context.value = "saved"),
            context -> Mono.fromRunnable(() -> context.value = null)
        );

        assertEquals("persist", step.name());
        assertTrue(step.supportsCompensate());
        assertTrue(step.compensateHandler().isPresent());
    }

    @Test
    void shouldCreateStepUsingBuilder() {
        ReactiveStep<TestContext> step = ReactiveStep.<TestContext>builder()
            .name("create-order")
            .forward(context -> Mono.fromRunnable(() -> context.value = "created"))
            .compensate(context -> Mono.fromRunnable(() -> context.value = "compensated"))
            .build();

        assertEquals("create-order", step.name());
        assertTrue(step.supportsCompensate());
        assertTrue(step.compensateHandler().isPresent());
    }

    @Test
    void shouldRejectNullName() {
        NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> ReactiveStep.of(null, context -> Mono.empty())
        );

        assertEquals("name must not be null", exception.getMessage());
    }

    @Test
    void shouldRejectBlankName() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ReactiveStep.of("   ", context -> Mono.empty())
        );

        assertEquals("name must not be blank", exception.getMessage());
    }

    @Test
    void shouldRejectNullHandler() {
        NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> ReactiveStep.of("test", null)
        );

        assertEquals("handler must not be null", exception.getMessage());
    }

    @Test
    void builderShouldRejectMissingHandler() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> ReactiveStep.<TestContext>builder()
                .name("missing-handler")
                .build()
        );

        assertEquals("step handler must be provided", exception.getMessage());
    }

    @Test
    void shouldRejectNonPositiveTimeout() {
        IllegalArgumentException zeroTimeout = assertThrows(
            IllegalArgumentException.class,
            () -> ReactiveStep.<TestContext>builder()
                .name("zero-timeout")
                .forward(context -> Mono.empty())
                .timeout(Duration.ZERO)
                .build()
        );

        IllegalArgumentException negativeTimeout = assertThrows(
            IllegalArgumentException.class,
            () -> ReactiveStep.<TestContext>builder()
                .name("negative-timeout")
                .forward(context -> Mono.empty())
                .timeout(Duration.ofMillis(-1))
                .build()
        );

        assertEquals("timeout must be positive", zeroTimeout.getMessage());
        assertEquals("timeout must be positive", negativeTimeout.getMessage());
    }

    @Test
    void shouldRejectParallelGroupWithFewerThanTwoSteps() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ReactiveParallelGroup.<TestContext>builder()
                .step(ReactiveStep.of("only-step", context -> Mono.empty()))
                .build()
        );

        assertEquals("parallel group must contain at least 2 steps", exception.getMessage());
    }

    @Test
    void shouldRejectDuplicateStepNames() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ReactiveStepEngine.<TestContext>builder()
                .step("duplicate", context -> Mono.empty())
                .step("duplicate", context -> Mono.empty())
                .build()
        );

        assertEquals("duplicate step name: duplicate", exception.getMessage());
    }

    @Test
    void shouldRejectDuplicateStepNamesAcrossParallelGroup() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ReactiveStepEngine.<TestContext>builder()
                .step("duplicate", context -> Mono.empty())
                .parallel(
                    ReactiveParallelGroup.<TestContext>builder()
                        .step(ReactiveStep.of("a", context -> Mono.empty()))
                        .step(ReactiveStep.of("duplicate", context -> Mono.empty()))
                        .build()
                )
                .build()
        );

        assertEquals("duplicate step name: duplicate", exception.getMessage());
    }

    @Test
    void shouldRejectEmptyReactiveStepEngine() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ReactiveStepEngine.<TestContext>builder().build()
        );

        assertEquals("steps must not be empty", exception.getMessage());
    }

    @Test
    void shouldUseStepLevelRetryPolicyInsteadOfEnginePolicy() {
        TestContext context = new TestContext();
        AtomicInteger attempts = new AtomicInteger();

        RetryPolicy enginePolicy = ExponentialBackoffRetryPolicy.builder()
            .maxAttempts(5)
            .initialDelay(Duration.ZERO)
            .maxDelay(Duration.ZERO)
            .retryOn(t -> true)
            .build();

        RetryPolicy stepPolicy = ExponentialBackoffRetryPolicy.builder()
            .maxAttempts(2)
            .initialDelay(Duration.ZERO)
            .maxDelay(Duration.ZERO)
            .retryOn(t -> true)
            .build();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step(
                ReactiveStep.<TestContext>builder()
                    .name("unstable-step")
                    .forward(ctx -> Mono.defer(() -> {
                        attempts.incrementAndGet();
                        return Mono.error(new IOException("fail"));
                    }))
                    .retryPolicy(stepPolicy)
                    .build()
            )
            .retryPolicy(enginePolicy)
            .build();

        StepVerifier.create(engine.execute(context))
            .expectError(IOException.class)
            .verify();

        assertEquals(2, attempts.get());
    }

    @Test
    void shouldUseEngineRetryPolicyWhenStepHasNoOverride() {
        TestContext context = new TestContext();
        AtomicInteger attempts = new AtomicInteger();

        RetryPolicy enginePolicy = ExponentialBackoffRetryPolicy.builder()
            .maxAttempts(3)
            .initialDelay(Duration.ZERO)
            .maxDelay(Duration.ZERO)
            .retryOn(t -> true)
            .build();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step("unstable-step", ctx -> Mono.defer(() -> {
                attempts.incrementAndGet();
                return Mono.error(new IOException("fail"));
            }))
            .retryPolicy(enginePolicy)
            .build();

        StepVerifier.create(engine.execute(context))
            .expectError(IOException.class)
            .verify();

        assertEquals(3, attempts.get());
    }

    @Test
    void stepLevelPolicyCanDisableRetry() {
        TestContext context = new TestContext();
        AtomicInteger attempts = new AtomicInteger();

        RetryPolicy enginePolicy = ExponentialBackoffRetryPolicy.builder()
            .maxAttempts(5)
            .initialDelay(Duration.ZERO)
            .maxDelay(Duration.ZERO)
            .retryOn(t -> true)
            .build();

        ReactiveStepEngine<TestContext> engine = ReactiveStepEngine.<TestContext>builder()
            .step(
                ReactiveStep.<TestContext>builder()
                    .name("unstable-step")
                    .forward(ctx -> Mono.defer(() -> {
                        attempts.incrementAndGet();
                        return Mono.error(new IOException("fail"));
                    }))
                    .retryPolicy(new NoRetryPolicy())
                    .build()
            )
            .retryPolicy(enginePolicy)
            .build();

        StepVerifier.create(engine.execute(context))
            .expectError(IOException.class)
            .verify();

        assertEquals(1, attempts.get());
    }

    private static final class TestContext {
        private String value;
    }
}
