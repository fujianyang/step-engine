package io.github.fujianyang.stepengine.retry;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

public final class RetryPolicy {

    private static final Duration MAX_DELAY = Duration.ofSeconds(60);

    private final int maxAttempts;
    private final BackoffStrategy backoffStrategy;
    private final Predicate<Throwable> retryOnException;

    public RetryPolicy(int maxAttempts,
                       BackoffStrategy backoffStrategy,
                       Predicate<Throwable> retryOnException) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }

        this.maxAttempts = maxAttempts;
        this.backoffStrategy = Objects.requireNonNull(backoffStrategy, "backoffStrategy must not be null");
        this.retryOnException = Objects.requireNonNull(retryOnException, "retryOnException must not be null");
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration delayBeforeRetry(int retryAttempt) {
        if (retryAttempt < 1) {
            throw new IllegalArgumentException("retryAttempt must be >= 1");
        }

        Duration delay = backoffStrategy.computeDelay(retryAttempt);
        if (delay == null) {
            throw new IllegalStateException("backoffStrategy returned null delay");
        }
        if (delay.isNegative()) {
            throw new IllegalStateException("backoffStrategy returned negative delay");
        }
        return delay;
    }

    public boolean shouldRetry(Throwable throwable) {
        return retryOnException.test(throwable);
    }

    public boolean canRetry(int attemptsUsedSoFar) {
        return attemptsUsedSoFar < maxAttempts;
    }

    public static RetryPolicy noRetry() {
        return new RetryPolicy(
            1,
            attempt -> Duration.ZERO,
            throwable -> false
        );
    }

    public static RetryPolicy exponentialBackoff(int maxAttempts, Duration baseDelay) {
        // throwable defaults to no-retry
        return exponentialBackoff(maxAttempts, baseDelay, t -> false);
    }

    public static RetryPolicy exponentialBackoff(int maxAttempts,
                                                 Duration baseDelay,
                                                 Predicate<Throwable> retryOnException) {
        Objects.requireNonNull(baseDelay, "baseDelay must not be null");
        if (baseDelay.isNegative()) {
            throw new IllegalArgumentException("baseDelay must not be negative");
        }

        return new RetryPolicy(
            maxAttempts,
            attempt -> fullJitter(cappedMultiply(baseDelay, 1L << (attempt - 1))),
            retryOnException
        );
    }

    private static Duration cappedMultiply(Duration duration, long factor) {
        try {
            Duration result = duration.multipliedBy(factor);
            return result.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : result;
        } catch (ArithmeticException e) {
            return MAX_DELAY;
        }
    }

    private static Duration fullJitter(Duration maxDelay) {
        if (maxDelay.isZero()) {
            return Duration.ZERO;
        }

        long upperBoundNanos = maxDelay.toNanos();
        long jitteredNanos = ThreadLocalRandom.current().nextLong(upperBoundNanos + 1);
        return Duration.ofNanos(jitteredNanos);
    }
}