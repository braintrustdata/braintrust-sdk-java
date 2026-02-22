package dev.braintrust.trace;

import dev.braintrust.config.BraintrustConfig;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.SdkLoggerProviderBuilder;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.ServiceAttributes;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;

/**
 * Main entry point for Braintrust tracing setup. Provides convenient methods to initialize
 * OpenTelemetry with Braintrust configuration.
 */
@Slf4j
public final class BraintrustTracing {
    public static final String PARENT_KEY = "braintrust.parent";
    static final String OTEL_SERVICE_NAME = "braintrust-app";
    static final String INSTRUMENTATION_NAME = "braintrust-java";
    static final String INSTRUMENTATION_VERSION = loadVersionFromProperties();

    /**
     * Quick start method that sets up global OpenTelemetry with Braintrust defaults. <br>
     * <br>
     * If you're looking for more options for configuring Braintrust/OpenTelemetry, consult the
     * `enable` method.
     */
    public static OpenTelemetry quickstart() {
        return of(BraintrustConfig.fromEnvironment(), true);
    }

    /**
     * Quick start method that sets up OpenTelemetry with custom Braintrust and otel settings. <br>
     * <br>
     * If you're looking for more options for configuring Braintrust and OpenTelemetry, consult the
     * `enable` method.
     */
    public static OpenTelemetry of(@Nonnull BraintrustConfig config, boolean registerGlobal) {
        var tracerBuilder = SdkTracerProvider.builder();
        var loggerBuilder = SdkLoggerProvider.builder();
        var meterBuilder = SdkMeterProvider.builder();
        var contextPropagator =
                ContextPropagators.create(
                        TextMapPropagator.composite(
                                W3CTraceContextPropagator.getInstance(),
                                W3CBaggagePropagator.getInstance()));
        enable(config, tracerBuilder, loggerBuilder, meterBuilder);
        var openTelemetry =
                OpenTelemetrySdk.builder()
                        .setTracerProvider(tracerBuilder.build())
                        .setLoggerProvider(loggerBuilder.build())
                        .setMeterProvider(meterBuilder.build())
                        .setPropagators(contextPropagator)
                        .build();
        if (registerGlobal) {
            GlobalOpenTelemetry.set(openTelemetry);
            log.debug("Registered OpenTelemetry globally");
        }
        return openTelemetry;
    }

    /**
     * Add braintrust to existing open telemetry builders <br>
     * <br>
     * This method provides the most options for configuring Braintrust and OpenTelemetry. If you're
     * looking for a more user-friendly setup, consult the `quickstart` methods. <br>
     * <br>
     * NOTE: if your otel setup does not have any particular builder, pass an instance of the
     * default provider builder. E.g. `SdkMeterProvider.builder()` <br>
     * <br>
     * NOTE: This method should only be invoked once. Enabling Braintrust multiple times is
     * unsupported and may lead to undesired behavior
     */
    public static void enable(
            @Nonnull BraintrustConfig config,
            @Nonnull SdkTracerProviderBuilder tracerProviderBuilder,
            @Nonnull SdkLoggerProviderBuilder loggerProviderBuilder,
            @Nonnull SdkMeterProviderBuilder meterProviderBuilder) {
        final Duration exportInterval = Duration.ofSeconds(5);
        final int maxQueueSize = 2048;
        final int maxExportBatchSize = 512;
        log.info(sdkInfoLogMessage());

        // Create resource first so BraintrustSpanProcessor can access service.name
        var resourceBuilder =
                Resource.getDefault().toBuilder()
                        .put(ServiceAttributes.SERVICE_NAME, OTEL_SERVICE_NAME)
                        .put(ServiceAttributes.SERVICE_VERSION, INSTRUMENTATION_VERSION);
        var resource = resourceBuilder.build();

        // spans
        var spanProcessor =
                new BraintrustSpanProcessor(
                        config,
                        BatchSpanProcessor.builder(new BraintrustSpanExporter(config))
                                .setScheduleDelay(exportInterval.toMillis(), TimeUnit.MILLISECONDS)
                                .setMaxQueueSize(maxQueueSize)
                                .setMaxExportBatchSize(maxExportBatchSize)
                                .build());
        tracerProviderBuilder.addResource(resource).addSpanProcessor(spanProcessor);
        // logs
        var logProcessor =
                BatchLogRecordProcessor.builder(new BraintrustLogExporter(config))
                        .setScheduleDelay(exportInterval.toMillis(), TimeUnit.MILLISECONDS)
                        .setMaxQueueSize(maxQueueSize)
                        .setMaxExportBatchSize(maxExportBatchSize)
                        .build();
        loggerProviderBuilder.addLogRecordProcessor(logProcessor);

        // NOTE: we don't actually do anything with otel metrics right now,
        // but it's included in the method signature so we can do so in the future without
        // introducing a breaking change

        BraintrustShutdownHook.addShutdownHook(
                () -> {
                    log.debug("Shutting down. Force-Flushing all otel data.");
                    var result =
                            CompletableResultCode.ofAll(
                                    // run all flushes in parallel. Should (rarely)
                                    // block for approx 10 seconds max
                                    Stream.of(spanProcessor.shutdown(), logProcessor.shutdown())
                                            .map(operation -> operation.join(10, TimeUnit.SECONDS))
                                            .toList());
                    log.debug(
                            "otel shutdown complete. Flush done: %s, Flush successful: %s"
                                    .formatted(result.isDone(), result.isSuccess()));
                });
    }

    /** Gets a tracer with Braintrust instrumentation scope. */
    public static Tracer getTracer() {
        return getTracer(GlobalOpenTelemetry.get());
    }

    /** Gets a tracer from a specific OpenTelemetry instance. */
    public static Tracer getTracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer(INSTRUMENTATION_NAME, INSTRUMENTATION_VERSION);
    }

    private static String sdkInfoLogMessage() {
        return "Initializing Braintrust OpenTelemetry with service=%s, instrumentation-name=%s, instrumentation-version=%s, jvm-version=%s, jvm-vendor=%s, jvm-name=%s"
                .formatted(
                        OTEL_SERVICE_NAME,
                        INSTRUMENTATION_NAME,
                        INSTRUMENTATION_VERSION,
                        System.getProperty("java.runtime.version"),
                        System.getProperty("java.vendor"),
                        System.getProperty("java.vm.name"));
    }

    static String loadVersionFromProperties() {
        try (var is = BraintrustTracing.class.getResourceAsStream("/braintrust.properties")) {
            var props = new Properties();
            props.load(is);
            return props.getProperty("sdk.version");
        } catch (Exception e) {
            throw new RuntimeException("unable to determine sdk version", e);
        }
    }

    private BraintrustTracing() {}
}
