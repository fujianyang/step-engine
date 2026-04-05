package io.github.fujianyang.stepengine.outcome;

public record PermanentFailure(String reason, Throwable cause) implements StepOutcome {
    public PermanentFailure {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}