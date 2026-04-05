package io.github.fujianyang.stepengine.outcome;

public sealed interface StepOutcome permits StepSuccess, RetryableFailure, PermanentFailure {

    static StepSuccess success() {
        return StepSuccess.INSTANCE;
    }

    static RetryableFailure retryableFailure(String reason) {
        return new RetryableFailure(reason, null);
    }

    static RetryableFailure retryableFailure(String reason, Throwable cause) {
        return new RetryableFailure(reason, cause);
    }

    static PermanentFailure permanentFailure(String reason) {
        return new PermanentFailure(reason, null);
    }

    static PermanentFailure permanentFailure(String reason, Throwable cause) {
        return new PermanentFailure(reason, cause);
    }
}