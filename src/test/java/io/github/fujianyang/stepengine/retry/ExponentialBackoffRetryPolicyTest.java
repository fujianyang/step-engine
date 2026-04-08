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
    void shouldCalculateExponentialDelayWithJitter() {
        RetryPolicy policy = ExponentialBackoffRetryPolicy.builder()
            .maxAttempts(5)
            .initialDelay(Duration.ofMillis(100))
            .maxDelay(Duration.ofSeconds(5))
            .build();

        assertDelayInRange(policy.backoffDelay(1), 0, 100);
        assertDelayInRange(policy.backoffDelay(2), 0, 200);
        assertDelayInRange(policy.backoffDelay(3), 0, 400);
        assertDelayInRange(policy.backoffDelay(4), 0, 800);
    }

    @Test
    void shouldCapDelayAtMaxDelay() {
        RetryPolicy policy = ExponentialBackoffRetryPolicy.builder()
            .maxAttempts(10)
            .initialDelay(Duration.ofMillis(500))
            .maxDelay(Duration.ofSeconds(1))
            .build();

        assertDelayInRange(policy.backoffDelay(1), 0, 500);
        assertDelayInRange(policy.backoffDelay(2), 0, 1000);
        assertDelayInRange(policy.backoffDelay(3), 0, 1000);
    }

    @Test
    void shouldReturnDelayWithinRangeWhenJitterEnabled() {
        RetryPolicy policy = ExponentialBackoffRetryPolicy.builder()
            .maxAttempts(5)
            .initialDelay(Duration.ofMillis(100))
            .maxDelay(Duration.ofSeconds(5))
            .build();

        Duration delay = policy.backoffDelay(3);

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

    private static void assertDelayInRange(Duration actual, long minMillis, long maxMillis) {
        long actualMillis = actual.toMillis();
        assertTrue(actualMillis >= minMillis && actualMillis <= maxMillis,
            "Expected delay between " + minMillis + " and " + maxMillis + " ms, but was " + actualMillis + " ms");
    }

}