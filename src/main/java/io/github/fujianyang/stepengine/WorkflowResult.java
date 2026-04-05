package io.github.fujianyang.stepengine;

import io.github.fujianyang.stepengine.result.ForwardFailure;
import io.github.fujianyang.stepengine.result.RollbackResult;

import java.util.Objects;
import java.util.Optional;

public final class WorkflowResult<C> {

    private final boolean success;
    private final C context;

    /**
     * Forward failure if the workflow didn't complete normally
     */
    private final ForwardFailure failure;

    /**
     * Rollback result if workflow failed and RollbackHandler is supplied for completed steps
     */
    private final RollbackResult rollback;

    private WorkflowResult(boolean success,
                           C context,
                           ForwardFailure failure,
                           RollbackResult rollback) {
        this.success = success;
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.failure = failure;
        this.rollback = rollback;
    }

    /**
     * Workflow completed normally
     */
    public static <C> WorkflowResult<C> success(C context) {
        return new WorkflowResult<>(true, context, null, RollbackResult.notAttempted());
    }

    /**
     * Workflow failed and rollback was not attempted
     */
    public static <C> WorkflowResult<C> failure(
        C context,
        String failedStepName,
        String failureReason,
        Throwable failureCause
    ) {
        return new WorkflowResult<>(
            false,
            context,
            new ForwardFailure(failedStepName, failureReason, failureCause),
            RollbackResult.notAttempted()
        );
    }

    /**
     * Workflow failed, rollback was attempted, and rollback completed
     */
    public static <C> WorkflowResult<C> failureWithRollback(
        C context,
        String failedStepName,
        String failureReason,
        Throwable failureCause,
        RollbackResult rollback
    ) {
        return new WorkflowResult<>(
            false,
            context,
            new ForwardFailure(failedStepName, failureReason, failureCause),
            Objects.requireNonNull(rollback, "rollback must not be null")
        );
    }

    /**
     * Workflow failed, rollback was attempted, and rollback itself failed
     */
    public static <C> WorkflowResult<C> failureWithRollbackFailure(
        C context,
        String failedStepName,
        String failureReason,
        Throwable failureCause,
        String rollbackFailedStepName,
        Throwable rollbackFailureCause
    ) {
        return new WorkflowResult<>(
            false,
            context,
            new ForwardFailure(failedStepName, failureReason, failureCause),
            RollbackResult.failed(rollbackFailedStepName, rollbackFailureCause)
        );
    }

    public boolean isSucceeded() {
        return success;
    }

    public boolean isFailed() {
        return !success;
    }

    public C context() {
        return context;
    }

    public Optional<ForwardFailure> failure() {
        return Optional.ofNullable(failure);
    }

    public RollbackResult rollback() {
        return rollback;
    }

    public Optional<String> failedStepName() {
        return failure().map(ForwardFailure::stepName);
    }

    public Optional<String> failureReason() {
        return failure().map(ForwardFailure::reason);
    }

    public Optional<Throwable> failureCause() {
        return failure().map(ForwardFailure::cause);
    }

    public boolean rollbackAttempted() {
        return rollback != null && rollback.attempted();
    }

    public boolean rollbackSucceeded() {
        return rollback != null && rollback.attempted() && rollback.failedStepName().isEmpty();
    }

    public boolean hasRollbackFailure() {
        return rollback != null && rollback.failedStepName().isPresent();
    }
}