package dev.braintrust.agent;

import java.lang.instrument.Instrumentation;

import dev.braintrust.Braintrust;
import dev.braintrust.bootstrap.BraintrustBridge;
import dev.braintrust.bootstrap.BraintrustClassLoader;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;

/**
 * The real agent installation logic
 */
public class BraintrustAgent {
    /**
     * Called reflectively from AgentBootstrap premain
     */
    public static void install(String agentArgs, Instrumentation inst) {
        if (!(BraintrustAgent.class.getClassLoader() instanceof BraintrustClassLoader)) {
            throw new IllegalCallerException("Braintrust agent can only run on a braintrust classloader");
        }
        log("AgentInstaller.install() called");
        log("AgentInstaller classloader: "
                + BraintrustAgent.class.getClassLoader().getClass().getName());
        log("Agent args: " + agentArgs);
        log("Instrumentation: retransform=" + inst.isRetransformClassesSupported());
        Braintrust.get(); // call this now so we'll fail fast if there are any issues using the braintrust sdk

        /*
        var agentBuilder =
                new AgentBuilder.Default()
                        .ignore(not(nameStartsWith("com.openai.").or(nameStartsWith("com.anthropic.")) .or(nameStartsWith("com.google.genai."))))
                        // Use retransformation so we can instrument classes already loaded
                        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                        .with(new LoggingListener());
        agentBuilder.installOn(inst);
        log("ByteBuddy instrumentation installed.");
         */
    }

    /**
     * Called reflectively from OtelAutConfiguration
     */
    public void configureOpenTelemetry(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration.addTracerProviderCustomizer(((sdkTracerProviderBuilder, configProperties) -> {
            var loggerBuilder = SdkLoggerProvider.builder();
            var meterBuilder = SdkMeterProvider.builder();
            Braintrust.get().openTelemetryEnable(sdkTracerProviderBuilder, loggerBuilder, meterBuilder);
            var installCount = BraintrustBridge.otelInstallCount.incrementAndGet();
            if (installCount > 1) {
                log("WARNING: unexpected otel install count: " + installCount);
            }
            return sdkTracerProviderBuilder;
        }));
    }

    private static void log(String msg) {
        // TODO -- replace me with slf4j
        System.out.println("[braintrust]   " + msg);
    }
}
