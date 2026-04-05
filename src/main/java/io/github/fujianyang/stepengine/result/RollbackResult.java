package io.github.fujianyang.stepengine.result;

import java.util.Objects;
import java.util.Optional;

public final class RollbackResult {

    private final boolean attempted;
    private final String failedStepName;
    private final Throwable failureCause;

    private RollbackResult(boolean attempted,
                           String failedStepName,
                           Throwable failureCause) {
        this.attempted = attempted;
        this.failedStepName = failedStepName;
        this.failureCause = failureCause;
    }

    public boolean isSucceeded() {
        return attempted && failedStepName == null;
    }

    public boolean isFailed() {
        return failedStepName != null;
    }

    public boolean attempted() {
        return attempted;
    }

    public Optional<String> failedStepName() {
        return Optional.ofNullable(failedStepName);
    }

    public Optional<Throwable> failureCause() {
        return Optional.ofNullable(failureCause);
    }

    public static RollbackResult notAttempted() {
        return new RollbackResult(false, null, null);
    }

    public static RollbackResult succeeded() {
        return new RollbackResult(true, null, null);
    }

    public static RollbackResult failed(String failedStepName, Throwable failureCause) {
        if (failedStepName == null || failedStepName.isBlank()) {
            throw new IllegalArgumentException("failedStepName must not be blank");
        }
        Objects.requireNonNull(failureCause, "failureCause must not be null");
        return new RollbackResult(true, failedStepName, failureCause);
    }

}