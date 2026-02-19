package dev.braintrust.agent;

import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.util.concurrent.TimeUnit;

/**
 * Factory for creating the Braintrust span processor. Called reflectively from {@link
 * dev.braintrust.bootstrap.BraintrustAutoConfigCustomizer} during OTel SDK autoconfiguration.
 *
 * <p>This class lives in the agent-internal module (loaded by BraintrustClassLoader) so it can
 * depend on heavy libraries (OTLP exporter, OkHttp, etc.) without polluting the bootstrap
 * classpath.
 *
 * <p>Creates a processing pipeline:
 * <pre>
 *   AgentSpanProcessor (enriches spans with braintrust.parent attribute)
 *     └─ BatchSpanProcessor
 *          └─ AgentSpanExporter (routes spans to Braintrust with x-bt-parent header)
 *               └─ OtlpHttpSpanExporter (per parent, with Authorization header)
 * </pre>
 */
public class BraintrustSpanProcessorFactory {

    private static final long EXPORT_INTERVAL_MS = 5_000;
    private static final int MAX_QUEUE_SIZE = 2048;
    private static final int MAX_EXPORT_BATCH_SIZE = 512;

    /**
     * Creates the Braintrust span processor.
     *
     * <p>Called reflectively by BraintrustAutoConfigCustomizer during OTel autoconfiguration.
     *
     * @return a SpanProcessor that exports spans to Braintrust
     */
    public static SpanProcessor create() {
        log("BraintrustSpanProcessorFactory.create() called");
        log("Factory classloader: "
                + BraintrustSpanProcessorFactory.class.getClassLoader().getClass().getName());

        AgentConfig config;
        try {
            config = AgentConfig.fromEnvironment();
        } catch (IllegalStateException e) {
            // BRAINTRUST_API_KEY not set — fall back to no-op processor.
            // The agent still installs (for ByteBuddy instrumentation) but spans won't export.
            log("WARNING: " + e.getMessage());
            log("Braintrust span export disabled. Set BRAINTRUST_API_KEY to enable.");
            return SpanProcessor.composite();
        }

        var exporter = new AgentSpanExporter(config);

        var batchProcessor =
                BatchSpanProcessor.builder(exporter)
                        .setScheduleDelay(EXPORT_INTERVAL_MS, TimeUnit.MILLISECONDS)
                        .setMaxQueueSize(MAX_QUEUE_SIZE)
                        .setMaxExportBatchSize(MAX_EXPORT_BATCH_SIZE)
                        .build();

        var processor = new AgentSpanProcessor(config, batchProcessor);

        log("Braintrust span processor created (endpoint=" + config.tracesEndpoint() + ")");

        return processor;
    }

    private static void log(String msg) {
        System.out.println("[braintrust] " + msg);
    }
}
