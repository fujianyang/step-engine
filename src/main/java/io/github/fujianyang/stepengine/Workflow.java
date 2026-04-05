package io.github.fujianyang.stepengine;

import io.github.fujianyang.stepengine.exception.WorkflowException;
import io.github.fujianyang.stepengine.handler.StepHandler;
import io.github.fujianyang.stepengine.outcome.PermanentFailure;
import io.github.fujianyang.stepengine.outcome.RetryableFailure;
import io.github.fujianyang.stepengine.outcome.StepOutcome;
import io.github.fujianyang.stepengine.outcome.StepSuccess;
import io.github.fujianyang.stepengine.result.RollbackResult;
import io.github.fujianyang.stepengine.retry.RetryPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class Workflow<C> {

    private final List<Step<C>> steps;
    private final RetryPolicy defaultRetryPolicy;

    private Workflow(Builder<C> builder) {
        this.steps = List.copyOf(builder.steps);
        if (this.steps.isEmpty()) {
            throw new IllegalArgumentException("workflow must contain at least one step");
        }
        this.defaultRetryPolicy = Objects.requireNonNull(
            builder.defaultRetryPolicy,
            "defaultRetryPolicy must not be null"
        );
    }

    public static <C> Builder<C> builder() {
        return new Builder<>();
    }

    public WorkflowResult<C> run(C context) {
        Objects.requireNonNull(context, "context must not be null");

        List<Step<C>> completedSteps = new ArrayList<>();

        for (Step<C> step : steps) {
            RetryPolicy retryPolicy = step.retryPolicy().orElse(defaultRetryPolicy);
            int attemptsUsed = 0;

            while (true) {
                attemptsUsed++;

                try {
                    StepOutcome outcome = step.forward().apply(context);

                    if (outcome instanceof StepSuccess) {
                        if (step.hasRollback()) {
                            completedSteps.add(step);
                        }
                        break;
                    }

                    if (outcome instanceof RetryableFailure retryableFailure) {
                        if (!retryPolicy.canRetry(attemptsUsed)) {
                            return handleFailureWithRollback(
                                context,
                                step,
                                retryableFailure.reason(),
                                retryableFailure.cause(),
                                completedSteps
                            );
                        }

                        sleep(retryPolicy.delayBeforeRetry(attemptsUsed));
                        continue;
                    }

                    if (outcome instanceof PermanentFailure permanentFailure) {
                        return handleFailureWithRollback(
                            context,
                            step,
                            permanentFailure.reason(),
                            permanentFailure.cause(),
                            completedSteps
                        );
                    }

                    return handleFailureWithRollback(
                        context,
                        step,
                        "Unknown step outcome: " + outcome.getClass().getName(),
                        null,
                        completedSteps
                    );

                } catch (Exception exception) {
                    if (!retryPolicy.shouldRetry(exception) || !retryPolicy.canRetry(attemptsUsed)) {
                        return handleFailureWithRollback(
                            context,
                            step,
                            "Unhandled exception during step execution: " + exception.getClass().getSimpleName(),
                            exception,
                            completedSteps
                        );
                    }

                    sleep(retryPolicy.delayBeforeRetry(attemptsUsed));
                }
            }
        }

        return WorkflowResult.success(context);
    }

    private void sleep(Duration delay) {
        if (delay.isZero()) {
            return;
        }

        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkflowException("Workflow execution interrupted", e);
        }
    }

    private WorkflowResult<C> handleFailureWithRollback(
        C context,
        Step<C> failedStep,
        String failureReason,
        Throwable failureCause,
        List<Step<C>> completedSteps
    ) {
        boolean rollbackAttempted = false;

        for (int i = completedSteps.size() - 1; i >= 0; i--) {
            Step<C> completedStep = completedSteps.get(i);

            var rollbackHandler = completedStep.rollback();
            if (rollbackHandler.isEmpty()) {
                continue;
            }

            rollbackAttempted = true;

            try {
                rollbackHandler.get().apply(context);
            } catch (Exception rollbackException) {
                return WorkflowResult.failureWithRollbackFailure(
                    context,
                    failedStep.name(),
                    failureReason,
                    failureCause,
                    completedStep.name(),
                    rollbackException
                );
            }
        }

        if (!rollbackAttempted) {
            return WorkflowResult.failure(
                context,
                failedStep.name(),
                failureReason,
                failureCause
            );
        }

        return WorkflowResult.failureWithRollback(
            context,
            failedStep.name(),
            failureReason,
            failureCause,
            RollbackResult.succeeded()
        );
    }

    public static final class Builder<C> {
        private final List<Step<C>> steps = new ArrayList<>();
        private RetryPolicy defaultRetryPolicy = RetryPolicy.noRetry();

        private Builder() {
        }

        public Builder<C> steps(Step<C>... steps) {
            for (Step<C> step : steps) {
                step(step);
            }
            return this;
        }

        public Builder<C> step(Step<C> step) {
            this.steps.add(Objects.requireNonNull(step, "step must not be null"));
            return this;
        }

        public Builder<C> defaultRetryPolicy(RetryPolicy defaultRetryPolicy) {
            this.defaultRetryPolicy = Objects.requireNonNull(
                defaultRetryPolicy,
                "defaultRetryPolicy must not be null"
            );
            return this;
        }

        public Builder<C> step(String name, StepHandler<C> forward) {
            return step(Step.of(name, forward));
        }

        public Workflow<C> build() {
            if (steps.isEmpty()) {
                throw new IllegalArgumentException("workflow must contain at least one step");
            }
            return new Workflow<>(this);
        }
    }
}