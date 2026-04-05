package io.github.fujianyang.stepengine.exception;

/**
 * Should only be for framework/runtime problems.
 */
public class WorkflowException extends RuntimeException {

    public WorkflowException(String message, Throwable cause) {
        super(message, cause);
    }
}