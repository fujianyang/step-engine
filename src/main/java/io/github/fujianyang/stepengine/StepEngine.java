package io.github.fujianyang.stepengine;

import io.github.fujianyang.stepengine.exception.ServiceException;
import io.github.fujianyang.stepengine.exception.StepTimeoutException;
import io.github.fujianyang.stepengine.handler.CompensateHandler;
import io.github.fujianyang.stepengine.handler.StepHandler;
import io.github.fujianyang.stepengine.retry.NoRetryPolicy;
import io.github.fujianyang.stepengine.retry.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class StepEngine<C> {

    private static final Logger log = LoggerFactory.getLogger(StepEngine.class);

    private final List<ExecutionUnit<C>> units;
    private final RetryPolicy retryPolicy;
    private final CompensateOnError compensateOnError;

    private StepEngine(List<ExecutionUnit<C>> units, RetryPolicy retryPolicy,
                       CompensateOnError compensateOnError) {
        Objects.requireNonNull(units, "units must not be null");
        if (units.isEmpty()) {
            throw new IllegalArgumentException("steps must not be empty");
        }

        this.units = List.copyOf(units);
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
        this.compensateOnError = Objects.requireNonNull(compensateOnError, "compensateOnError must not be null");

        validateUniqueStepNames(this.units);
    }

    public static <C> Builder<C> builder() {
        return new Builder<>();
    }

    public void execute(C context) {
        Deque<ExecutionUnit<C>> completedUnits = new ArrayDeque<>();

        log.info("StepEngine started, units={}", units.size());

        for (ExecutionUnit<C> unit : units) {
            try {
                switch (unit) {
                    case ExecutionUnit.Sequential<C> seq -> {
                        Step<C> step = seq.step();
                        log.debug("Step '{}' forwarding ...", step.name());
                        executeStepWithRetry(step, context, null);
                        log.debug("Step '{}' forwarding ... done", step.name());
                    }
                    case ExecutionUnit.Parallel<C> par -> {
                        executeParallelGroup(par.group(), context);
                    }
                }
                completedUnits.push(unit);

            } catch (Exception exception) {
                compensateCompletedUnits(completedUnits, context, exception);
                logException(unit, exception);
                rethrow(exception);
            }
        }

        log.info("StepEngine finished successfully");
    }

    private void executeParallelGroup(ParallelGroup<C> group, C context) throws Exception {
        List<Step<C>> steps = group.steps();
        log.debug("Parallel group forwarding, steps={}", steps.size());

        AtomicBoolean doomed = new AtomicBoolean(false);
        AtomicReference<Exception> firstFailure = new AtomicReference<>();
        List<Exception> failures = Collections.synchronizedList(new ArrayList<>());
        List<Step<C>> completedSteps = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService defaultExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = steps.stream()
                .map(step -> {
                    Executor executor = step.executor()
                        .orElse(group.executor().orElse(defaultExecutor));

                    return CompletableFuture.runAsync(() -> {
                        try {
                            log.debug("Step '{}' forwarding ... (parallel)", step.name());
                            executeStepWithRetry(step, context, doomed);
                            completedSteps.add(step);
                            log.debug("Step '{}' forwarding ... done (parallel)", step.name());
                        } catch (Exception e) {
                            firstFailure.compareAndSet(null, e);
                            failures.add(e);
                            if (e instanceof ServiceException) {
                                doomed.set(true);
                            }
                            throw new CompletionException(e);
                        }
                    }, executor);
                })
                .toList();

            // Wait for all to finish
            for (CompletableFuture<Void> future : futures) {
                try {
                    future.join();
                } catch (CompletionException ignored) {
                    // Exceptions are captured in failures list
                }
            }
        }

        Exception primary = firstFailure.get();
        if (primary != null) {
            // Attach other failures as suppressed
            for (Exception failure : failures) {
                if (failure != primary) {
                    primary.addSuppressed(failure);
                }
            }
            // Compensate completed steps in this group in parallel
            compensateStepsInParallel(completedSteps, context, primary, group);
            throw primary;
        }

        log.debug("Parallel group forwarding ... done");
    }

    private void executeStepWithRetry(Step<C> step, C context, AtomicBoolean doomed) throws Exception {
        int attemptNumber = 1;

        RetryPolicy effectivePolicy =
            step.retryPolicy().orElse(this.retryPolicy);

        while (true) {
            try {
                invokeWithTimeout(() -> step.handler().forward(context), step);
                return;
            } catch (ServiceException serviceException) {
                log.warn("Step '{}' ServiceException: {}", step.name(), serviceException.getMessage());
                throw serviceException;
            } catch (Exception exception) {
                log.warn("Step '{}' Exception: {}", step.name(), exception.getMessage());

                if (doomed != null && doomed.get()) {
                    throw exception;
                }

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

    private void executeCompensateWithRetry(Step<C> step, CompensateHandler<C> compensateHandler,
                                            C context) throws Exception {
        RetryPolicy compensatePolicy = step.compensateRetryPolicy().orElse(null);

        if (compensatePolicy == null) {
            invokeWithTimeout(() -> compensateHandler.compensate(context), step);
            return;
        }

        int attemptNumber = 1;
        while (true) {
            try {
                invokeWithTimeout(() -> compensateHandler.compensate(context), step);
                return;
            } catch (Exception exception) {
                log.warn("Step '{}' compensate Exception: {}", step.name(), exception.getMessage());

                if (!compensatePolicy.shouldRetry(exception, attemptNumber)) {
                    throw exception;
                }

                log.info("Step '{}' compensate retry, attempt={}", step.name(), attemptNumber);

                Duration delay = compensatePolicy.backoffDelay(attemptNumber);
                sleep(delay, step.name(), attemptNumber, exception);

                attemptNumber++;
            }
        }
    }

    private void compensateCompletedUnits(Deque<ExecutionUnit<C>> completedUnits,
                                          C context,
                                          Throwable originalFailure) {
        while (!completedUnits.isEmpty()) {
            ExecutionUnit<C> unit = completedUnits.pop();
            switch (unit) {
                case ExecutionUnit.Sequential<C> seq -> {
                    try {
                        compensateStep(seq.step(), context);
                    } catch (Exception compensateException) {
                        log.error("Step '{}' compensating Exception: {}", seq.step().name(), compensateException.getMessage());
                        originalFailure.addSuppressed(compensateException);
                        if (compensateOnError == CompensateOnError.STOP) {
                            return;
                        }
                    }
                }
                case ExecutionUnit.Parallel<C> par -> {
                    compensateStepsInParallel(par.group().steps(), context, originalFailure, par.group());
                }
            }
        }
    }

    private void compensateStep(Step<C> step, C context) throws Exception {
        if (!step.supportsCompensate()) {
            return;
        }

        CompensateHandler<C> compensateHandler = step.compensateHandler().orElseThrow();
        log.warn("Step '{}' compensating", step.name());
        executeCompensateWithRetry(step, compensateHandler, context);
        log.warn("Step '{}' compensating ... done", step.name());
    }

    private void compensateStepsInParallel(List<Step<C>> steps,
                                           C context,
                                           Throwable originalFailure,
                                           ParallelGroup<C> group) {
        List<Step<C>> compensatable = steps.stream()
            .filter(Step::supportsCompensate)
            .toList();

        if (compensatable.isEmpty()) {
            return;
        }

        try (ExecutorService defaultExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = compensatable.stream()
                .map(step -> {
                    Executor executor = step.executor()
                        .orElse(group.executor().orElse(defaultExecutor));

                    return CompletableFuture.runAsync(() -> {
                        CompensateHandler<C> compensateHandler = step.compensateHandler().orElseThrow();
                        try {
                            log.warn("Step '{}' compensating (parallel)", step.name());
                            executeCompensateWithRetry(step, compensateHandler, context);
                            log.warn("Step '{}' compensating ... done (parallel)", step.name());
                        } catch (Exception compensateException) {
                            log.error("Step '{}' compensating Exception: {}", step.name(), compensateException.getMessage());
                            synchronized (originalFailure) {
                                originalFailure.addSuppressed(compensateException);
                            }
                        }
                    }, executor);
                })
                .toList();

            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        }
    }

    private void invokeWithTimeout(ThrowingRunnable action, Step<C> step) throws Exception {
        Duration timeout = step.timeout().orElse(null);
        if (timeout == null) {
            action.run();
            return;
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> future = executor.submit(() -> {
                action.run();
                return null;
            });

            try {
                future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new StepTimeoutException(step.name(), timeout);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception ex) throw ex;
                if (cause instanceof Error err) throw err;
                throw new RuntimeException(cause);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private void logException(ExecutionUnit<C> unit, Exception exception) {
        String stepName = switch (unit) {
            case ExecutionUnit.Sequential<C> seq -> seq.step().name();
            case ExecutionUnit.Parallel<C> par -> "parallel group";
        };

        if (exception instanceof ServiceException) {
            log.warn(
                "StepEngine terminated at '{}' due to ServiceException: {}",
                stepName,
                exception.getMessage(),
                exception
            );
        } else {
            log.error(
                "StepEngine failed at '{}': {}",
                stepName,
                exception.getMessage(),
                exception
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void rethrow(Throwable throwable) throws E {
        throw (E) throwable;
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

    private static <C> void validateUniqueStepNames(List<ExecutionUnit<C>> units) {
        Set<String> names = new LinkedHashSet<>();
        for (ExecutionUnit<C> unit : units) {
            switch (unit) {
                case ExecutionUnit.Sequential<C> seq -> {
                    if (!names.add(seq.step().name())) {
                        throw new IllegalArgumentException("duplicate step name: " + seq.step().name());
                    }
                }
                case ExecutionUnit.Parallel<C> par -> {
                    for (Step<C> step : par.group().steps()) {
                        if (!names.add(step.name())) {
                            throw new IllegalArgumentException("duplicate step name: " + step.name());
                        }
                    }
                }
            }
        }
    }

    public static final class Builder<C> {

        private final List<ExecutionUnit<C>> units = new ArrayList<>();
        private RetryPolicy retryPolicy = new NoRetryPolicy();
        private CompensateOnError compensateOnError = CompensateOnError.STOP;

        private Builder() {
        }

        public Builder<C> step(Step<C> step) {
            units.add(new ExecutionUnit.Sequential<>(
                Objects.requireNonNull(step, "step must not be null")));
            return this;
        }

        public Builder<C> step(String name, StepHandler<C> handler) {
            units.add(new ExecutionUnit.Sequential<>(Step.of(name, handler)));
            return this;
        }

        public Builder<C> step(String name,
                               StepHandler<C> handler,
                               CompensateHandler<C> compensateHandler) {
            units.add(new ExecutionUnit.Sequential<>(Step.of(name, handler, compensateHandler)));
            return this;
        }

        public Builder<C> parallel(ParallelGroup<C> group) {
            units.add(new ExecutionUnit.Parallel<>(
                Objects.requireNonNull(group, "group must not be null")));
            return this;
        }

        public Builder<C> retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
            return this;
        }

        /**
         * Controls behavior when a sequential step's compensation fails.
         *
         * <p>{@link CompensateOnError#STOP} (default) stops the compensation chain on the first
         * failure. {@link CompensateOnError#CONTINUE} continues compensating remaining steps
         * despite failures.
         *
         * <p>This setting only affects sequential steps. Within a parallel group, all steps are
         * always compensated regardless of individual failures.
         *
         * @param compensateOnError the error handling strategy for compensation
         */
        public Builder<C> compensateOnError(CompensateOnError compensateOnError) {
            this.compensateOnError = Objects.requireNonNull(compensateOnError, "compensateOnError must not be null");
            return this;
        }

        public StepEngine<C> build() {
            return new StepEngine<>(units, retryPolicy, compensateOnError);
        }
    }
}
