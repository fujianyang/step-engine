package io.github.fujianyang.stepengine;

import io.github.fujianyang.stepengine.handler.RollbackHandler;
import io.github.fujianyang.stepengine.handler.StepHandler;
import io.github.fujianyang.stepengine.retry.RetryPolicy;

import java.util.Objects;
import java.util.Optional;

public final class Step<C> {

    /**
     * Mandatory step name
     */
    private final String name;

    /**
     * Mandatory forward StepHandler
     */
    private final StepHandler<C> forward;

    /**
     * Optional RollbackHandler
     */
    private final RollbackHandler<C> rollback;

    /**
     * Optional step specific RetryPolicy, defaults to global RetryPolicy
     */
    private final RetryPolicy retryPolicy;

    private Step(Builder<C> builder) {
        this.name = requireNonBlank(builder.name, "name must not be blank");
        this.forward = Objects.requireNonNull(builder.forward, "forward handler must not be null");
        this.rollback = builder.rollback;
        this.retryPolicy = builder.retryPolicy;
    }

    /**
     * Convenient method to construct a Step object
     */
    public static <C> Step<C> of(String name, StepHandler<C> forward) {
        return Step.<C>builder()
            .name(name)
            .forward(forward)
            .build();
    }

    public String name() {
        return name;
    }

    StepHandler<C> forward() {
        return forward;
    }

    Optional<RollbackHandler<C>> rollback() {
        return Optional.ofNullable(rollback);
    }

    Optional<RetryPolicy> retryPolicy() {
        return Optional.ofNullable(retryPolicy);
    }

    boolean hasRollback() {
        return rollback != null;
    }

    public static <C> Builder<C> builder() {
        return new Builder<>();
    }

    public static final class Builder<C> {
        private String name;
        private StepHandler<C> forward;
        private RollbackHandler<C> rollback;
        private RetryPolicy retryPolicy;

        private Builder() {
        }

        public Builder<C> name(String name) {
            this.name = name;
            return this;
        }

        public Builder<C> forward(StepHandler<C> forward) {
            this.forward = forward;
            return this;
        }

        public Builder<C> rollback(RollbackHandler<C> rollback) {
            this.rollback = rollback;
            return this;
        }

        public Builder<C> retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        public Step<C> build() {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("step name must not be blank");
            }
            if (forward == null) {
                throw new IllegalArgumentException("step forward handler must not be null");
            }
            return new Step<>(this);
        }
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}