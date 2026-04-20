package io.github.fujianyang.stepengine.reactor;

import reactor.core.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ReactiveParallelGroup<C> {

    private final List<ReactiveStep<C>> steps;
    private final Scheduler scheduler;

    private ReactiveParallelGroup(List<ReactiveStep<C>> steps, Scheduler scheduler) {
        Objects.requireNonNull(steps, "steps must not be null");
        if (steps.size() < 2) {
            throw new IllegalArgumentException("parallel group must contain at least 2 steps");
        }
        this.steps = List.copyOf(steps);
        this.scheduler = scheduler;
    }

    public static <C> Builder<C> builder() {
        return new Builder<>();
    }

    public List<ReactiveStep<C>> steps() {
        return steps;
    }

    public Optional<Scheduler> scheduler() {
        return Optional.ofNullable(scheduler);
    }

    public static final class Builder<C> {

        private final List<ReactiveStep<C>> steps = new ArrayList<>();
        private Scheduler scheduler;

        private Builder() {
        }

        public Builder<C> step(ReactiveStep<C> step) {
            steps.add(Objects.requireNonNull(step, "step must not be null"));
            return this;
        }

        public Builder<C> scheduler(Scheduler scheduler) {
            this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
            return this;
        }

        public ReactiveParallelGroup<C> build() {
            return new ReactiveParallelGroup<>(steps, scheduler);
        }
    }
}
