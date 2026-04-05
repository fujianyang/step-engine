package io.github.fujianyang.stepengine.result;

public record ForwardFailure(
    String stepName,
    String reason,
    Throwable cause
) {
    public ForwardFailure {
        if (stepName == null || stepName.isBlank()) {
            throw new IllegalArgumentException("stepName must not be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}