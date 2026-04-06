package io.github.fujianyang.stepengine.exception;

/**
 * Base class for all service-level exceptions that are intended to be exposed
 * outside of the StepEngine and mapped to a transport response (e.g. HTTP).
 *
 * <p><strong>Purpose</strong></p>
 * <ul>
 *   <li>Represents an <em>expected</em> failure that is part of the service contract.</li>
 *   <li>Acts as the canonical failure signal across workflow steps, services, and APIs.</li>
 *   <li>Provides a stable boundary between internal execution logic and external response handling.</li>
 * </ul>
 *
 * <p><strong>Error Code</strong></p>
 * <ul>
 *   <li>Each {@code ServiceException} carries a machine-readable {@code errorCode}
 *       that uniquely identifies the failure type.</li>
 *   <li>{@code errorCode} is intended for clients, logging, monitoring, and alerting,
 *       and should remain stable across versions.</li>
 *   <li>Unlike the exception message, {@code errorCode} should not change frequently
 *       and should not contain dynamic data.</li>
 * </ul>
 *
 * <p><strong>Usage within StepEngine</strong></p>
 * <ul>
 *   <li>Steps should throw {@code ServiceException} (or its subclasses) for all
 *       known, intentional failure cases such as invalid requests, missing resources,
 *       or business rule violations.</li>
 *   <li>The StepEngine will catch all exceptions, perform workflow failure handling
 *       (e.g. rollback, cleanup, logging), and then:</li>
 *   <ul>
 *       <li>rethrow {@code ServiceException} as-is, or</li>
 *       <li>wrap unexpected exceptions into an internal service exception.</li>
 *   </ul>
 * </ul>
 *
 * <p><strong>Transport Mapping</strong></p>
 * <ul>
 *   <li>Instances of {@code ServiceException} are expected to be handled by the
 *       framework layer (e.g. Micronaut {@code ExceptionHandler}) and translated
 *       into appropriate transport responses (such as HTTP status codes).</li>
 *   <li>This class and its subclasses are intentionally transport-agnostic and
 *       should not depend on HTTP-specific concepts.</li>
 * </ul>
 *
 * <p><strong>Design Guidelines</strong></p>
 * <ul>
 *   <li>Use specific subclasses to represent semantic failure types
 *       (e.g. {@code InvalidRequestException}, {@code ResourceNotFoundException},
 *       {@code ConflictException}).</li>
 *   <li>Define a constant {@code errorCode} for each failure type (e.g. {@code DEVICE_NOT_FOUND}).</li>
 *   <li>Do not use this exception for unexpected system failures; those should
 *       be propagated and wrapped as internal errors by the StepEngine.</li>
 *   <li>Avoid catching generic exceptions and converting them into
 *       {@code ServiceException} unless the failure is truly a known,
 *       client-visible condition.</li>
 * </ul>
 *
 * <p><strong>Summary</strong></p>
 * <p>
 * {@code ServiceException} defines the boundary of "what failures are allowed to
 * leave the service." All external error responses should originate from this
 * hierarchy, ensuring consistent behavior across workflows and services.
 * </p>
 */
public abstract class ServiceException extends RuntimeException {

    private final String errorCode;

    protected ServiceException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected ServiceException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * Returns the machine-readable error code that uniquely identifies this failure.
     *
     * @return stable error code for client and observability usage
     */
    public String getErrorCode() {
        return errorCode;
    }
}