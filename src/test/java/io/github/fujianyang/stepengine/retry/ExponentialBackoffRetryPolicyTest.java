package io.github.fujianyang.stepengine.retry;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class ExponentialBackoffRetryPolicyTest {

    @Test
    void shouldRetryWhileAttemptsRemainAndPredicateMatches() {
        RetryPolicy policy = ExponentialBackoffRetryPolicy.builder()
            .maxAttempts(3)
            .retryOn(t -> t instanceof IOException)
            .build();

        assertTrue(policy.shouldRetry(new IOException("io"), 1));
        assertTrue(policy.shouldRetry(new IOException("io"), 2));
        assertFalse(policy.shouldRetry(new IOException("io"), 3));
    }

    @Test
    void shouldNotRetryWhenPredicateDoesNotMatch() {
        RetryPolicy policy = ExponentialBackoffRetryPolicy.builder()
            .maxAttempts(3)
            .retryOn(t -> t instanceof IOException)
            .build();

        assertFalse(policy.shouldRetry(new TimeoutException("timeout"), 1));
    }

    @Test
    void shouldCalculateExponentialDelayWithoutJitter() {
        RetryPolicy policy = ExponentialBackoffRetryPolicy.builder()
            .maxAttempts(5)
            .initialDelay(Duration.ofMillis(100))
            .maxDelay(Duration.ofSeconds(5))
            .multiplier(2.0)
            .jitterEnabled(false)
            .build();

        assertEquals(Duration.ofMillis(100), policy.backoffDelay(new IOException(), 1));
        assertEquals(Duration.ofMillis(200), policy.backoffDelay(new IOException(), 2));
        assertEquals(Duration.ofMillis(400), policy.backoffDelay(new IOException(), 3));
        assertEquals(Duration.ofMillis(800), policy.backoffDelay(new IOException(), 4));
    }

    @Test
    void shouldCapDelayAtMaxDelay() {
        RetryPolicy policy = ExponentialBackoffRetryPolicy.builder()
            .maxAttempts(10)
            .initialDelay(Duration.ofMillis(500))
            .maxDelay(Duration.ofSeconds(1))
            .multiplier(3.0)
            .jitterEnabled(false)
            .build();

        assertEquals(Duration.ofMillis(500), policy.backoffDelay(new IOException(), 1));
        assertEquals(Duration.ofSeconds(1), policy.backoffDelay(new IOException(), 2));
        assertEquals(Duration.ofSeconds(1), policy.backoffDelay(new IOException(), 3));
    }

    @Test
    void shouldReturnDelayWithinRangeWhenJitterEnabled() {
        RetryPolicy policy = ExponentialBackoffRetryPolicy.builder()
            .maxAttempts(5)
            .initialDelay(Duration.ofMillis(100))
            .maxDelay(Duration.ofSeconds(5))
            .multiplier(2.0)
            .jitterEnabled(true)
            .build();

        Duration delay = policy.backoffDelay(new IOException(), 3);

        assertTrue(delay.toMillis() >= 0);
        assertTrue(delay.toMillis() <= 400);
    }

    @Test
    void shouldRejectInvalidMaxAttempts() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ExponentialBackoffRetryPolicy.builder()
                .maxAttempts(0)
        );

        assertEquals("maxAttempts must be >= 1", exception.getMessage());
    }

    @Test
    void shouldRejectNegativeInitialDelay() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ExponentialBackoffRetryPolicy.builder()
                .initialDelay(Duration.ofMillis(-1))
        );

        assertEquals("initialDelay must not be negative", exception.getMessage());
    }

    @Test
    void shouldRejectNegativeMaxDelay() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ExponentialBackoffRetryPolicy.builder()
                .maxDelay(Duration.ofMillis(-1))
        );

        assertEquals("maxDelay must not be negative", exception.getMessage());
    }

    @Test
    void shouldRejectInvalidMultiplier() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ExponentialBackoffRetryPolicy.builder()
                .multiplier(0.99)
        );

        assertEquals("multiplier must be >= 1.0", exception.getMessage());
    }

    @Test
    void shouldRejectMaxDelayLessThanInitialDelayAtBuildTime() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> ExponentialBackoffRetryPolicy.builder()
                .initialDelay(Duration.ofSeconds(2))
                .maxDelay(Duration.ofSeconds(1))
                .build()
        );

        assertEquals("maxDelay must be >= initialDelay", exception.getMessage());
    }
}