package dev.braintrust.agent.dd;

import dev.braintrust.Braintrust;
import dev.braintrust.system.DDBridge;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.List;

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

        if (smokeTest) {
            var inMemoryExporter = new DDBridge.BridgeInMemorySpanExporter();
            tracerBuilder.addSpanProcessor(SimpleSpanProcessor.create(inMemoryExporter));
            DDBridge.setSmokeTestExporter(inMemoryExporter);
        }

        final var tracerProvider = tracerBuilder.build();
        final var tracer = tracerProvider.get("braintrust-java-dd-bridge");
        DDBridge.tracerProvider.set(tracerProvider);

        if (!DDBridge.registerDDTraceInterceptor()) {
            throw new IllegalStateException("Failed to register DD trace interceptor");
        }

        DDBridge.setTraceConsumer(mutableSpans -> {
            try {
                List<SpanData> spanDataList = DDSpanConverter.convertTrace(mutableSpans);
                DDSpanConverter.replayTrace(tracer, spanDataList);
            } catch (Exception e) {
                log.warn("Failed to convert DD trace to OTel SpanData", e);
            }
        });

        log.info("DD bridge consumer installed — DD traces will be forwarded to Braintrust.");
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
