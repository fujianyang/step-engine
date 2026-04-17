package io.github.fujianyang.stepengine;

import io.github.fujianyang.stepengine.retry.ExponentialBackoffRetryPolicy;
import io.github.fujianyang.stepengine.retry.NoRetryPolicy;
import io.github.fujianyang.stepengine.retry.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StepTest {

    @Test
    void shouldCreateStepWithoutCompensateUsingOf() {
        Step<TestContext> step = Step.of("validate", context -> context.value = "ok");

        assertEquals("validate", step.name());
        assertFalse(step.supportsCompensate());
        assertTrue(step.compensateHandler().isEmpty());
        assertNotNull(step.handler());
    }

    @Test
    void shouldCreateStepWithCompensateUsingOf() {
        Step<TestContext> step = Step.of(
            "persist",
            context -> context.value = "saved",
            context -> context.value = null
        );

        assertEquals("persist", step.name());
        assertTrue(step.supportsCompensate());
        assertTrue(step.compensateHandler().isPresent());
    }

    @Test
    void shouldCreateStepUsingBuilder() {
        Step<TestContext> step = Step.<TestContext>builder()
            .name("create-order")
            .forward(context -> context.value = "created")
            .compensate(context -> context.value = "compensated")
            .build();

        assertEquals("create-order", step.name());
        assertTrue(step.supportsCompensate());
        assertTrue(step.compensateHandler().isPresent());
    }

    @Test
    void shouldRejectNullName() {
        NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> Step.of(null, context -> {})
        );

        assertEquals("name must not be null", exception.getMessage());
    }

    @Test
    void shouldRejectBlankName() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> Step.of("   ", context -> {})
        );

        assertEquals("name must not be blank", exception.getMessage());
    }

    @Test
    void shouldRejectNullHandler() {
        NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> Step.of("test", null)
        );

        assertEquals("handler must not be null", exception.getMessage());
    }

    @Test
    void builderShouldRejectMissingHandler() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> Step.<TestContext>builder()
                .name("missing-handler")
                .build()
        );

        assertEquals("step handler must be provided", exception.getMessage());
    }

    private static final class TestContext {
        private String value;
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
            .maxAttempts(2) // override to 2 attempts
            .initialDelay(Duration.ZERO)
            .maxDelay(Duration.ZERO)
            .retryOn(t -> true)
            .build();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step(
                Step.<TestContext>builder()
                    .name("unstable-step")
                    .forward(ctx -> {
                        attempts.incrementAndGet();
                        throw new IOException("fail");
                    })
                    .retryPolicy(stepPolicy)
                    .build()
            )
            .retryPolicy(enginePolicy)
            .build();

        assertThrows(IOException.class, () -> engine.execute(context));

        // should follow step policy (2 attempts), not engine (5)
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

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step("unstable-step", ctx -> {
                attempts.incrementAndGet();
                throw new IOException("fail");
            })
            .retryPolicy(enginePolicy)
            .build();

        assertThrows(IOException.class, () -> engine.execute(context));

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

        RetryPolicy noRetry = new NoRetryPolicy();

        StepEngine<TestContext> engine = StepEngine.<TestContext>builder()
            .step(
                Step.<TestContext>builder()
                    .name("unstable-step")
                    .forward(ctx -> {
                        attempts.incrementAndGet();
                        throw new IOException("fail");
                    })
                    .retryPolicy(noRetry)
                    .build()
            )
            .retryPolicy(enginePolicy)
            .build();

        assertThrows(IOException.class, () -> engine.execute(context));

        assertEquals(1, attempts.get());
    }
}