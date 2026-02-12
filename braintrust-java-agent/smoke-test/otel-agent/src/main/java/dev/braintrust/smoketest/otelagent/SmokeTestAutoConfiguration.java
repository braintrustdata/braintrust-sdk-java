package dev.braintrust.smoketest.otelagent;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

/**
 * Autoconfigure SPI provider that hooks the smoke test's collecting exporter
 * into the OTel SDK tracer provider for assertions.
 *
 * <p>Discovered by Braintrust's {@code OtelAutoConfiguration} which scans the
 * system classloader for additional {@code AutoConfigurationCustomizerProvider} SPIs.
 */
public class SmokeTestAutoConfiguration implements AutoConfigurationCustomizerProvider {

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        System.out.println("[smoke-test] SmokeTestAutoConfiguration.customize() called");

        autoConfiguration.addTracerProviderCustomizer((builder, config) -> {
            var collectingExporter = new CollectingSpanExporter();
            OtelAgentSmokeTest.collectingExporter = collectingExporter;
            builder.addSpanProcessor(SimpleSpanProcessor.create(collectingExporter));
            System.out.println("[smoke-test] Added collecting span exporter");
            return builder;
        });
    }
}
