package io.github.fujianyang.stepengine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

public final class ParallelGroup<C> {

    private final List<Step<C>> steps;
    private final Executor executor;

    private ParallelGroup(List<Step<C>> steps, Executor executor) {
        Objects.requireNonNull(steps, "steps must not be null");
        if (steps.size() < 2) {
            throw new IllegalArgumentException("parallel group must contain at least 2 steps");
        }
        this.steps = List.copyOf(steps);
        this.executor = executor;
    }

    public static <C> Builder<C> builder() {
        return new Builder<>();
    }

    public List<Step<C>> steps() {
        return steps;
    }

    public Optional<Executor> executor() {
        return Optional.ofNullable(executor);
    }

    public static final class Builder<C> {

        private final List<Step<C>> steps = new ArrayList<>();
        private Executor executor;

        private Builder() {}

        public Builder<C> step(Step<C> step) {
            steps.add(Objects.requireNonNull(step, "step must not be null"));
            return this;
        }

        public Builder<C> executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor must not be null");
            return this;
        }

        public ParallelGroup<C> build() {
            return new ParallelGroup<>(steps, executor);
        }
    }
}
