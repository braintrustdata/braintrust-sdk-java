package dev.braintrust.bootstrap;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * OTel SDK autoconfiguration customizer that injects the Braintrust span processor into the tracer
 * provider during SDK initialization.
 */
public class OtelAutoConfiguration implements AutoConfigurationCustomizerProvider {

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
        ClassLoader agentCL = BraintrustBridge.getAgentClassLoader();
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
