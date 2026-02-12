package dev.braintrust.devserver;

import dev.braintrust.Braintrust;
import javax.annotation.Nullable;
import lombok.Builder;
import lombok.Getter;

/**
 * Context object attached to each authenticated request.
 *
 * <p>Contains the validated origin, extracted authentication token, and cached login state.
 */
@Getter
@Builder
public class RequestContext {
    /** Validated origin from CORS */
    private final String appOrigin;

    /** Extracted auth token (if present) */
    @Nullable private final String token;

    /** Cached login state */
    @Nullable private final Braintrust braintrust;
}
