package dev.braintrust.trace;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.TestHarness;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;

public class DistributedTracingTest {

    private static final AttributeKey<String> PARENT_ATTR_KEY =
            AttributeKey.stringKey(BraintrustTracing.PARENT_KEY);

    @Test
    void testDistributedTracingPropagation() throws Exception {
        TestHarness harness = TestHarness.setup();
        TextMapPropagator propagator =
                harness.openTelemetry().getPropagators().getTextMapPropagator();

        Tracer clientTracer = harness.openTelemetry().getTracer("test-client");
        Tracer serverTracer = harness.openTelemetry().getTracer("test-server");

        com.sun.net.httpserver.HttpServer httpServer =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("localhost", 0), 0);
        int port = httpServer.getAddress().getPort();

        httpServer.createContext(
                "/test",
                exchange -> {
                    Map<String, String> headers = new HashMap<>();
                    exchange.getRequestHeaders()
                            .forEach(
                                    (key, values) -> {
                                        if (!values.isEmpty()) {
                                            headers.put(key, values.get(0));
                                        }
                                    });

                    Context serverContext =
                            propagator.extract(Context.root(), headers, MapGetter.INSTANCE);
                    Span serverSpan =
                            serverTracer
                                    .spanBuilder("server-operation")
                                    .setParent(serverContext)
                                    .startSpan();

                    try (var scope = serverContext.with(serverSpan).makeCurrent()) {
                        String response = "OK";
                        exchange.sendResponseHeaders(200, response.length());
                        exchange.getResponseBody().write(response.getBytes());
                        exchange.getResponseBody().close();
                    } finally {
                        serverSpan.end();
                    }
                });

        httpServer.start();

        try {
            String experimentId = "abc123-http-test";
            Context experimentContext =
                    BraintrustContext.setParentInBaggage(
                            Context.root(), "experiment_id", experimentId);

            Span clientSpan =
                    clientTracer
                            .spanBuilder("client-operation")
                            .setParent(experimentContext)
                            .startSpan();
            Context clientContext = experimentContext.with(clientSpan);

            try (var scope = clientContext.makeCurrent()) {
                java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest.Builder requestBuilder =
                        java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create("http://localhost:" + port + "/test"))
                                .GET();
                propagator.inject(
                        clientContext,
                        requestBuilder,
                        (builder, key, value) -> builder.header(key, value));

                java.net.http.HttpRequest request = requestBuilder.build();
                java.net.http.HttpResponse<String> response =
                        httpClient.send(
                                request, java.net.http.HttpResponse.BodyHandlers.ofString());

                assertEquals(200, response.statusCode(), "HTTP request should succeed");
            } finally {
                clientSpan.end();
            }

            var allSpans = harness.awaitExportedSpans();
            assertEquals(2, allSpans.size(), "Expected two spans (client + server)");

            SpanData clientSpanData =
                    allSpans.stream()
                            .filter(s -> s.getName().equals("client-operation"))
                            .findFirst()
                            .orElseThrow();
            SpanData serverSpanData =
                    allSpans.stream()
                            .filter(s -> s.getName().equals("server-operation"))
                            .findFirst()
                            .orElseThrow();

            String clientParentAttr = clientSpanData.getAttributes().get(PARENT_ATTR_KEY);
            assertNotNull(clientParentAttr, "Client span should have braintrust.parent attribute");
            assertEquals(
                    "experiment_id:" + experimentId,
                    clientParentAttr,
                    "Client parent attribute should match experiment");

            String serverParentAttr = serverSpanData.getAttributes().get(PARENT_ATTR_KEY);
            assertNotNull(
                    serverParentAttr,
                    "Server span should have braintrust.parent attribute propagated via HTTP");
            assertEquals(
                    "experiment_id:" + experimentId,
                    serverParentAttr,
                    "Server parent attribute should match client experiment");

            assertEquals(
                    clientSpanData.getTraceId(),
                    serverSpanData.getTraceId(),
                    "Trace IDs should match across HTTP boundary");

            assertEquals(
                    clientSpanData.getSpanId(),
                    serverSpanData.getParentSpanId(),
                    "Server span should be a child of client span");

        } finally {
            httpServer.stop(0);
        }
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
        var parsed1 = BraintrustContext.parseParent("experiment_id:abc123");
        assertEquals("experiment_id", parsed1.type());
        assertEquals("abc123", parsed1.id());

        var parsed2 = BraintrustContext.parseParent("project_name:my-project");
        assertEquals("project_name", parsed2.type());
        assertEquals("my-project", parsed2.id());

        assertThrows(
                Exception.class,
                () -> BraintrustContext.parseParent("invalid-no-colon"),
                "Should throw on invalid format");
        assertThrows(
                Exception.class,
                () -> BraintrustContext.parseParent("invalid:too:many:colons"),
                "Should throw on invalid format");
        assertThrows(
                Exception.class,
                () -> BraintrustContext.parseParent(""),
                "Should throw on empty string");
    }

    /** TextMapGetter for extracting headers from a Map (case-insensitive for HTTP headers). */
    private enum MapGetter implements TextMapGetter<Map<String, String>> {
        INSTANCE;

        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        @Nullable
        public String get(@Nullable Map<String, String> carrier, String key) {
            if (carrier == null) {
                return null;
            }
            // Try exact match first
            String value = carrier.get(key);
            if (value != null) {
                return value;
            }
            // Fall back to case-insensitive search for HTTP headers
            for (Map.Entry<String, String> entry : carrier.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key)) {
                    return entry.getValue();
                }
            }
            return null;
        }
    }
}
