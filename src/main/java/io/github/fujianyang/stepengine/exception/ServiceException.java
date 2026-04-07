package io.github.fujianyang.stepengine.exception;

import java.util.Objects;

/**
 * Base class for expected business-visible failures that may be exposed
 * outside StepEngine and mapped to a transport response.
 */
public abstract class ServiceException extends RuntimeException {

    private final String errorCode;

    protected ServiceException(String errorCode, String message) {
        super(message);
        this.errorCode = requireErrorCode(errorCode);
    }

    protected ServiceException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = requireErrorCode(errorCode);
    }

    public String getErrorCode() {
        return errorCode;
    }

    private static String requireErrorCode(String errorCode) {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        if (errorCode.isBlank()) {
            throw new IllegalArgumentException("errorCode must not be blank");
        }
        return errorCode;
    }
}