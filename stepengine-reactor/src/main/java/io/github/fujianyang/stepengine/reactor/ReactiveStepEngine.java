package io.github.fujianyang.stepengine.reactor;

import io.github.fujianyang.stepengine.CompensateOnError;
import io.github.fujianyang.stepengine.exception.ServiceException;
import io.github.fujianyang.stepengine.exception.StepTimeoutException;
import io.github.fujianyang.stepengine.reactor.handler.ReactiveCompensateHandler;
import io.github.fujianyang.stepengine.reactor.handler.ReactiveStepHandler;
import io.github.fujianyang.stepengine.retry.NoRetryPolicy;
import io.github.fujianyang.stepengine.retry.RetryPolicy;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ReactiveStepEngine<C> {

    private final List<ReactiveExecutionUnit<C>> units;
    private final RetryPolicy retryPolicy;
    private final CompensateOnError compensateOnError;

    private ReactiveStepEngine(List<ReactiveExecutionUnit<C>> units,
                               RetryPolicy retryPolicy,
                               CompensateOnError compensateOnError) {
        Objects.requireNonNull(units, "units must not be null");
        if (units.isEmpty()) {
            throw new IllegalArgumentException("steps must not be empty");
        }

        this.units = List.copyOf(units);
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        this.compensateOnError = Objects.requireNonNull(compensateOnError, "compensateOnError must not be null");

        ReactiveValidation.validateUniqueStepNames(this.units);
    }

    public static <C> Builder<C> builder() {
        return new Builder<>();
    }

    public Mono<Void> execute(C context) {
        return Mono.defer(() -> executeUnitsSequentially(0, context, new ArrayDeque<>()));
    }

    private Mono<Void> executeUnitsSequentially(int index,
                                                C context,
                                                Deque<ReactiveExecutionUnit<C>> completedUnits) {
        if (index >= units.size()) {
            return Mono.empty();
        }

        ReactiveExecutionUnit<C> unit = units.get(index);

        return executeUnit(unit, context)
            .then(Mono.defer(() -> {
                completedUnits.push(unit);
                return executeUnitsSequentially(index + 1, context, completedUnits);
            }))
            .onErrorResume(originalFailure ->
                compensateCompletedUnits(completedUnits, context, originalFailure)
                    .then(Mono.error(originalFailure))
            );
    }

    private Mono<Void> executeUnit(ReactiveExecutionUnit<C> unit, C context) {
        return switch (unit) {
            case ReactiveExecutionUnit.Sequential<C> seq -> executeStepWithRetry(seq.step(), context, null, null);
            case ReactiveExecutionUnit.Parallel<C> par -> executeParallelGroup(par.group(), context);
        };
    }

    private Mono<Void> executeStepWithRetry(ReactiveStep<C> step,
                                            C context,
                                            AtomicBoolean doomed) {
        return executeStepWithRetry(step, context, doomed, null, 1);
    }

    private Mono<Void> executeStepWithRetry(ReactiveStep<C> step,
                                            C context,
                                            AtomicBoolean doomed,
                                            Scheduler defaultScheduler) {
        return executeStepWithRetry(step, context, doomed, defaultScheduler, 1);
    }

    private Mono<Void> executeStepWithRetry(ReactiveStep<C> step,
                                            C context,
                                            AtomicBoolean doomed,
                                            Scheduler defaultScheduler,
                                            int attemptNumber) {
        RetryPolicy effectivePolicy = step.retryPolicy().orElse(this.retryPolicy);

        return invokeWithTimeout(step, invokeForward(step, context, defaultScheduler))
            .onErrorResume(ServiceException.class, Mono::error)
            .onErrorResume(exception -> !(exception instanceof ServiceException), exception -> {
                if (doomed != null && doomed.get()) {
                    return Mono.error(exception);
                }

                if (!effectivePolicy.shouldRetry(exception, attemptNumber)) {
                    return Mono.error(exception);
                }

                Duration delay = effectivePolicy.backoffDelay(attemptNumber);
                return delayMono(delay)
                    .then(executeStepWithRetry(step, context, doomed, defaultScheduler, attemptNumber + 1));
            });
    }

    private Mono<Void> executeParallelGroup(ReactiveParallelGroup<C> group, C context) {
        AtomicBoolean doomed = new AtomicBoolean(false);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<ReactiveStep<C>> completedSteps = Collections.synchronizedList(new ArrayList<>());

        List<Mono<ParallelOutcome<C>>> executions = group.steps().stream()
            .map(step -> executeParallelStep(step, group, context, doomed, firstFailure, failures, completedSteps))
            .toList();

        return reactor.core.publisher.Flux.merge(executions)
            .collectList()
            .flatMap(ignored -> {
                Throwable primary = firstFailure.get();
                if (primary == null) {
                    return Mono.empty();
                }

                attachSuppressed(primary, failures);
                return compensateStepsInParallel(completedSteps, context, primary, group)
                    .then(Mono.error(primary));
            });
    }

    private Mono<ParallelOutcome<C>> executeParallelStep(ReactiveStep<C> step,
                                                         ReactiveParallelGroup<C> group,
                                                         C context,
                                                         AtomicBoolean doomed,
                                                         AtomicReference<Throwable> firstFailure,
                                                         List<Throwable> failures,
                                                         List<ReactiveStep<C>> completedSteps) {
        return executeStepWithRetry(step, context, doomed, group.scheduler().orElse(null))
            .then(Mono.fromSupplier(() -> {
                completedSteps.add(step);
                return ParallelOutcome.success(step);
            }))
            .onErrorResume(error -> {
                firstFailure.compareAndSet(null, error);
                failures.add(error);
                if (error instanceof ServiceException) {
                    doomed.set(true);
                }
                return Mono.just(ParallelOutcome.failure(step, error));
            });
    }

    private Mono<Void> executeCompensateWithRetry(ReactiveStep<C> step,
                                                  ReactiveCompensateHandler<C> compensateHandler,
                                                  C context) {
        return executeCompensateWithRetry(step, compensateHandler, context, null, 1);
    }

    private Mono<Void> executeCompensateWithRetry(ReactiveStep<C> step,
                                                  ReactiveCompensateHandler<C> compensateHandler,
                                                  C context,
                                                  Scheduler defaultScheduler) {
        return executeCompensateWithRetry(step, compensateHandler, context, defaultScheduler, 1);
    }

    private Mono<Void> executeCompensateWithRetry(ReactiveStep<C> step,
                                                  ReactiveCompensateHandler<C> compensateHandler,
                                                  C context,
                                                  Scheduler defaultScheduler,
                                                  int attemptNumber) {
        RetryPolicy compensatePolicy = step.compensateRetryPolicy().orElse(null);
        Mono<Void> attempt = invokeWithTimeout(step, invokeCompensate(step, compensateHandler, context, defaultScheduler));

        if (compensatePolicy == null) {
            return attempt;
        }

        return attempt.onErrorResume(exception -> {
            if (!compensatePolicy.shouldRetry(exception, attemptNumber)) {
                return Mono.error(exception);
            }

            Duration delay = compensatePolicy.backoffDelay(attemptNumber);
            return delayMono(delay)
                .then(executeCompensateWithRetry(step, compensateHandler, context, defaultScheduler, attemptNumber + 1));
        });
    }

    private Mono<Void> compensateCompletedUnits(Deque<ReactiveExecutionUnit<C>> completedUnits,
                                                C context,
                                                Throwable originalFailure) {
        if (completedUnits.isEmpty()) {
            return Mono.empty();
        }

        ReactiveExecutionUnit<C> unit = completedUnits.pop();

        return switch (unit) {
            case ReactiveExecutionUnit.Sequential<C> seq -> compensateSequentialUnit(
                seq.step(),
                completedUnits,
                context,
                originalFailure
            );

            case ReactiveExecutionUnit.Parallel<C> par ->
                compensateStepsInParallel(par.group().steps(), context, originalFailure, par.group())
                    .then(Mono.defer(() -> compensateCompletedUnits(completedUnits, context, originalFailure)));
        };
    }

    private Mono<Void> compensateSequentialUnit(ReactiveStep<C> step,
                                                Deque<ReactiveExecutionUnit<C>> remainingUnits,
                                                C context,
                                                Throwable originalFailure) {
        return compensateStep(step, context)
            .then(Mono.defer(() -> compensateCompletedUnits(remainingUnits, context, originalFailure)))
            .onErrorResume(compensateException -> {
                originalFailure.addSuppressed(compensateException);
                if (compensateOnError == CompensateOnError.STOP) {
                    remainingUnits.clear();
                    return Mono.empty();
                }
                return Mono.defer(() -> compensateCompletedUnits(remainingUnits, context, originalFailure));
            });
    }

    private Mono<Void> compensateStep(ReactiveStep<C> step, C context) {
        if (!step.supportsCompensate()) {
            return Mono.empty();
        }

        ReactiveCompensateHandler<C> compensateHandler = step.compensateHandler().orElseThrow();
        return executeCompensateWithRetry(step, compensateHandler, context);
    }

    private Mono<Void> compensateStepsInParallel(List<ReactiveStep<C>> steps,
                                                 C context,
                                                 Throwable originalFailure,
                                                 ReactiveParallelGroup<C> group) {
        List<Mono<Void>> compensations = steps.stream()
            .filter(ReactiveStep::supportsCompensate)
            .map(step -> compensateStep(step, context, group.scheduler().orElse(null))
                .onErrorResume(compensateException -> {
                    synchronized (originalFailure) {
                        originalFailure.addSuppressed(compensateException);
                    }
                    return Mono.empty();
                }))
            .toList();

        if (compensations.isEmpty()) {
            return Mono.empty();
        }

        return reactor.core.publisher.Flux.merge(compensations).then();
    }

    private Mono<Void> compensateStep(ReactiveStep<C> step, C context, Scheduler defaultScheduler) {
        if (!step.supportsCompensate()) {
            return Mono.empty();
        }

        ReactiveCompensateHandler<C> compensateHandler = step.compensateHandler().orElseThrow();
        return executeCompensateWithRetry(step, compensateHandler, context, defaultScheduler);
    }

    private Mono<Void> invokeWithTimeout(ReactiveStep<C> step, Mono<Void> action) {
        Duration timeout = step.timeout().orElse(null);
        if (timeout == null) {
            return action;
        }

        return action.timeout(timeout)
            .onErrorMap(
                TimeoutException.class,
                timeoutException -> new StepTimeoutException(step.name(), timeout)
            );
    }

    private Mono<Void> invokeForward(ReactiveStep<C> step, C context, Scheduler defaultScheduler) {
        return Mono.defer(() -> {
            Mono<Void> mono = step.handler().forward(context);
            if (mono == null) {
                return Mono.error(new NullPointerException("step handler returned null Mono"));
            }
            return applyScheduler(mono, resolveScheduler(step, defaultScheduler));
        });
    }

    private Mono<Void> invokeCompensate(ReactiveStep<C> step,
                                        ReactiveCompensateHandler<C> compensateHandler,
                                        C context,
                                        Scheduler defaultScheduler) {
        return Mono.defer(() -> {
            Mono<Void> mono = compensateHandler.compensate(context);
            if (mono == null) {
                return Mono.error(new NullPointerException("compensate handler returned null Mono"));
            }
            return applyScheduler(mono, resolveScheduler(step, defaultScheduler));
        });
    }

    private Scheduler resolveScheduler(ReactiveStep<C> step, Scheduler defaultScheduler) {
        return step.scheduler().orElse(defaultScheduler);
    }

    private Mono<Void> applyScheduler(Mono<Void> mono, Scheduler scheduler) {
        return scheduler == null ? mono : mono.subscribeOn(scheduler);
    }

    private Mono<Void> delayMono(Duration delay) {
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return Mono.empty();
        }
        return Mono.delay(delay).then();
    }

    private void attachSuppressed(Throwable primary, List<Throwable> failures) {
        for (Throwable failure : failures) {
            if (failure != primary) {
                primary.addSuppressed(failure);
            }
        }
    }

    private record ParallelOutcome<C>(ReactiveStep<C> step, Throwable failure) {
        private static <C> ParallelOutcome<C> success(ReactiveStep<C> step) {
            return new ParallelOutcome<>(step, null);
        }

        private static <C> ParallelOutcome<C> failure(ReactiveStep<C> step, Throwable failure) {
            return new ParallelOutcome<>(step, failure);
        }
    }

    public static final class Builder<C> {

        private final List<ReactiveExecutionUnit<C>> units = new ArrayList<>();
        private RetryPolicy retryPolicy = new NoRetryPolicy();
        private CompensateOnError compensateOnError = CompensateOnError.STOP;

        private Builder() {
        }

        public Builder<C> step(ReactiveStep<C> step) {
            units.add(new ReactiveExecutionUnit.Sequential<>(
                Objects.requireNonNull(step, "step must not be null")));
            return this;
        }

        public Builder<C> step(String name, ReactiveStepHandler<C> handler) {
            units.add(new ReactiveExecutionUnit.Sequential<>(ReactiveStep.of(name, handler)));
            return this;
        }

        public Builder<C> step(String name,
                               ReactiveStepHandler<C> handler,
                               ReactiveCompensateHandler<C> compensateHandler) {
            units.add(new ReactiveExecutionUnit.Sequential<>(ReactiveStep.of(name, handler, compensateHandler)));
            return this;
        }

        public Builder<C> parallel(ReactiveParallelGroup<C> group) {
            units.add(new ReactiveExecutionUnit.Parallel<>(
                Objects.requireNonNull(group, "group must not be null")));
            return this;
        }

        public Builder<C> retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
            return this;
        }

        public Builder<C> compensateOnError(CompensateOnError compensateOnError) {
            this.compensateOnError = Objects.requireNonNull(compensateOnError, "compensateOnError must not be null");
            return this;
        }

        public ReactiveStepEngine<C> build() {
            return new ReactiveStepEngine<>(units, retryPolicy, compensateOnError);
        }
    }
}
