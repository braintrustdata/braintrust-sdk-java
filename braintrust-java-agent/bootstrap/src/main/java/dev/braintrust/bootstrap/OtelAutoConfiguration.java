package dev.braintrust.bootstrap;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

import java.util.List;
import java.util.ServiceLoader;

/**
 * OTel SDK autoconfiguration customizer that injects the Braintrust span processor into the tracer
 * provider during SDK initialization.
 */
public class OtelAutoConfiguration implements AutoConfigurationCustomizerProvider {
    private static final String AGENT_CLASS = "dev.braintrust.agent.BraintrustAgent";
    private static final String CONFIGURE_METHOD = "configureOpenTelemetry";

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        BraintrustClassLoader agentCL = BraintrustBridge.getAgentClassLoader();
        if (agentCL == null) {
            System.out.println("[braintrust] WARNING: agent classloader not set, skipping OTel customization.");
            return;
        }

        try {
            Class<?> installerClass = agentCL.loadClass(AGENT_CLASS);
            Object installer = installerClass.getDeclaredConstructor().newInstance();
            installerClass
                    .getMethod(CONFIGURE_METHOD, AutoConfigurationCustomizer.class)
                    .invoke(installer, autoConfiguration);
            System.out.println("[braintrust] Added Braintrust span processor to tracer provider.");
        } catch (Exception e) {
            System.err.println("[braintrust] ERROR: Failed to configure OTel: " + e.getMessage());
            e.printStackTrace(System.err);
        }
        // FIXME vv make sure spi auto loading is correct
        /*
        List<ClassLoader> loaders = List.of(ClassLoader.getSystemClassLoader());
        for (var spiLoader : loaders) {
            for (var provider : ServiceLoader.load(AutoConfigurationCustomizerProvider.class, spiLoader)) {
                if (provider.getClass() != this.getClass()) { // shouldn't happen, but just in case
                    provider.customize(autoConfiguration);
                }
            }
        }
         */
    }
}
