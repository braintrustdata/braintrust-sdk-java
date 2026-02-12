package dev.braintrust.agent.dd;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.InstrumentationLibraryInfo;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * Adapts a {@link SpanData} instance to the {@link ReadableSpan} interface so it can
 * be passed to {@link io.opentelemetry.sdk.trace.SpanProcessor#onEnd(ReadableSpan)}.
 */
final class SpanDataReadableSpan implements ReadableSpan {

    private final SpanData spanData;

    SpanDataReadableSpan(SpanData spanData) {
        this.spanData = spanData;
    }

    @Override
    public SpanContext getSpanContext() {
        return spanData.getSpanContext();
    }

    @Override
    public SpanContext getParentSpanContext() {
        return spanData.getParentSpanContext();
    }

    @Override
    public String getName() {
        return spanData.getName();
    }

    @Override
    public SpanData toSpanData() {
        return spanData;
    }

    @SuppressWarnings("deprecation")
    @Override
    public InstrumentationLibraryInfo getInstrumentationLibraryInfo() {
        return spanData.getInstrumentationLibraryInfo();
    }

    @Override
    public InstrumentationScopeInfo getInstrumentationScopeInfo() {
        return spanData.getInstrumentationScopeInfo();
    }

    @Override
    public boolean hasEnded() {
        return true;
    }

    @Override
    public long getLatencyNanos() {
        return spanData.getEndEpochNanos() - spanData.getStartEpochNanos();
    }

    @Override
    public SpanKind getKind() {
        return spanData.getKind();
    }

    @Override
    public <T> T getAttribute(AttributeKey<T> key) {
        return spanData.getAttributes().get(key);
    }

    @Override
    public Attributes getAttributes() {
        return spanData.getAttributes();
    }
}
