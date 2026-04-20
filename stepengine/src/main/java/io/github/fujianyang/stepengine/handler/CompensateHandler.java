package io.github.fujianyang.stepengine.handler;

@FunctionalInterface
public interface CompensateHandler<C> {

    /**
     * Executes the compensating logic for a previously completed step.
     *
     * @throws Exception to indicate compensation failure
     */
    void compensate(C context) throws Exception;
}
