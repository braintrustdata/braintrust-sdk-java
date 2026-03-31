package dev.braintrust.trace;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.TestHarness;
import dev.braintrust.UnitTestSpanExporter;
import dev.braintrust.config.BraintrustConfig;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BraintrustTracingTest {
    private TestHarness testHarness;

    @BeforeEach
    void beforeEach() {
        testHarness = TestHarness.setup();
    }

    @Test
    void instrumentationInfoIsPresent() {
        assertNotNull(BraintrustTracing.INSTRUMENTATION_NAME);
        assertFalse(BraintrustTracing.INSTRUMENTATION_NAME.isEmpty());
        var gradleSdkVersion = System.getenv("GRADLE_SDK_VERSION");
        assertNotNull(gradleSdkVersion);
        assertFalse(gradleSdkVersion.isEmpty());
        assertEquals(gradleSdkVersion, BraintrustTracing.INSTRUMENTATION_VERSION);
    }

    @Test
    void globalBTTracing() {
        doSimpleOtelTrace(BraintrustTracing.getTracer());
        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        assertEquals(true, spans.get(0).getAttributes().get(AttributeKey.booleanKey("unit-test")));
    }

    @Test
    void customBTTracing() {
        // TestHarness already sets up custom BT tracing with openTelemetryEnable
        // We just need to verify it works with a custom tracer
        doSimpleOtelTrace(testHarness.openTelemetry().getTracer("some-instrumentation"));
        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        assertEquals(true, spans.get(0).getAttributes().get(AttributeKey.booleanKey("unit-test")));
    }

    @Test
    void spanProcessorAddsParentFromConfig() {
        // Verify that BraintrustSpanProcessor automatically adds the braintrust.parent
        // attribute from the config when no parent is explicitly set on the span
        doSimpleOtelTrace(testHarness.openTelemetry().getTracer("my tracer"));
        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var span = spans.get(0);

        // The span should have the braintrust.parent attribute set from the config
        var parentAttribute = span.getAttributes().get(AttributeKey.stringKey("braintrust.parent"));
        assertNotNull(
                parentAttribute,
                "braintrust.parent attribute should be set by BraintrustSpanProcessor");
        assertEquals(
                "project_name:" + TestHarness.defaultProjectName(),
                parentAttribute,
                "braintrust.parent should be set from the config's default project name");
    }

    @Test
    void filterAISpansDropsNonAISpans() {
        // Build a tracer where the BraintrustSpanProcessor wraps a test exporter,
        // so the filter in onEnd() gates what the exporter receives.
        var config =
                BraintrustConfig.builder()
                        .apiKey("test-key")
                        .apiUrl("http://localhost:1234")
                        .filterAISpans(true)
                        .build();

        var spanExporter = new UnitTestSpanExporter();
        var processor =
                new BraintrustSpanProcessor(config, SimpleSpanProcessor.create(spanExporter));
        var tracerProvider = SdkTracerProvider.builder().addSpanProcessor(processor).build();
        var tracer = tracerProvider.get("test");

        // Non-AI span — should be filtered out
        var nonAiSpan = tracer.spanBuilder("http-call").startSpan();
        nonAiSpan.setAttribute("http.method", "GET");
        nonAiSpan.end();

        // Span with no attributes — should be filtered out
        var emptySpan = tracer.spanBuilder("empty-span").startSpan();
        emptySpan.end();

        // Substring non-match — should be filtered out
        var substringSpan = tracer.spanBuilder("not-ai").startSpan();
        substringSpan.setAttribute("zbraintrust.foo", "bar");
        substringSpan.end();

        // AI span with attribute set AFTER startSpan — should pass the filter
        var aiSpan = tracer.spanBuilder("llm-call").startSpan();
        aiSpan.setAttribute("gen_ai.model", "gpt-4");
        aiSpan.end();

        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
        var spans = spanExporter.getFinishedSpanItems();

        assertEquals(1, spans.size(), "Only the AI span should be exported");
        assertEquals("llm-call", spans.get(0).getName());
    }

    private void doSimpleOtelTrace(Tracer tracer) {
        // use tracer to create a simple trace with a root span and a child span
        var span = tracer.spanBuilder("unit-test-root").startSpan();
        try (var ignored = span.makeCurrent()) {
            span.setAttribute("unit-test", true);
        } finally {
            span.end();
        }
    }
}
