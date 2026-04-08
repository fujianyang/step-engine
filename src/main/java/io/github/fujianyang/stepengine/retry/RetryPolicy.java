package io.github.fujianyang.stepengine.retry;

import java.time.Duration;

public interface RetryPolicy {

    /**
     * Returns true if the given exception should be retried for the given attempt.
     *
     * @param throwable the exception thrown by the step
     * @param attemptNumber the current attempt number, starting from 1
     */
    boolean shouldRetry(Throwable throwable, int attemptNumber);

    /**
     * Returns the delay before the next retry attempt.
     *
     * @param attemptNumber the current attempt number, starting from 1
     */
    Duration backoffDelay(int attemptNumber);
}