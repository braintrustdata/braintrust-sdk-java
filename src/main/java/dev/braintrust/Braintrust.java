package dev.braintrust;

import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.prompt.BraintrustPromptLoader;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.logs.SdkLoggerProviderBuilder;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import java.net.URI;
import javax.annotation.Nonnull;
import lombok.Getter;
import lombok.experimental.Accessors;

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
public class Braintrust {
    /**
     * get or create the global braintrust instance. Most users will want to use this method to
     * access the Braintrust SDK.
     */
    public static Braintrust get() {
        throw new RuntimeException("TODO");
    }

    /** get or create the global braintrust instance from the given config */
    public static Braintrust get(BraintrustConfig config) {
        throw new RuntimeException("TODO");
    }

    /** Create a new Braintrust instance from the given config */
    public static Braintrust of(BraintrustConfig config) {
        throw new RuntimeException("TODO");
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
        throw new RuntimeException("TODO");
    }

    public OpenTelemetry openTelemetryCreate() {
        throw new RuntimeException("TODO");
    }

    public OpenTelemetry openTelemetryCreate(boolean registerGlobal) {
        throw new RuntimeException("TODO");
    }

    public void openTelemetryEnable(
            @Nonnull SdkTracerProviderBuilder tracerProviderBuilder,
            @Nonnull SdkLoggerProviderBuilder loggerProviderBuilder,
            @Nonnull SdkMeterProviderBuilder meterProviderBuilder) {
        throw new RuntimeException("TODO");
    }
}
