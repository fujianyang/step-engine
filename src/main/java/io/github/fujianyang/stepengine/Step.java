package io.github.fujianyang.stepengine;

import io.github.fujianyang.stepengine.handler.RollbackHandler;
import io.github.fujianyang.stepengine.handler.StepHandler;
import io.github.fujianyang.stepengine.retry.RetryPolicy;

import java.util.Objects;
import java.util.Optional;

public final class Step<C> {

    private final String name;
    private final StepHandler<C> handler;
    private final RollbackHandler<C> rollbackHandler;
    private final RetryPolicy retryPolicy;

    private Step(String name, StepHandler<C> handler, RollbackHandler<C> rollbackHandler, RetryPolicy retryPolicy) {
        this.name = requireName(name);
        this.handler = Objects.requireNonNull(handler, "handler must not be null");
        this.rollbackHandler = rollbackHandler;
        this.retryPolicy = retryPolicy;
    }

    public static <C> Step<C> of(String name, StepHandler<C> handler) {
        return new Step<>(name, handler, null, null);
    }

    public static <C> Step<C> of(String name,
                                 StepHandler<C> handler,
                                 RollbackHandler<C> rollbackHandler) {
        return new Step<>(name, handler, rollbackHandler, null);
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

    public Optional<RollbackHandler<C>> rollbackHandler() {
        return Optional.ofNullable(rollbackHandler);
    }

    public boolean supportsRollback() {
        return rollbackHandler != null;
    }

    public Optional<RetryPolicy> retryPolicy() {
        return Optional.ofNullable(retryPolicy);
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
        private RollbackHandler<C> rollbackHandler;
        private RetryPolicy retryPolicy;

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

        public Builder<C> rollback(RollbackHandler<C> rollbackHandler) {
            this.rollbackHandler = rollbackHandler;
            return this;
        }

        public Builder<C> retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        public Step<C> build() {
            if (handler == null) {
                throw new IllegalStateException("step handler must be provided");
            }
            return new Step<>(name, handler, rollbackHandler, retryPolicy);
        }
    }
}