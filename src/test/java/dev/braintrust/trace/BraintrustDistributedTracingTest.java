package dev.braintrust.trace;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.TestHarness;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Tests for distributed tracing support in Braintrust.
 *
 * <p>These tests verify that braintrust.parent propagates correctly across process boundaries using
 * OpenTelemetry's W3C Trace Context and Baggage propagation.
 */
public class BraintrustDistributedTracingTest {

    private static final AttributeKey<String> PARENT_ATTR_KEY =
            AttributeKey.stringKey(BraintrustTracing.PARENT_KEY);

    /**
     * Tests distributed tracing propagation across a simulated process boundary.
     *
     * <p>This test simulates distributed tracing between a client and server process. It verifies
     * that the braintrust.parent attribute propagates from client to server using OpenTelemetry's
     * W3C Trace Context and Baggage propagation.
     *
     * <p>Flow:
     *
     * <ol>
     *   <li>Client: Create span with experiment parent
     *   <li>Client: Extract W3C headers (traceparent + baggage)
     *   <li>Server: Inject headers into new context
     *   <li>Server: Create span from propagated context
     *   <li>Verify: Both spans have braintrust.parent attribute
     * </ol>
     */
    @Test
    void testDistributedTracingPropagation() {
        // Configure propagator for distributed tracing (W3C Trace Context + Baggage)
        // This simulates what happens in real distributed systems like HTTP/gRPC
        TextMapPropagator propagator =
                TextMapPropagator.composite(
                        W3CTraceContextPropagator.getInstance(),
                        W3CBaggagePropagator.getInstance());

        // === CLIENT SIDE ===
        // Create client tracer with Braintrust enabled
        InMemorySpanExporter clientExporter = InMemorySpanExporter.create();
        SdkTracerProviderBuilder clientTracerBuilder = SdkTracerProvider.builder();

        // Enable Braintrust tracing on the client side
        TestHarness clientHarness = TestHarness.setup();
        clientHarness
                .braintrust()
                .openTelemetryEnable(
                        clientTracerBuilder,
                        SdkLoggerProvider.builder(),
                        SdkMeterProvider.builder());

        // Add in-memory exporter to capture spans
        clientTracerBuilder.addSpanProcessor(SimpleSpanProcessor.create(clientExporter));
        SdkTracerProvider clientTracerProvider = clientTracerBuilder.build();

        Tracer clientTracer = clientTracerProvider.get("test-client");

        // Set a parent (experiment) in the context BEFORE starting the span
        String experimentId = "abc123-distributed-test";

        // Set parent directly in baggage (simpler for testing)
        Context experimentContext =
                BraintrustContext.setParentInBaggage(Context.root(), "experiment_id", experimentId);

        // Start the client span with the experiment context
        Span clientSpan =
                clientTracer
                        .spanBuilder("client-operation")
                        .setParent(experimentContext)
                        .startSpan();
        Context clientContext = experimentContext.with(clientSpan);

        try (var scope = clientContext.makeCurrent()) {
            // Span is active during the scope
        } finally {
            clientSpan.end();
        }

        // Force export
        clientTracerProvider.forceFlush();

        // Verify client span has the parent attribute
        var clientSpans = clientExporter.getFinishedSpanItems();
        assertEquals(1, clientSpans.size(), "Expected one client span");
        SpanData clientSpanData = clientSpans.get(0);
        String clientParentAttr = clientSpanData.getAttributes().get(PARENT_ATTR_KEY);
        assertNotNull(
                clientParentAttr,
                "Client span should have braintrust.parent attribute from context");
        assertEquals(
                "experiment_id:" + experimentId,
                clientParentAttr,
                "Client parent attribute should match experiment");

        // === SIMULATE NETWORK ===
        // Extract W3C headers (traceparent + baggage)
        // In real distributed systems, these headers are sent over HTTP/gRPC
        Map<String, String> headers = new HashMap<>();
        propagator.inject(clientContext, headers, MapSetter.INSTANCE);

        assertFalse(headers.isEmpty(), "Headers should be extracted");
        assertTrue(
                headers.containsKey("traceparent"),
                "Should have traceparent header for trace context");
        assertTrue(headers.containsKey("baggage"), "Should have baggage header for parent");

        // === SERVER SIDE ===
        // Create server tracer provider (separate process)
        InMemorySpanExporter serverExporter = InMemorySpanExporter.create();
        SdkTracerProviderBuilder serverTracerBuilder = SdkTracerProvider.builder();

        // Enable Braintrust tracing on the server side (simulates separate process)
        TestHarness serverHarness = TestHarness.setup();
        serverHarness
                .braintrust()
                .openTelemetryEnable(
                        serverTracerBuilder,
                        SdkLoggerProvider.builder(),
                        SdkMeterProvider.builder());

        // Add in-memory exporter to capture spans
        serverTracerBuilder.addSpanProcessor(SimpleSpanProcessor.create(serverExporter));
        SdkTracerProvider serverTracerProvider = serverTracerBuilder.build();

        Tracer serverTracer = serverTracerProvider.get("test-server");

        // DESERIALIZE: Inject headers into new context
        Context serverContext = propagator.extract(Context.root(), headers, MapGetter.INSTANCE);

        // Start a span on the server side with the propagated context
        Span serverSpan =
                serverTracer.spanBuilder("server-operation").setParent(serverContext).startSpan();
        try (var scope = serverContext.with(serverSpan).makeCurrent()) {
            // Span is active during the scope
        } finally {
            serverSpan.end();
        }

        // Force export
        serverTracerProvider.forceFlush();

        // === VERIFY ===
        // Server span should have the braintrust.parent attribute
        var serverSpans = serverExporter.getFinishedSpanItems();
        assertEquals(1, serverSpans.size(), "Expected one server span");
        SpanData serverSpanData = serverSpans.get(0);

        // The critical assertion: parent should have propagated across the boundary
        String serverParentAttr = serverSpanData.getAttributes().get(PARENT_ATTR_KEY);
        assertNotNull(
                serverParentAttr,
                "Server span should have braintrust.parent attribute propagated via baggage");
        assertEquals(
                "experiment_id:" + experimentId,
                serverParentAttr,
                "Server parent attribute should match client experiment");

        // Additional verification: trace IDs should match (standard OTel behavior)
        assertEquals(
                clientSpanData.getTraceId(),
                serverSpanData.getTraceId(),
                "Trace IDs should match across distributed boundary");
    }

    /**
     * Tests that parent can be retrieved from baggage when context doesn't have it.
     *
     * <p>This verifies the fallback mechanism in BraintrustSpanProcessor.
     */
    @Test
    void testGetParentFromBaggage() {
        String experimentId = "test-experiment-123";
        String parentValue = "experiment_id:" + experimentId;

        // Create a context with parent in baggage
        Context ctx =
                BraintrustContext.setParentInBaggage(Context.root(), "experiment_id", experimentId);

        // Verify we can retrieve it
        var retrieved = BraintrustContext.getParentFromBaggage(ctx);
        assertTrue(retrieved.isPresent(), "Should retrieve parent from baggage");
        assertEquals(parentValue, retrieved.get(), "Parent value should match");
    }

    /** Tests parsing of parent strings in the format "type:id". */
    @Test
    void testParseParent() {
        // Valid formats
        var parsed1 = BraintrustContext.parseParent("experiment_id:abc123");
        assertEquals("experiment_id", parsed1.type());
        assertEquals("abc123", parsed1.id());

        var parsed2 = BraintrustContext.parseParent("project_name:my-project");
        assertEquals("project_name", parsed2.type());
        assertEquals("my-project", parsed2.id());

        // ID with colons (should split on first colon only)
        var parsed3 = BraintrustContext.parseParent("type:id:with:colons");
        assertEquals("type", parsed3.type());
        assertEquals("id:with:colons", parsed3.id());

        // Invalid format
        assertThrows(
                IllegalArgumentException.class,
                () -> BraintrustContext.parseParent("invalid-no-colon"),
                "Should throw on invalid format");

        assertThrows(
                IllegalArgumentException.class,
                () -> BraintrustContext.parseParent(""),
                "Should throw on empty string");
    }

    // Helper classes for propagation

    /** TextMapSetter for injecting headers into a Map. */
    private enum MapSetter implements TextMapSetter<Map<String, String>> {
        INSTANCE;

        @Override
        public void set(@Nullable Map<String, String> carrier, String key, String value) {
            if (carrier != null) {
                carrier.put(key, value);
            }
        }
    }

    /** TextMapGetter for extracting headers from a Map. */
    private enum MapGetter implements TextMapGetter<Map<String, String>> {
        INSTANCE;

        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        @Nullable
        public String get(@Nullable Map<String, String> carrier, String key) {
            return carrier != null ? carrier.get(key) : null;
        }
    }
}
