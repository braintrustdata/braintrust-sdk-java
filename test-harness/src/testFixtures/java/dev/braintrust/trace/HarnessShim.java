package dev.braintrust.trace;

import dev.braintrust.config.BraintrustConfig;
import io.opentelemetry.sdk.logs.SdkLoggerProviderBuilder;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.SpanExporter;

public class HarnessShim {
    public static void addShutdownHook(Runnable target) {
        BraintrustShutdownHook.addShutdownHook(
                BraintrustShutdownHook.ShutdownOrder.TEST_HARNESS, target);
    }

    /** Enable Braintrust tracing and capture the same transformed spans as the OTLP transport. */
    public static void enableTracing(
            BraintrustConfig config,
            SdkTracerProviderBuilder tracerProviderBuilder,
            SpanExporter capturedSpans,
            SdkLoggerProviderBuilder loggerProviderBuilder,
            SdkMeterProviderBuilder meterProviderBuilder) {
        BraintrustTracing.enable(
                config,
                tracerProviderBuilder,
                transport -> SpanExporter.composite(transport, capturedSpans),
                loggerProviderBuilder,
                meterProviderBuilder);
    }
}
