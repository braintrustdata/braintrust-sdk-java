package dev.braintrust.agent;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.interceptor.MutableSpan;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
public class DDSpanConverter {
    static List<SpanData> convertTrace(List<MutableSpan> mutableSpans) throws Exception {
        List<SpanData> result = new ArrayList<>(mutableSpans.size());
        // First pass: build a map of DD spanId -> DD span for parent lookup
        // DD spanId is a long; we need to find each span's parent
        for (MutableSpan ddSpan : mutableSpans) {
            SpanData spanData = convertSpan(ddSpan, mutableSpans);
            result.add(spanData);
        }
        return result;
    }

    static void replayTrace(Tracer tracer, List<SpanData> spans) {
    }

    private static SpanData convertSpan(MutableSpan ddSpan, List<MutableSpan> allSpans) throws Exception {
        // Extract trace ID and span ID via reflection
        Object agentSpanContext = contextMethod.invoke(ddSpan);
        DDTraceId ddTraceId = (DDTraceId) getTraceIdMethod.invoke(agentSpanContext);
        long ddSpanId = (long) getSpanIdMethod.invoke(agentSpanContext);

        // Convert DD trace ID (DDTraceId) to OTel 32-hex-char trace ID
        String traceIdHex = (String) toHexStringPaddedMethod.invoke(ddTraceId, 32);

        // Convert DD span ID (long) to OTel 16-hex-char span ID
        String spanIdHex = String.format("%016x", ddSpanId);

        // Find parent span ID
        String parentSpanIdHex = SpanContext.getInvalid().getSpanId();
        MutableSpan localRoot = ddSpan.getLocalRootSpan();
        if (localRoot != null && localRoot != ddSpan) {
            // This span has a parent. The parent's span ID is available through
            // the DD span's parentId. We need reflection for that too.
            try {
                Method getParentIdMethod = agentSpanContext.getClass().getMethod("getParentId");
                long parentId = (long) getParentIdMethod.invoke(agentSpanContext);
                if (parentId != 0) {
                    parentSpanIdHex = String.format("%016x", parentId);
                }
            } catch (NoSuchMethodException e) {
                // Fall back — can't determine parent
                log.debug("Cannot determine parent span ID for '{}'", ddSpan.getResourceName());
            }
        }

        SpanContext spanContext = SpanContext.create(
                traceIdHex, spanIdHex, TraceFlags.getSampled(), TraceState.getDefault());

        SpanContext parentSpanContext;
        if (!parentSpanIdHex.equals(SpanContext.getInvalid().getSpanId())) {
            parentSpanContext = SpanContext.create(
                    traceIdHex, parentSpanIdHex, TraceFlags.getSampled(), TraceState.getDefault());
        } else {
            parentSpanContext = SpanContext.getInvalid();
        }

        // Convert attributes
        Attributes attributes = convertTags(ddSpan.getTags());

        // Convert span kind from DD operation name convention
        SpanKind spanKind = inferSpanKind(ddSpan);

        // Convert status
        StatusData status = ddSpan.isError()
                ? StatusData.create(StatusCode.ERROR, "")
                : StatusData.unset();

        long startEpochNanos = ddSpan.getStartTime();
        long endEpochNanos = startEpochNanos + ddSpan.getDurationNano();

        return new ImmutableSpanData(
                spanContext,
                parentSpanContext,
                Resource.getDefault(),
                InstrumentationScopeInfo.create("datadog"),
                String.valueOf(ddSpan.getResourceName()),
                spanKind,
                startEpochNanos,
                endEpochNanos,
                attributes,
                status);
    }

    private static Attributes convertTags(Map<String, Object> tags) {
        if (tags == null || tags.isEmpty()) {
            return Attributes.empty();
        }
        AttributesBuilder builder = Attributes.builder();
        for (var entry : tags.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String s) {
                builder.put(key, s);
            } else if (value instanceof Long l) {
                builder.put(key, l);
            } else if (value instanceof Integer i) {
                builder.put(key, (long) i);
            } else if (value instanceof Double d) {
                builder.put(key, d);
            } else if (value instanceof Float f) {
                builder.put(key, (double) f);
            } else if (value instanceof Boolean b) {
                builder.put(key, b);
            } else if (value != null) {
                builder.put(key, value.toString());
            }
        }
        return builder.build();
    }

    /**
     * Infers OTel SpanKind from DD's operation name convention.
     * DD's OTel shim maps SpanKind to operation names like "internal", "server",
     * "client.request", "producer", "consumer".
     */
    private static SpanKind inferSpanKind(MutableSpan ddSpan) {
        CharSequence opName = ddSpan.getOperationName();
        if (opName == null) return SpanKind.INTERNAL;
        String op = opName.toString();
        return switch (op) {
            case "server.request", "server" -> SpanKind.SERVER;
            case "client.request", "client" -> SpanKind.CLIENT;
            case "producer" -> SpanKind.PRODUCER;
            case "consumer" -> SpanKind.CONSUMER;
            default -> SpanKind.INTERNAL;
        };
    }
}
