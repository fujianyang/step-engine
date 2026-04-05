package io.github.fujianyang.stepengine.handler;

@FunctionalInterface
public interface RollbackHandler<C> {

    /**
     * @throws Exception to indicate a rollback failure
     */
    void apply(C context) throws Exception;
}