package dev.braintrust.trace;

import dev.braintrust.config.BraintrustConfig;
import io.opentelemetry.sdk.logs.SdkLoggerProviderBuilder;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.util.List;

public class HarnessShim {
    public static void addShutdownHook(Runnable target) {
        BraintrustShutdownHook.addShutdownHook(
                BraintrustShutdownHook.ShutdownOrder.TEST_HARNESS, target);
    }

    /**
     * Enable Braintrust tracing with additional span processors composited into the {@link
     * BraintrustSpanProcessor}'s delegate chain, so they see post-processed spans.
     */
    public static void enableTracing(
            BraintrustConfig config,
            SdkTracerProviderBuilder tracerProviderBuilder,
            List<SpanProcessor> additionalDelegates,
            SdkLoggerProviderBuilder loggerProviderBuilder,
            SdkMeterProviderBuilder meterProviderBuilder) {
        BraintrustTracing.enable(
                config,
                tracerProviderBuilder,
                additionalDelegates,
                loggerProviderBuilder,
                meterProviderBuilder);
    }
}
