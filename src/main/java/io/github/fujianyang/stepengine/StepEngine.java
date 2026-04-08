package io.github.fujianyang.stepengine;

import io.github.fujianyang.stepengine.exception.ServiceException;
import io.github.fujianyang.stepengine.handler.RollbackHandler;
import io.github.fujianyang.stepengine.handler.StepHandler;
import io.github.fujianyang.stepengine.retry.NoRetryPolicy;
import io.github.fujianyang.stepengine.retry.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class StepEngine<C> {

    private static final Logger log = LoggerFactory.getLogger(StepEngine.class);

    private final List<Step<C>> steps;
    private final RetryPolicy retryPolicy;

    private StepEngine(List<Step<C>> steps, RetryPolicy retryPolicy) {
        Objects.requireNonNull(steps, "steps must not be null");
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("steps must not be empty");
        }

        this.steps = List.copyOf(steps);
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");

        validateUniqueStepNames(this.steps);
    }

    public static <C> Builder<C> builder() {
        return new Builder<>();
    }

    public void execute(C context) {
        Deque<Step<C>> completedSteps = new ArrayDeque<>();

        log.info("StepEngine started, steps={}", steps.size());

        for (Step<C> step : steps) {
            try {
                log.debug("Step '{}' forwarding ...", step.name());

                executeStepWithRetry(step, context);
                completedSteps.push(step);

                log.debug("Step '{}' forwarding ... done", step.name());

            } catch (Exception exception) {
                rollbackCompletedSteps(completedSteps, context, exception);
                logException(step, exception);
                rethrow(exception);
            }
        }

        log.info("StepEngine finished successfully");
    }

    private void logException(Step<C> step, Exception exception) {
        if (exception instanceof ServiceException) {
            log.warn(
                "StepEngine terminated at step '{}' due to ServiceException: {}",
                step.name(),
                exception.getMessage()
            );
        } else {
            log.error(
                "StepEngine failed at step '{}': {}",
                step.name(),
                exception.getMessage(),
                exception
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void rethrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    private void executeStepWithRetry(Step<C> step, C context) throws Exception {
        int attemptNumber = 1;

        RetryPolicy effectivePolicy =
            step.retryPolicy().orElse(this.retryPolicy);

        while (true) {
            try {
                step.handler().forward(context);
                return;
            } catch (ServiceException serviceException) {
                log.warn("Step '{}' ServiceException: {}", step.name(), serviceException.getMessage());
                throw serviceException;
            } catch (Exception exception) {
                log.warn("Step '{}' Exception: {}", step.name(), exception.getMessage());
                if (!effectivePolicy.shouldRetry(exception, attemptNumber)) {
                    throw exception;
                }

                log.info("Step '{}' retry, attempt={}", step.name(), attemptNumber);

                Duration delay = effectivePolicy.backoffDelay(attemptNumber);
                sleep(delay, step.name(), attemptNumber, exception);

                attemptNumber++;
            }
        }
    }

    private void rollbackCompletedSteps(Deque<Step<C>> completedSteps,
                                        C context,
                                        Throwable originalFailure) {
        while (!completedSteps.isEmpty()) {
            Step<C> completedStep = completedSteps.pop();
            if (!completedStep.supportsRollback()) {
                continue;
            }

            RollbackHandler<C> rollbackHandler = completedStep.rollbackHandler().orElseThrow();
            try {
                log.warn("Step '{}' rolling back", completedStep.name());
                rollbackHandler.rollback(context);
                log.warn("Step '{}' rolling back ... done", completedStep.name());
            } catch (Exception rollbackException) {
                log.error("Step '{}' rolling back Exception: {}", completedStep.name(), rollbackException.getMessage());
                originalFailure.addSuppressed(rollbackException);
            }
        }
    }

    private void sleep(Duration delay,
                       String stepName,
                       int attemptNumber,
                       Exception originalException) {
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return;
        }

        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException interruptedException) {
            // reset the interrupt flag and continue
            Thread.currentThread().interrupt();

            RuntimeException interruptionFailure = new RuntimeException(
                "Interrupted during retry backoff for step '" + stepName
                    + "' after attempt " + attemptNumber,
                interruptedException
            );
            interruptionFailure.addSuppressed(originalException);
            throw interruptionFailure;
        }
    }

    private static <C> void validateUniqueStepNames(List<Step<C>> steps) {
        Set<String> names = new LinkedHashSet<>();
        for (Step<C> step : steps) {
            if (!names.add(step.name())) {
                throw new IllegalArgumentException("duplicate step name: " + step.name());
            }
        }
    }

    public static final class Builder<C> {

        private final List<Step<C>> steps = new ArrayList<>();
        private RetryPolicy retryPolicy = new NoRetryPolicy();

        private Builder() {
        }

        public Builder<C> step(Step<C> step) {
            steps.add(Objects.requireNonNull(step, "step must not be null"));
            return this;
        }

        public Builder<C> step(String name, StepHandler<C> handler) {
            steps.add(Step.of(name, handler));
            return this;
        }

        public Builder<C> step(String name,
                               StepHandler<C> handler,
                               RollbackHandler<C> rollbackHandler) {
            steps.add(Step.of(name, handler, rollbackHandler));
            return this;
        }

        public Builder<C> retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
            return this;
        }

        public StepEngine<C> build() {
            return new StepEngine<>(steps, retryPolicy);
        }
    }
}