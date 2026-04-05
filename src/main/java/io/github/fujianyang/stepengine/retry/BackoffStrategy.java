package io.github.fujianyang.stepengine.retry;

import java.time.Duration;

@FunctionalInterface
public interface BackoffStrategy {

    /**
     * @param attempt next retry attempt number starting from 1
     */
    Duration computeDelay(int attempt);
}