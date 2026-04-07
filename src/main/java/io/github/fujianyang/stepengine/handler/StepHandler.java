package io.github.fujianyang.stepengine.handler;

@FunctionalInterface
public interface StepHandler<C> {

    /**
     * Executes the forward logic of a workflow step.
     *
     * @throws Exception to indicate step failure
     */
    void execute(C context) throws Exception;
}