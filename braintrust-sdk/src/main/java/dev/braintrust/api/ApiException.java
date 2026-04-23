package dev.braintrust.api;

/** Deprecated. Please use {@link dev.braintrust.openapi.ApiException} instead */
@Deprecated
class ApiException extends RuntimeException {
    public ApiException(String message) {
        super(message);
    }

    public ApiException(Throwable cause) {
        super(cause);
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
