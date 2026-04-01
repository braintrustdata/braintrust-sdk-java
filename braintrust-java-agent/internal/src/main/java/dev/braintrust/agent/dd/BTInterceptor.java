package dev.braintrust.agent.dd;

import datadog.trace.api.GlobalTracer;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import dev.braintrust.Braintrust;
import dev.braintrust.trace.BraintrustTracing;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BTInterceptor implements TraceInterceptor {
    private static final AtomicBoolean installed = new AtomicBoolean(false);

    public static void install() {
        if (!installed.compareAndExchange(false, true)) {
            try {
                if (!DDSpanConverter.initialize()) {
                    log.warn(
                            "failed to initialize DD span converter. Braintrust traces will not be"
                                    + " reported.");
                    return;
                }
                var tbBuilder =
                        SdkTracerProvider.builder().setIdGenerator(OverridableIdGenerator.INSTANCE);
                Braintrust.get()
                        .openTelemetryEnable(
                                tbBuilder, SdkLoggerProvider.builder(), SdkMeterProvider.builder());
                final var traceProvider = tbBuilder.build();
                var interceptor = new BTInterceptor(999, traceProvider);
                if (!GlobalTracer.get().addTraceInterceptor(interceptor)) {
                    log.warn(
                            "trace interceptor install failed due to conflicting priorities."
                                    + " Braintrust traces will not be reported.");
                    return;
                }
                log.info("trace interceptor successfully installed");
            } catch (Exception e) {
                log.warn(
                        "trace interceptor install failed. Braintrust traces will not be reported.",
                        e);
                // Don't reset the flag. We don't want to try again.
            }
        }
    }

    private final int priority;
    private final Tracer tracer;

    private BTInterceptor(int priority, SdkTracerProvider traceProvider) {
        this.priority = priority;
        this.tracer = BraintrustTracing.getTracer(traceProvider);
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public Collection<? extends MutableSpan> onTraceComplete(
            Collection<? extends MutableSpan> trace) {
        try {
            List<SpanData> spanDataList = DDSpanConverter.convertTrace(List.copyOf(trace));
            DDSpanConverter.replayTrace(tracer, spanDataList);
        } catch (Exception e) {
            log.debug("failed to replay traces", e);
        }
        return trace;
    }
}
