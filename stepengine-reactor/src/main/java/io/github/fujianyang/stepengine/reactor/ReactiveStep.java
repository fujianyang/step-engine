package io.github.fujianyang.stepengine.reactor;

import io.github.fujianyang.stepengine.reactor.handler.ReactiveCompensateHandler;
import io.github.fujianyang.stepengine.reactor.handler.ReactiveStepHandler;
import io.github.fujianyang.stepengine.retry.RetryPolicy;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class ReactiveStep<C> {

    private final String name;
    private final ReactiveStepHandler<C> handler;
    private final ReactiveCompensateHandler<C> compensateHandler;
    private final RetryPolicy retryPolicy;
    private final RetryPolicy compensateRetryPolicy;
    private final Scheduler scheduler;
    private final Duration timeout;

    private ReactiveStep(String name,
                         ReactiveStepHandler<C> handler,
                         ReactiveCompensateHandler<C> compensateHandler,
                         RetryPolicy retryPolicy,
                         RetryPolicy compensateRetryPolicy,
                         Scheduler scheduler,
                         Duration timeout) {
        this.name = requireName(name);
        this.handler = Objects.requireNonNull(handler, "handler must not be null");
        this.compensateHandler = compensateHandler;
        this.retryPolicy = retryPolicy;
        this.compensateRetryPolicy = compensateRetryPolicy;
        this.scheduler = scheduler;
        this.timeout = validateTimeout(timeout);
    }

    public static <C> ReactiveStep<C> of(String name, ReactiveStepHandler<C> handler) {
        return new ReactiveStep<>(name, handler, null, null, null, null, null);
    }

    public static <C> ReactiveStep<C> of(String name,
                                         ReactiveStepHandler<C> handler,
                                         ReactiveCompensateHandler<C> compensateHandler) {
        return new ReactiveStep<>(name, handler, compensateHandler, null, null, null, null);
    }

    public static <C> Builder<C> builder() {
        return new Builder<>();
    }

    public String name() {
        return name;
    }

    public ReactiveStepHandler<C> handler() {
        return handler;
    }

    public Optional<ReactiveCompensateHandler<C>> compensateHandler() {
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

    public Optional<Scheduler> scheduler() {
        return Optional.ofNullable(scheduler);
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
        private ReactiveStepHandler<C> handler;
        private ReactiveCompensateHandler<C> compensateHandler;
        private RetryPolicy retryPolicy;
        private RetryPolicy compensateRetryPolicy;
        private Scheduler scheduler;
        private Duration timeout;

        private Builder() {
        }

        public Builder<C> name(String name) {
            this.name = name;
            return this;
        }

        public Builder<C> forward(ReactiveStepHandler<C> handler) {
            this.handler = handler;
            return this;
        }

        public Builder<C> compensate(ReactiveCompensateHandler<C> compensateHandler) {
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

        public Builder<C> scheduler(Scheduler scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        public Builder<C> timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public ReactiveStep<C> build() {
            if (handler == null) {
                throw new IllegalStateException("step handler must be provided");
            }
            return new ReactiveStep<>(
                name,
                handler,
                compensateHandler,
                retryPolicy,
                compensateRetryPolicy,
                scheduler,
                timeout
            );
        }
    }
}
