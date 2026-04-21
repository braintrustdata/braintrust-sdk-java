package dev.braintrust.agent;

import com.google.auto.service.AutoService;
import dev.braintrust.Braintrust;
import dev.braintrust.bootstrap.BraintrustBridge;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AutoService(AutoConfigurationCustomizerProvider.class)
public class OtelAutoConfig implements AutoConfigurationCustomizerProvider {
    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration.addTracerProviderCustomizer(
                ((sdkTracerProviderBuilder, configProperties) -> {
                    if (!BraintrustBridge.otelInstallCount.compareAndSet(0, 1)) {
                        log.warn(
                                "otel install invoked more than once. This should not happen."
                                        + " Bailing.");
                        return sdkTracerProviderBuilder;
                    }
                    Braintrust.get()
                            .openTelemetryEnable(
                                    sdkTracerProviderBuilder,
                                    SdkLoggerProvider.builder(),
                                    SdkMeterProvider.builder());
                    return sdkTracerProviderBuilder;
                }));
    }
}
