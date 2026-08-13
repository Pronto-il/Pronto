package com.pronto.common.exception;

/**
 * Thrown by service-layer code for any expected, business-meaningful failure that should
 * surface as the standard error envelope (see {@code docs/architecture/api-contract.md}
 * §1). Caught centrally by {@link GlobalExceptionHandler}.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Object details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ApiException(ErrorCode code, String message, Object details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public ErrorCode getCode() {
        return code;
    }

    public Object getDetails() {
        return details;
    }
}
