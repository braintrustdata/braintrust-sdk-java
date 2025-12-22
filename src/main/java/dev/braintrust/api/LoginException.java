package dev.braintrust.api;

import javax.annotation.Nullable;

/**
 * Exception thrown when login to Braintrust fails.
 *
 * <p>This is a RuntimeException so it doesn't require explicit handling, but callers can catch it
 * specifically if they want to handle login failures differently from other errors.
 */
public class LoginException extends RuntimeException {
    public LoginException(String message) {
        super(message);
    }

    public LoginException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }

    public LoginException(Throwable cause) {
        super(cause);
    }
}
