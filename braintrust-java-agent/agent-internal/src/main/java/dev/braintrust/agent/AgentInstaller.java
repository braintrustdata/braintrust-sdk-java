package dev.braintrust.agent;

import java.lang.instrument.Instrumentation;

import dev.braintrust.Braintrust;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

/**
 * The real agent installation logic
 */
public class AgentInstaller {
    /**
     * Called reflectively from AgentBootstrap premain
     */
    public static void install(String agentArgs, Instrumentation inst) {
        log("AgentInstaller.install() called");
        log("AgentInstaller classloader: "
                + AgentInstaller.class.getClassLoader().getClass().getName());
        log("Agent args: " + agentArgs);
        log("Instrumentation: retransform=" + inst.isRetransformClassesSupported());

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
            return sdkTracerProviderBuilder;
        }));
    }

    private static void log(String msg) {
        // TODO -- replace me with slf4j
        System.out.println("[braintrust]   " + msg);
    }
}
