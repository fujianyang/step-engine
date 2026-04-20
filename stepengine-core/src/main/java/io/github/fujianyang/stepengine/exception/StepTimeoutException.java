package io.github.fujianyang.stepengine.exception;

import java.time.Duration;
import java.util.Objects;

public class StepTimeoutException extends RuntimeException {

    private final String stepName;
    private final Duration timeout;

    public StepTimeoutException(String stepName, Duration timeout) {
        super("Step '" + stepName + "' timed out after " + timeout.toMillis() + "ms");
        this.stepName = Objects.requireNonNull(stepName, "stepName must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
    }

    public String getStepName() {
        return stepName;
    }

    public Duration getTimeout() {
        return timeout;
    }
}
