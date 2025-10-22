package dev.braintrust;

import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.eval.Eval;
import dev.braintrust.prompt.BraintrustPromptLoader;
import dev.braintrust.trace.BraintrustTracing;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.logs.SdkLoggerProviderBuilder;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

/**
 * Main entry point for the Braintrust SDK.
 *
 * <p>This class provides access to all Braintrust functionality. Most users will interact with a
 * singleton instance via {@link #get()}, though you can create independent instances if needed.
 *
 * <p>The Braintrust instance also provides methods for enabling Braintrust in open telemetry
 * builders.
 *
 * <p>Additionally, vendor-specific instrumentation or functionality is provided by {@code
 * Braintrust<Vendor Name>}. E.g. {@code BraintrustOpenAI}, {@code BraintrustAnthropic}, etc.
 *
 * @see #get()
 * @see BraintrustConfig
 * @see #openTelemetryCreate()
 * @see #openTelemetryEnable(SdkTracerProviderBuilder, SdkLoggerProviderBuilder,
 *     SdkMeterProviderBuilder)
 */
@Slf4j
public class Braintrust {
    private static final String SDK_VERSION = SDKMain.loadVersionFromProperties();
    private static final AtomicReference<Braintrust> instance = new AtomicReference<>();

    /**
     * get or create the global braintrust instance. Most users will want to use this method to
     * access the Braintrust SDK.
     */
    public static Braintrust get() {
        var current = instance.get();
        if (null == current) {
            return get(BraintrustConfig.fromEnvironment());
        } else {
            return current;
        }
    }

    /** get or create the global braintrust instance from the given config */
    public static Braintrust get(BraintrustConfig config) {
        var current = instance.get();
        if (null == current) {
            var success = instance.compareAndSet(null, of(config));
            if (success) {
                log.info("initialized global Braintrust sdk {}", SDK_VERSION);
            }
            return instance.get();
        } else {
            return current;
        }
    }

    /** Create a new Braintrust instance from the given config */
    public static Braintrust of(BraintrustConfig config) {
        BraintrustApiClient apiClient = BraintrustApiClient.of(config);
        BraintrustPromptLoader promptLoader = BraintrustPromptLoader.of(config, apiClient);
        return new Braintrust(config, apiClient, promptLoader);
    }

    @Getter
    @Accessors(fluent = true)
    private final BraintrustConfig config;

    @Getter
    @Accessors(fluent = true)
    private final BraintrustApiClient apiClient;

    @Getter
    @Accessors(fluent = true)
    private final BraintrustPromptLoader promptLoader;

    private Braintrust(
            BraintrustConfig config,
            BraintrustApiClient apiClient,
            BraintrustPromptLoader promptLoader) {
        this.config = config;
        this.apiClient = apiClient;
        this.promptLoader = promptLoader;
    }

    public URI projectUri() {
        // TODO cache?
        return config.fetchProjectURI();
    }

    /**
     * Quick start method that sets up global OpenTelemetry with this Braintrust. <br>
     * <br>
     * If you're looking for more options for configuring Braintrust/OpenTelemetry, consult the
     * `enable` method.
     */
    public OpenTelemetry openTelemetryCreate() {
        return openTelemetryCreate(true);
    }

    /**
     * Quick start method that sets up OpenTelemetry with this Braintrust. <br>
     * <br>
     * If you're looking for more options for configuring Braintrust and OpenTelemetry, consult the
     * `enable` method.
     */
    public OpenTelemetry openTelemetryCreate(boolean registerGlobal) {
        return BraintrustTracing.of(this.config, registerGlobal);
    }

    /**
     * Add braintrust to existing open telemetry builders <br>
     * <br>
     * This method provides the most options for configuring Braintrust and OpenTelemetry. If you're
     * looking for a more user-friendly setup, consult the `openTelemetryCreate` methods. <br>
     * <br>
     * NOTE: if your otel setup does not have any particular builder, pass an instance of the
     * default provider builder. E.g. `SdkMeterProvider.builder()` <br>
     * <br>
     * NOTE: This method should only be invoked once. Enabling Braintrust multiple times is
     * unsupported and may lead to undesired behavior
     */
    public void openTelemetryEnable(
            @Nonnull SdkTracerProviderBuilder tracerProviderBuilder,
            @Nonnull SdkLoggerProviderBuilder loggerProviderBuilder,
            @Nonnull SdkMeterProviderBuilder meterProviderBuilder) {
        BraintrustTracing.enable(
                this.config, tracerProviderBuilder, loggerProviderBuilder, meterProviderBuilder);
    }

    /** Create a new eval builder */
    public <INPUT, OUTPUT> Eval.Builder<INPUT, OUTPUT> evalBuilder() {
        return (Eval.Builder<INPUT, OUTPUT>)
                Eval.builder().config(this.config).apiClient(this.apiClient);
    }
}
