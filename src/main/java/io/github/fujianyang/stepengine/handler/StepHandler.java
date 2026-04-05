package io.github.fujianyang.stepengine.handler;

import io.github.fujianyang.stepengine.outcome.StepOutcome;

@FunctionalInterface
public interface StepHandler<C> {

    StepOutcome apply(C context) throws Exception;
}