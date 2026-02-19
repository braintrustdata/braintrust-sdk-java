package dev.braintrust.agent.internal;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * Span processor for the Braintrust agent that enriches spans with the {@code braintrust.parent}
 * attribute.
 *
 * <p>The parent attribute tells Braintrust's ingestion endpoint where to route spans. It checks (in
 * order):
 * <ol>
 *   <li>Existing {@code braintrust.parent} attribute on the span (set by application code)</li>
 *   <li>OTel Baggage propagated via W3C headers (cross-process parent propagation)</li>
 *   <li>Default project from agent configuration (environment variables)</li>
 * </ol>
 *
 * <p>Mirrors the SDK's {@code BraintrustSpanProcessor} but without Lombok or full SDK dependencies.
 */
final class AgentSpanProcessor implements SpanProcessor {

    static final String PARENT_ATTR_NAME = "braintrust.parent";
    static final AttributeKey<String> PARENT_KEY = AttributeKey.stringKey(PARENT_ATTR_NAME);

    private final AgentConfig config;
    private final SpanProcessor delegate;

    AgentSpanProcessor(AgentConfig config, SpanProcessor delegate) {
        this.config = config;
        this.delegate = delegate;
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        // Only set parent if the span doesn't already have one
        if (span.getAttribute(PARENT_KEY) == null) {
            // Check baggage for distributed tracing (cross-process parent propagation)
            var parentFromBaggage = getParentFromBaggage(parentContext);
            if (parentFromBaggage != null) {
                span.setAttribute(PARENT_KEY, parentFromBaggage);
            } else {
                // Fall back to default parent from config
                config.getBraintrustParentValue()
                        .ifPresent(parentValue -> span.setAttribute(PARENT_KEY, parentValue));
            }
        }

        delegate.onStart(parentContext, span);
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        if (config.debug()) {
            var data = span.toSpanData();
            System.out.printf(
                    "[braintrust] Span: name=%s traceId=%s duration=%dms%n",
                    data.getName(),
                    data.getTraceId(),
                    (data.getEndEpochNanos() - data.getStartEpochNanos()) / 1_000_000);
        }
        delegate.onEnd(span);
    }

    @Override
    public boolean isEndRequired() {
        return delegate.isEndRequired();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }

    @Override
    public CompletableResultCode forceFlush() {
        return delegate.forceFlush();
    }

    /**
     * Reads the {@code braintrust.parent} value from OTel Baggage, which propagates automatically
     * across process boundaries via W3C Baggage headers.
     */
    private static String getParentFromBaggage(Context ctx) {
        try {
            Baggage baggage = Baggage.fromContext(ctx);
            String value = baggage.getEntryValue(PARENT_ATTR_NAME);
            return (value != null && !value.isEmpty()) ? value : null;
        } catch (Exception e) {
            return null;
        }
    }
}
