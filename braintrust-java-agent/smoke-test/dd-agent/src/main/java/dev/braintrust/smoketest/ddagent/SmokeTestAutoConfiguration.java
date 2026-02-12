package dev.braintrust.smoketest.ddagent;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import java.time.Duration;

/**
 * Autoconfigure SPI provider that hooks collecting exporters for traces, logs,
 * and metrics into the OTel SDK for test assertions.
 *
 * <p>The DD bridge exporter is installed by the Braintrust agent itself
 * (in {@code BraintrustAgent.hookUpDatadogOtelShim}), so this SPI only adds
 * collecting exporters for test verification.
 *
 * <p>Discovered by Braintrust's {@code OtelAutoConfiguration} which scans the
 * system classloader for additional {@code AutoConfigurationCustomizerProvider} SPIs.
 */
public class SmokeTestAutoConfiguration implements AutoConfigurationCustomizerProvider {

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        System.out.println("[smoke-test] SmokeTestAutoConfiguration.customize() called");

        // Traces
        autoConfiguration.addTracerProviderCustomizer((builder, config) -> {
            var collectingExporter = new CollectingSpanExporter();
            DdAgentSmokeTest.collectingSpanExporter = collectingExporter;
            builder.addSpanProcessor(SimpleSpanProcessor.create(collectingExporter));
            System.out.println("[smoke-test] Added collecting span exporter");
            return builder;
        });

        // Logs
        autoConfiguration.addLoggerProviderCustomizer((builder, config) -> {
            var collectingExporter = new CollectingLogRecordExporter();
            DdAgentSmokeTest.collectingLogExporter = collectingExporter;
            builder.addLogRecordProcessor(SimpleLogRecordProcessor.create(collectingExporter));
            System.out.println("[smoke-test] Added collecting log record exporter");
            return builder;
        });

        // Metrics — use a short interval so we don't have to wait long
        autoConfiguration.addMeterProviderCustomizer((builder, config) -> {
            var collectingExporter = new CollectingMetricExporter();
            DdAgentSmokeTest.collectingMetricExporter = collectingExporter;
            builder.registerMetricReader(
                    PeriodicMetricReader.builder(collectingExporter)
                            .setInterval(Duration.ofMillis(100))
                            .build());
            System.out.println("[smoke-test] Added collecting metric exporter");
            return builder;
        });
    }
}
