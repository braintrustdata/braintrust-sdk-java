package dev.braintrust.agent;

import java.lang.instrument.Instrumentation;

import dev.braintrust.Braintrust;
import dev.braintrust.agent.instrumentation.InstrumentationInstaller;
import dev.braintrust.bootstrap.BraintrustBridge;
import dev.braintrust.bootstrap.BraintrustClassLoader;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import lombok.extern.slf4j.Slf4j;

/**
 * The real agent installation logic
 */
@Slf4j
public class BraintrustAgent {

    /**
     * Called reflectively from AgentBootstrap premain.
     */
    public static void install(String agentArgs, Instrumentation inst) {
        if (!(BraintrustAgent.class.getClassLoader() instanceof BraintrustClassLoader)) {
            throw new IllegalCallerException(
                    "Braintrust agent can only run on a braintrust classloader");
        }
        log.info("invoked on classloader: {}", BraintrustAgent.class.getClassLoader().getClass().getName());
        log.info("agentArgs: {}", agentArgs);
        log.info("Instrumentation: retransform={}", inst.isRetransformClassesSupported());
        // Fail fast if there are any issues with the Braintrust SDK
        Braintrust.get();

        InstrumentationInstaller.install(inst, BraintrustAgent.class.getClassLoader());
    }

    /**
     * Called reflectively from OtelAutoConfiguration.
     */
    public void configureOpenTelemetry(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration.addTracerProviderCustomizer(
                ((sdkTracerProviderBuilder, configProperties) -> {
                    var loggerBuilder = SdkLoggerProvider.builder();
                    var meterBuilder = SdkMeterProvider.builder();
                    Braintrust.get()
                            .openTelemetryEnable(
                                    sdkTracerProviderBuilder, loggerBuilder, meterBuilder);
                    var installCount = BraintrustBridge.otelInstallCount.incrementAndGet();
                    if (installCount > 1) {
                        log.warn("unexpected otel install count: {}", installCount);
                    }
                    return sdkTracerProviderBuilder;
                }));
    }

}
