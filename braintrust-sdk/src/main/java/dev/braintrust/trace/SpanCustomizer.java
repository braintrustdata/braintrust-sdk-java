package dev.braintrust.trace;

import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * A callback hook for altering span data. This is primarly used by SDK users who wish to customize
 * auto-instrumented spans.
 */
public interface SpanCustomizer {
    /**
     * called just before a span is exported. You may add/remove/delete most fields on the span.
     *
     * <p>The follow fields may NOT be altered: trace id, span id, parent id
     */
    default SpanData onSpanExport(SpanData span) {
        return span;
    }
}
