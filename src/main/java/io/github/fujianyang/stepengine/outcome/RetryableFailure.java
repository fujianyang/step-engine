package io.github.fujianyang.stepengine.outcome;

public record RetryableFailure(String reason, Throwable cause) implements StepOutcome {
    public RetryableFailure {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}