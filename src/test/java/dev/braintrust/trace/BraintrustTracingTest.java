package dev.braintrust.trace;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.TestHarness;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
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
