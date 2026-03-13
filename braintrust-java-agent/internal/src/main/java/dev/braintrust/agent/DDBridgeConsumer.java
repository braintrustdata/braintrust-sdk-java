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
        final boolean smokeTest = Boolean.getBoolean(SMOKE_TEST_PROP);

        var tracerBuilder = SdkTracerProvider.builder();
        Braintrust.get().openTelemetryEnable(tracerBuilder, SdkLoggerProvider.builder(), SdkMeterProvider.builder());
        final var tracerProvider = tracerBuilder.build();
        final var tracer = tracerProvider.get("braintrust-java-dd-bridge");
        DDBridge.tracerProvider.set(tracerProvider);

        // Extract the span processor list from the built SdkTracerProvider via reflection.
        // SdkTracerProvider stores processors in sharedState.getActiveSpanProcessor().
        final var spanProcessor = extractSpanProcessor(tracerProvider);

        if (!DDBridge.registerDDTraceInterceptor()) {
            throw new IllegalStateException("Failed to register DD trace interceptor");
        }

        DDBridge.setTraceConsumer(mutableSpans -> {
            try {
                List<SpanData> spanDataList = DDSpanConverter.convertTrace(mutableSpans);
                DDSpanConverter.replayTrace(tracer, spanDataList);
                if (smokeTest) {
                    for (var spanData : spanDataList) {
                        DDBridge.bridgedSpans
                                .computeIfAbsent(spanData.getTraceId(), k -> new CopyOnWriteArrayList<>())
                                .add(spanData);
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
