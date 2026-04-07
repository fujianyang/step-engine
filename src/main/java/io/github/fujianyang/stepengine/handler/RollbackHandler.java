package io.github.fujianyang.stepengine.handler;

@FunctionalInterface
public interface RollbackHandler<C> {

    /**
     * Executes the compensating rollback logic for a previously completed step.
     *
     * @throws Exception to indicate rollback failure
     */
    void rollback(C context) throws Exception;
}