package dev.braintrust.agent.dd;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.InstrumentationLibraryInfo;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.util.Collections;
import java.util.List;

/** Immutable implementation of {@link SpanData} for converting DD spans to OTel format. */
record ImmutableSpanData(
        SpanContext spanContext,
        SpanContext parentSpanContext,
        Resource resource,
        InstrumentationScopeInfo instrumentationScopeInfo,
        String name,
        SpanKind kind,
        long startEpochNanos,
        long endEpochNanos,
        Attributes attributes,
        StatusData status)
        implements SpanData {

    @Override
    public SpanContext getSpanContext() {
        return spanContext;
    }

    @Override
    public SpanContext getParentSpanContext() {
        return parentSpanContext;
    }

    @Override
    public Resource getResource() {
        return resource;
    }

    @Override
    public InstrumentationScopeInfo getInstrumentationScopeInfo() {
        return instrumentationScopeInfo;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public SpanKind getKind() {
        return kind;
    }

    @Override
    public long getStartEpochNanos() {
        return startEpochNanos;
    }

    @Override
    public long getEndEpochNanos() {
        return endEpochNanos;
    }

    @Override
    public Attributes getAttributes() {
        return attributes;
    }

    @Override
    public StatusData getStatus() {
        return status;
    }

    @Override
    public List<EventData> getEvents() {
        return Collections.emptyList();
    }

    @Override
    public List<LinkData> getLinks() {
        return Collections.emptyList();
    }

    @Override
    public boolean hasEnded() {
        return true;
    }

    @Override
    public int getTotalRecordedEvents() {
        return 0;
    }

    @Override
    public int getTotalRecordedLinks() {
        return 0;
    }

    @Override
    public int getTotalAttributeCount() {
        return attributes.size();
    }

    @SuppressWarnings("deprecation")
    @Override
    public InstrumentationLibraryInfo getInstrumentationLibraryInfo() {
        return InstrumentationLibraryInfo.create(
                instrumentationScopeInfo.getName(), instrumentationScopeInfo.getVersion());
    }
}
