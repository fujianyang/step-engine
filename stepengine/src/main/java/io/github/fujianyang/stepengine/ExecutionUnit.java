package io.github.fujianyang.stepengine;

sealed interface ExecutionUnit<C> {

    record Sequential<C>(Step<C> step) implements ExecutionUnit<C> {}

    record Parallel<C>(ParallelGroup<C> group) implements ExecutionUnit<C> {}
}
