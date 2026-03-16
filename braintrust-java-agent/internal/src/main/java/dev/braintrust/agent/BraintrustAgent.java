package dev.braintrust.agent;

import com.google.auto.service.AutoService;
import dev.braintrust.Braintrust;
import dev.braintrust.agent.dd.DDBridgeConsumer;
import dev.braintrust.bootstrap.BraintrustBridge;
import dev.braintrust.bootstrap.BraintrustClassLoader;
import dev.braintrust.instrumentation.InstrumentationInstaller;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import java.lang.instrument.Instrumentation;

/** The real agent installation logic */
// @Slf4j
@AutoService(AutoConfigurationCustomizerProvider.class)
public class BraintrustAgent implements AutoConfigurationCustomizerProvider {

    /** Called reflectively from AgentBootstrap premain. */
    public static void install(String agentArgs, Instrumentation inst) {
        if (!(BraintrustAgent.class.getClassLoader() instanceof BraintrustClassLoader)) {
            throw new IllegalCallerException(
                    "Braintrust agent can only run on a braintrust classloader");
        }
        // log.info("invoked on classloader: {}",
        // BraintrustAgent.class.getClassLoader().getClass().getName());
        // log.info("agentArgs: {}", agentArgs);
        // log.info("Instrumentation: retransform={}", inst.isRetransformClassesSupported());
        // Fail fast if there are any issues with the Braintrust SDK or otel
        Braintrust.get();

        if (DDBridgeConsumer.jvmRunningWithDatadogOtel()) {
            DDBridgeConsumer.install();
        }
        InstrumentationInstaller.install(inst, BraintrustAgent.class.getClassLoader());
    }

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration.addTracerProviderCustomizer(
                ((sdkTracerProviderBuilder, configProperties) -> {
                    if (!BraintrustBridge.otelInstallCount.compareAndSet(0, 1)) {
                        // log.warn("otel install invoked more than once. This should not happen.
                        // Bailing.");
                        return sdkTracerProviderBuilder;
                    }
                    var loggerBuilder = SdkLoggerProvider.builder();
                    var meterBuilder = SdkMeterProvider.builder();
                    Braintrust.get()
                            .openTelemetryEnable(
                                    sdkTracerProviderBuilder, loggerBuilder, meterBuilder);
                    return sdkTracerProviderBuilder;
                }));
    }
}
