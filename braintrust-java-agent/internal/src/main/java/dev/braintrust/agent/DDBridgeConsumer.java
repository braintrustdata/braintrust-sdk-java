package dev.braintrust.agent;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.interceptor.MutableSpan;
import dev.braintrust.Braintrust;
import dev.braintrust.system.DDBridge;
import dev.braintrust.trace.BraintrustTracing;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bridges Datadog traces into Braintrust's span export pipeline.
 */
@Slf4j
public class DDBridgeConsumer {
    private static volatile Method contextMethod;
    private static volatile Method getTraceIdMethod;
    private static volatile Method getSpanIdMethod;
    private static volatile Method toHexStringMethod;
    private static volatile Method toHexStringPaddedMethod;
    private static volatile boolean reflectionInitialized;

    /** System property that enables in-memory span collection for smoke tests. */
    private static final String SMOKE_TEST_PROP = "braintrust.dd.bridge.smoketest";

    public static void install() {
        initReflection();

        final boolean smokeTest = Boolean.getBoolean(SMOKE_TEST_PROP);

        var tracerBuilder = SdkTracerProvider.builder();
        Braintrust.get().openTelemetryEnable(tracerBuilder, SdkLoggerProvider.builder(), SdkMeterProvider.builder());
        final var tracerProvider = tracerBuilder.build();
        DDBridge.tracerProvider.set(tracerProvider);

        // Extract the span processor list from the built SdkTracerProvider via reflection.
        // SdkTracerProvider stores processors in sharedState.getActiveSpanProcessor().
        final var spanProcessor = extractSpanProcessor(tracerProvider);

        if (!DDBridge.registerDDTraceInterceptor()) {
            throw new IllegalStateException("Failed to register DD trace interceptor");
        }

        DDBridge.setTraceConsumer(mutableSpans -> {
            try {
                List<SpanData> spanDataList = convertTrace(mutableSpans);
                if (!spanDataList.isEmpty()) {
                    if (smokeTest) {
                        for (var spanData : spanDataList) {
                            DDBridge.bridgedSpans
                                    .computeIfAbsent(spanData.getTraceId(), k -> new CopyOnWriteArrayList<>())
                                    .add(spanData);
                        }
                    }
                    // Feed through BT's span processor pipeline
                    if (spanProcessor != null) {
                        for (var spanData : spanDataList) {
                            spanProcessor.onEnd(new SpanDataReadableSpan(spanData));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to convert DD trace to OTel SpanData", e);
            }
        });

        log.info("DD bridge consumer installed — DD traces will be forwarded to Braintrust.");
    }

    /**
     * Extracts the active SpanProcessor from a built SdkTracerProvider via reflection.
     * Returns null if extraction fails (spans won't be exported but DD still works).
     */
    private static io.opentelemetry.sdk.trace.SpanProcessor extractSpanProcessor(SdkTracerProvider provider) {
        try {
            var sharedStateField = SdkTracerProvider.class.getDeclaredField("sharedState");
            sharedStateField.setAccessible(true);
            var sharedState = sharedStateField.get(provider);

            var getProcessorMethod = sharedState.getClass().getMethod("getActiveSpanProcessor");
            return (io.opentelemetry.sdk.trace.SpanProcessor) getProcessorMethod.invoke(sharedState);
        } catch (Exception e) {
            log.warn("Failed to extract span processor from SdkTracerProvider — DD spans won't be exported to BT", e);
            return null;
        }
    }

    /**
     * Converts a list of DD {@link MutableSpan}s (one trace) to OTel {@link SpanData}.
     */
    static List<SpanData> convertTrace(List<MutableSpan> mutableSpans) {
        if (!reflectionInitialized) {
            log.warn("Reflection not initialized — cannot convert DD spans");
            return Collections.emptyList();
        }

        List<SpanData> result = new ArrayList<>(mutableSpans.size());

        // First pass: build a map of DD spanId -> DD span for parent lookup
        // DD spanId is a long; we need to find each span's parent
        for (MutableSpan ddSpan : mutableSpans) {
            try {
                SpanData spanData = convertSpan(ddSpan, mutableSpans);
                if (spanData != null) {
                    result.add(spanData);
                }
            } catch (Exception e) {
                log.warn("Failed to convert DD span '{}': {}", ddSpan.getResourceName(), e.getMessage());
            }
        }
        return result;
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

    /**
     * Initialize reflection handles for extracting IDs from DD spans.
     * DD's AgentSpan and AgentSpanContext are on the bootstrap classpath
     * (from dd-trace-java's internal-api), so we can load them.
     */
    private static void initReflection() {
        try {
            // MutableSpan's runtime type is DDSpan which implements AgentSpan.
            // AgentSpan has context() -> AgentSpanContext
            // AgentSpanContext has getTraceId() -> DDTraceId, getSpanId() -> long
            Class<?> agentSpanClass = Class.forName(
                    "datadog.trace.bootstrap.instrumentation.api.AgentSpan", true, null);
            contextMethod = agentSpanClass.getMethod("context");

            Class<?> agentSpanContextClass = Class.forName(
                    "datadog.trace.bootstrap.instrumentation.api.AgentSpanContext", true, null);
            getTraceIdMethod = agentSpanContextClass.getMethod("getTraceId");
            getSpanIdMethod = agentSpanContextClass.getMethod("getSpanId");

            // DDTraceId.toHexStringPadded(int) returns padded hex — use 32 for 128-bit
            toHexStringPaddedMethod = DDTraceId.class.getMethod("toHexStringPadded", int.class);
            // DDTraceId.toHexString() returns minimal hex
            toHexStringMethod = DDTraceId.class.getMethod("toHexString");

            reflectionInitialized = true;
            log.info("DD bridge reflection initialized successfully.");
        } catch (Exception e) {
            log.error("Failed to initialize DD bridge reflection — span conversion will not work", e);
            reflectionInitialized = false;
        }
    }

    /**
     * Checks whether the Datadog agent is present and configured for OTel integration.
     */
    public static boolean jvmRunningWithDatadogOtel() {
        try {
            Class.forName("datadog.trace.bootstrap.Agent", false, null);
        } catch (ClassNotFoundException e) {
            return false;
        }
        String sysProp = System.getProperty("dd.trace.otel.enabled");
        if (sysProp != null) {
            return Boolean.parseBoolean(sysProp);
        }
        String envVar = System.getenv("DD_TRACE_OTEL_ENABLED");
        return Boolean.parseBoolean(envVar);
    }
}
