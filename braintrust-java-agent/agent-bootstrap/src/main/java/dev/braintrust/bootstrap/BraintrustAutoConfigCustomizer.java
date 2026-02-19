package dev.braintrust.bootstrap;

import dev.braintrust.agent.BraintrustAgent;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * OTel SDK autoconfiguration customizer that injects the Braintrust span processor into the tracer
 * provider during SDK initialization.
 *
 * <p>This class is discovered by {@link java.util.ServiceLoader} when OTel SDK autoconfiguration
 * runs (triggered by the first call to {@code GlobalOpenTelemetry.get()}). It loads the actual span
 * processor implementation from {@link BraintrustAgent#agentClassLoader} (the isolated {@link
 * BraintrustClassLoader}) to keep the heavy dependencies (OTLP exporter, OkHttp, etc.) out of the
 * bootstrap classpath.
 *
 * <p>This class lives on the bootstrap classpath so that ServiceLoader can find it regardless of
 * which classloader triggers autoconfiguration.
 */
public class BraintrustAutoConfigCustomizer implements AutoConfigurationCustomizerProvider {

    private static final String SPAN_PROCESSOR_FACTORY =
            "dev.braintrust.agent.internal.BraintrustSpanProcessorFactory";
    private static final String FACTORY_METHOD = "create";

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration.addTracerProviderCustomizer(
                (tracerProviderBuilder, configProperties) -> {
                    SpanProcessor spanProcessor = loadSpanProcessor();
                    if (spanProcessor != null) {
                        tracerProviderBuilder.addSpanProcessor(spanProcessor);
                        System.out.println(
                                "[braintrust] Added Braintrust span processor to tracer provider.");
                    }
                    return tracerProviderBuilder;
                });
    }

    /**
     * Loads the Braintrust span processor from the agent's isolated classloader. The processor
     * implementation and its dependencies (OTLP exporter, etc.) live as .classdata in the agent
     * JAR, only accessible via BraintrustClassLoader.
     */
    private static SpanProcessor loadSpanProcessor() {
        ClassLoader agentCL = BraintrustAgent.agentClassLoader;
        if (agentCL == null) {
            System.err.println(
                    "[braintrust] WARNING: Agent classloader not initialized. "
                            + "Braintrust span processor will not be registered.");
            return null;
        }

        try {
            Class<?> factoryClass = agentCL.loadClass(SPAN_PROCESSOR_FACTORY);
            return (SpanProcessor) factoryClass.getMethod(FACTORY_METHOD).invoke(null);
        } catch (Throwable t) {
            System.err.println(
                    "[braintrust] WARNING: Failed to load Braintrust span processor: "
                            + t.getMessage());
            t.printStackTrace(System.err);
            return null;
        }
    }
}
