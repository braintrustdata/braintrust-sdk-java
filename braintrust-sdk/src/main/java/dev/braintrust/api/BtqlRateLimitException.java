package dev.braintrust.api;

import dev.braintrust.openapi.ApiException;
import java.net.http.HttpHeaders;

/** Thrown when the BTQL endpoint returns HTTP 429 (Too Many Requests). */
public final class BtqlRateLimitException extends ApiException {
    BtqlRateLimitException(
            int code, String message, HttpHeaders responseHeaders, String responseBody) {
        super(code, message, responseHeaders, responseBody);
    }
}
