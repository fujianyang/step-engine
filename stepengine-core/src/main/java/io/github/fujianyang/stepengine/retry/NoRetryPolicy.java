package io.github.fujianyang.stepengine.retry;

import java.time.Duration;

public final class NoRetryPolicy implements RetryPolicy {

    @Override
    public boolean shouldRetry(Throwable throwable, int attemptNumber) {
        return false;
    }

    @Override
    public Duration backoffDelay(int attemptNumber) {
        return Duration.ZERO;
    }
}