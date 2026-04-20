package io.github.fujianyang.stepengine.reactor;

sealed interface ReactiveExecutionUnit<C> {

    record Sequential<C>(ReactiveStep<C> step) implements ReactiveExecutionUnit<C> {}

    record Parallel<C>(ReactiveParallelGroup<C> group) implements ReactiveExecutionUnit<C> {}
}
