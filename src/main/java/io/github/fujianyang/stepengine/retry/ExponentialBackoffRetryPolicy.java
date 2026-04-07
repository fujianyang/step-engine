package io.github.fujianyang.stepengine.retry;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

public final class ExponentialBackoffRetryPolicy implements RetryPolicy {

    private final int maxAttempts;
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final double multiplier;
    private final boolean jitterEnabled;
    private final Predicate<Throwable> retryablePredicate;

    private ExponentialBackoffRetryPolicy(Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.initialDelay = builder.initialDelay;
        this.maxDelay = builder.maxDelay;
        this.multiplier = builder.multiplier;
        this.jitterEnabled = builder.jitterEnabled;
        this.retryablePredicate = builder.retryablePredicate;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean shouldRetry(Throwable throwable, int attemptNumber) {
        Objects.requireNonNull(throwable, "throwable must not be null");
        validateAttemptNumber(attemptNumber);

        return attemptNumber < maxAttempts && retryablePredicate.test(throwable);
    }

    @Override
    public Duration backoffDelay(Throwable throwable, int attemptNumber) {
        Objects.requireNonNull(throwable, "throwable must not be null");
        validateAttemptNumber(attemptNumber);

        long delayMillis = calculateDelayMillis(attemptNumber);

        if (jitterEnabled && delayMillis > 0) {
            delayMillis = ThreadLocalRandom.current().nextLong(delayMillis + 1);
        }

        return Duration.ofMillis(delayMillis);
    }

    private long calculateDelayMillis(int attemptNumber) {
        double exponentialMultiplier = Math.pow(multiplier, attemptNumber - 1);
        double rawDelayMillis = initialDelay.toMillis() * exponentialMultiplier;

        long boundedDelayMillis;
        if (rawDelayMillis >= Long.MAX_VALUE) {
            boundedDelayMillis = maxDelay.toMillis();
        } else {
            boundedDelayMillis = (long) rawDelayMillis;
            boundedDelayMillis = Math.min(boundedDelayMillis, maxDelay.toMillis());
        }

        return boundedDelayMillis;
    }

    private static void validateAttemptNumber(int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be >= 1");
        }
    }

    public static final class Builder {

        private int maxAttempts = 3;
        private Duration initialDelay = Duration.ofMillis(100);
        private Duration maxDelay = Duration.ofSeconds(5);
        private double multiplier = 2.0;
        private boolean jitterEnabled = true;
        private Predicate<Throwable> retryablePredicate = throwable -> true;

        private Builder() {
        }

        public Builder maxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1");
            }
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder initialDelay(Duration initialDelay) {
            Objects.requireNonNull(initialDelay, "initialDelay must not be null");
            if (initialDelay.isNegative()) {
                throw new IllegalArgumentException("initialDelay must not be negative");
            }
            this.initialDelay = initialDelay;
            return this;
        }

        public Builder maxDelay(Duration maxDelay) {
            Objects.requireNonNull(maxDelay, "maxDelay must not be null");
            if (maxDelay.isNegative()) {
                throw new IllegalArgumentException("maxDelay must not be negative");
            }
            this.maxDelay = maxDelay;
            return this;
        }

        public Builder multiplier(double multiplier) {
            if (multiplier < 1.0) {
                throw new IllegalArgumentException("multiplier must be >= 1.0");
            }
            this.multiplier = multiplier;
            return this;
        }

        public Builder jitterEnabled(boolean jitterEnabled) {
            this.jitterEnabled = jitterEnabled;
            return this;
        }

        public Builder retryOn(Predicate<Throwable> retryablePredicate) {
            this.retryablePredicate = Objects.requireNonNull(
                retryablePredicate,
                "retryablePredicate must not be null"
            );
            return this;
        }

        public ExponentialBackoffRetryPolicy build() {
            if (maxDelay.compareTo(initialDelay) < 0) {
                throw new IllegalStateException("maxDelay must be >= initialDelay");
            }

            return new ExponentialBackoffRetryPolicy(this);
        }
    }
}