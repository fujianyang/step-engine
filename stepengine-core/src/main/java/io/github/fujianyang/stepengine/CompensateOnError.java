package io.github.fujianyang.stepengine;

/**
 * Controls how the engine behaves when a sequential step's compensation fails.
 *
 * <p>This setting only affects the sequential compensation chain. Within a parallel group,
 * all steps are always compensated regardless of individual failures.
 */
public enum CompensateOnError {

    /**
     * Stop the compensation chain on the first failure. Remaining sequential steps
     * are not compensated. The compensation failure is attached as a suppressed
     * exception on the original failure.
     *
     * <p>This is the default behavior.
     */
    STOP,

    /**
     * Continue compensating remaining sequential steps even if one fails. All
     * compensation failures are attached as suppressed exceptions on the original failure.
     */
    CONTINUE
}
