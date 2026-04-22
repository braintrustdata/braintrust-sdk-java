package dev.braintrust;

import com.google.auto.service.AutoService;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AutoService(AutoConfigurationCustomizerProvider.class)
public class OtelAutoConfig implements AutoConfigurationCustomizerProvider {
    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration.addTracerProviderCustomizer(
                ((sdkTracerProviderBuilder, configProperties) -> {
                    try {
                        var bridgeClass =
                                OtelAutoConfig.class
                                        .getClassLoader()
                                        .loadClass("dev.braintrust.bootstrap.BraintrustBridge");
                        AtomicInteger otelInstallCount =
                                (AtomicInteger)
                                        bridgeClass.getDeclaredField("otelInstallCount").get(null);
                        if (!otelInstallCount.compareAndSet(0, 1)) {
                            log.warn(
                                    "otel install invoked more than once. This should not happen."
                                            + " Bailing.");
                            return sdkTracerProviderBuilder;
                        }
                    } catch (ClassNotFoundException e) {
                        // running without autoinstrumentation
                    } catch (Exception e) {
                        log.info(
                                "failed to invoke autoinstrumention bridge. Non-fatal. Continuing",
                                e);
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
