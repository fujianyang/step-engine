package io.github.fujianyang.stepengine;

import io.github.fujianyang.stepengine.handler.CompensateHandler;
import io.github.fujianyang.stepengine.handler.StepHandler;
import io.github.fujianyang.stepengine.retry.RetryPolicy;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

public final class Step<C> {

    private final String name;
    private final StepHandler<C> handler;
    private final CompensateHandler<C> compensateHandler;
    private final RetryPolicy retryPolicy;
    private final RetryPolicy compensateRetryPolicy;
    private final Executor executor;
    private final Duration timeout;

    private Step(String name, StepHandler<C> handler, CompensateHandler<C> compensateHandler,
                 RetryPolicy retryPolicy, RetryPolicy compensateRetryPolicy,
                 Executor executor, Duration timeout) {
        this.name = requireName(name);
        this.handler = Objects.requireNonNull(handler, "handler must not be null");
        this.compensateHandler = compensateHandler;
        this.retryPolicy = retryPolicy;
        this.compensateRetryPolicy = compensateRetryPolicy;
        this.executor = executor;
        this.timeout = validateTimeout(timeout);
    }

    public static <C> Step<C> of(String name, StepHandler<C> handler) {
        return new Step<>(name, handler, null, null, null, null, null);
    }

    public static <C> Step<C> of(String name,
                                 StepHandler<C> handler,
                                 CompensateHandler<C> compensateHandler) {
        return new Step<>(name, handler, compensateHandler, null, null, null, null);
    }

    public static <C> Builder<C> builder() {
        return new Builder<>();
    }

    public String name() {
        return name;
    }

    public StepHandler<C> handler() {
        return handler;
    }

    public Optional<CompensateHandler<C>> compensateHandler() {
        return Optional.ofNullable(compensateHandler);
    }

    public boolean supportsCompensate() {
        return compensateHandler != null;
    }

    public Optional<RetryPolicy> retryPolicy() {
        return Optional.ofNullable(retryPolicy);
    }

    public Optional<RetryPolicy> compensateRetryPolicy() {
        return Optional.ofNullable(compensateRetryPolicy);
    }

    public Optional<Executor> executor() {
        return Optional.ofNullable(executor);
    }

    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }

    private static Duration validateTimeout(Duration timeout) {
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return timeout;
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return name;
    }

    public static final class Builder<C> {

        private String name;
        private StepHandler<C> handler;
        private CompensateHandler<C> compensateHandler;
        private RetryPolicy retryPolicy;
        private RetryPolicy compensateRetryPolicy;
        private Executor executor;
        private Duration timeout;

        private Builder() {
        }

        public Builder<C> name(String name) {
            this.name = name;
            return this;
        }

        public Builder<C> execute(StepHandler<C> handler) {
            this.handler = handler;
            return this;
        }

        public Builder<C> compensate(CompensateHandler<C> compensateHandler) {
            this.compensateHandler = compensateHandler;
            return this;
        }

        public Builder<C> retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        public Builder<C> compensateRetryPolicy(RetryPolicy compensateRetryPolicy) {
            this.compensateRetryPolicy = compensateRetryPolicy;
            return this;
        }

        public Builder<C> executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        public Builder<C> timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Step<C> build() {
            if (handler == null) {
                throw new IllegalStateException("step handler must be provided");
            }
            return new Step<>(name, handler, compensateHandler, retryPolicy, compensateRetryPolicy, executor, timeout);
        }
    }
}
