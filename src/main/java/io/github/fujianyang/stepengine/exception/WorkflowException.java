package io.github.fujianyang.stepengine.exception;

/**
 * Indicates an unexpected workflow/runtime failure inside StepEngine.
 *
 * <p>This is used for technical failures such as retry exhaustion,
 * rollback failures, interruption during backoff, or unexpected
 * exceptions thrown from step execution.</p>
 */
public class WorkflowException extends RuntimeException {

    public WorkflowException(String message) {
        super(message);
    }

    public WorkflowException(String message, Throwable cause) {
        super(message, cause);
    }
}