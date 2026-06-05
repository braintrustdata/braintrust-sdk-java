package dev.braintrust.trace;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.TestHarness;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Conformance tests for the Braintrust distributed-tracing spec
 * (docs/features/distributed-tracing.md).
 *
 * <p>The Java SDK propagates trace context using OpenTelemetry's standard W3C propagators ({@link
 * io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator} and {@link
 * io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator}), composited in {@link
 * BraintrustTracing}. The Braintrust parent travels in the {@code baggage} header under the {@code
 * braintrust.parent} key via {@link BraintrustContext}. These tests assert the wire-level behavior
 * the spec calls out, operating directly on a carrier (a {@code Map<String,String>} of headers) the
 * way the spec's "inject"/"extract" test cases describe, plus an end-to-end HTTP round trip.
 */
public class DistributedTracingTest {

    private static final String TRACEPARENT = "traceparent";
    private static final String BAGGAGE = "baggage";
    private static final String TRACESTATE = "tracestate";
    private static final String PARENT_KEY = BraintrustTracing.PARENT_KEY; // braintrust.parent

    private static final AttributeKey<String> PARENT_ATTR_KEY = AttributeKey.stringKey(PARENT_KEY);

    // version-traceid-parentid-flags, all lowercase hex.
    private static final Pattern TRACEPARENT_RE =
            Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");
    private static final String ZERO_TRACE_ID = "00000000000000000000000000000000";
    private static final String ZERO_SPAN_ID = "0000000000000000";

    private TestHarness harness;
    private TextMapPropagator propagator;
    private Tracer tracer;

    @BeforeEach
    void beforeEach() {
        harness = TestHarness.setup();
        propagator = harness.openTelemetry().getPropagators().getTextMapPropagator();
        tracer = harness.openTelemetry().getTracer("conformance-test");
    }

    // ------------------------------------------------------------------
    // End-to-end HTTP round trip
    // ------------------------------------------------------------------

    @Test
    void testDistributedTracingPropagation() throws Exception {
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

    // ------------------------------------------------------------------
    // Send: header injection
    // ------------------------------------------------------------------

    /**
     * Spec "Send: header injection": traceparent is present and well-formed, its trace id / parent
     * id are non-zero and equal the active span's ids, and baggage carries braintrust.parent.
     */
    @Test
    void injectProducesWellFormedTraceparentAndBaggageParent() {
        String experimentId = "exp-inject-123";
        Context ctx =
                BraintrustContext.setParentInBaggage(Context.root(), "experiment_id", experimentId);
        Span span = tracer.spanBuilder("client").setParent(ctx).startSpan();
        try {
            Context active = ctx.with(span);
            Map<String, String> carrier = inject(active);

            String traceparent = carrier.get(TRACEPARENT);
            assertNotNull(traceparent, "traceparent must be injected");
            assertTrue(
                    TRACEPARENT_RE.matcher(traceparent).matches(),
                    "traceparent must match the W3C format: " + traceparent);

            String[] parts = traceparent.split("-");
            String injectedTraceId = parts[1];
            String injectedParentId = parts[2];
            assertNotEquals(ZERO_TRACE_ID, injectedTraceId, "trace id must be non-zero");
            assertNotEquals(ZERO_SPAN_ID, injectedParentId, "parent id must be non-zero");

            // Injected ids equal the active span's ids.
            assertEquals(
                    span.getSpanContext().getTraceId(),
                    injectedTraceId,
                    "injected trace id must equal the active span's trace id (root_span_id"
                            + " analogue)");
            assertEquals(
                    span.getSpanContext().getSpanId(),
                    injectedParentId,
                    "injected parent id must equal the active span's span id");

            String baggage = carrier.get(BAGGAGE);
            assertNotNull(baggage, "baggage must be injected when a Braintrust parent is known");
            assertTrue(
                    baggage.contains(PARENT_KEY + "=experiment_id:" + experimentId),
                    "baggage must carry braintrust.parent: " + baggage);
        } finally {
            span.end();
        }
    }

    /**
     * Spec "Send: header injection": pre-existing, non-Braintrust baggage entries on the outbound
     * context are preserved (inject does not clobber unrelated baggage).
     */
    @Test
    void injectPreservesUnrelatedBaggage() {
        Context ctx = Context.root().with(Baggage.builder().put("user.id", "u-42").build());
        ctx = BraintrustContext.setParentInBaggage(ctx, "project_id", "proj-1");
        Span span = tracer.spanBuilder("client").setParent(ctx).startSpan();
        try {
            Map<String, String> carrier = inject(ctx.with(span));
            String baggage = carrier.get(BAGGAGE);
            assertNotNull(baggage);
            assertTrue(
                    baggage.contains("user.id=u-42"),
                    "unrelated baggage entry must be preserved: " + baggage);
            assertTrue(
                    baggage.contains(PARENT_KEY + "=project_id:proj-1"),
                    "braintrust.parent must be present alongside unrelated baggage: " + baggage);
        } finally {
            span.end();
        }
    }

    /**
     * Spec "Send: header injection": injected header names are lowercase, and if the carrier
     * already carries a case-variant (e.g. title-cased {@code Baggage}/{@code Traceparent}), the
     * result has a single lowercase key, not two conflicting case-variants.
     *
     * <p>The W3C propagator always writes lowercase keys ({@code traceparent}, {@code baggage}),
     * which satisfies the lowercase requirement. Whether a pre-existing title-cased variant is
     * overwritten "in place" is a property of the carrier's {@link TextMapSetter}: real HTTP
     * carriers (servlet/Netty/OkHttp header maps) are case-insensitive, so the lowercase write
     * replaces the title-cased entry. This test uses such a case-insensitive carrier and asserts
     * the carrier ends up with a single key per header holding the freshly injected value.
     */
    @Test
    void injectOverwritesCaseVariantHeadersInPlace() {
        String experimentId = "exp-case";
        Context ctx =
                BraintrustContext.setParentInBaggage(Context.root(), "experiment_id", experimentId);
        Span span = tracer.spanBuilder("client").setParent(ctx).startSpan();
        try {
            // A case-insensitive carrier, like a real HTTP header map. Pre-seed it with
            // title-cased variants a framework might have added.
            Map<String, String> carrier = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            carrier.put("Traceparent", "stale-value");
            carrier.put("Baggage", "stale=baggage");

            propagator.inject(ctx.with(span), carrier, MapSetter.INSTANCE);

            // Exactly one key per header (case-insensitively), holding the fresh value.
            long traceparentKeys =
                    carrier.keySet().stream().filter(k -> k.equalsIgnoreCase(TRACEPARENT)).count();
            assertEquals(
                    1,
                    traceparentKeys,
                    "there must be exactly one traceparent key, not conflicting case-variants: "
                            + carrier.keySet());
            String traceparent = carrier.get(TRACEPARENT);
            assertTrue(
                    TRACEPARENT_RE.matcher(traceparent).matches(),
                    "traceparent must hold the freshly injected, well-formed value: "
                            + traceparent);

            long baggageKeys =
                    carrier.keySet().stream().filter(k -> k.equalsIgnoreCase(BAGGAGE)).count();
            assertEquals(
                    1,
                    baggageKeys,
                    "there must be exactly one baggage key, not conflicting case-variants: "
                            + carrier.keySet());
            assertTrue(
                    carrier.get(BAGGAGE).contains(PARENT_KEY + "=experiment_id:" + experimentId),
                    "baggage must hold the freshly injected braintrust.parent: "
                            + carrier.get(BAGGAGE));
        } finally {
            span.end();
        }
    }

    // ------------------------------------------------------------------
    // Receive: header extraction
    // ------------------------------------------------------------------

    /**
     * Spec "Receive" row 1: valid traceparent + baggage braintrust.parent → the span shares the
     * inbound trace id, is parented to the inbound span, and is routed to the baggage parent.
     */
    @Test
    void extractWithTraceparentAndBaggageParent() {
        String traceId = "f53d4cd03acedba3ca85a4605ca4bdce";
        String spanId = "baeeec9367deae51";
        Map<String, String> carrier = new HashMap<>();
        carrier.put(TRACEPARENT, "00-" + traceId + "-" + spanId + "-01");
        carrier.put(BAGGAGE, PARENT_KEY + "=project_id:proj-99");

        Context extracted = propagator.extract(Context.root(), carrier, MapGetter.INSTANCE);

        SpanContext parent = Span.fromContext(extracted).getSpanContext();
        assertEquals(traceId, parent.getTraceId(), "must adopt inbound trace id");
        assertEquals(spanId, parent.getSpanId(), "must be parented to inbound span");

        assertEquals(
                "project_id:proj-99",
                BraintrustContext.getParentFromBaggage(extracted).orElse(null),
                "braintrust.parent must be resolvable from baggage");

        // And a span started under this context inherits the trace and parent.
        Span child = tracer.spanBuilder("server").setParent(extracted).startSpan();
        try {
            assertEquals(traceId, child.getSpanContext().getTraceId());
            assertTrue(child.getSpanContext().isValid());
        } finally {
            child.end();
        }
    }

    /**
     * Spec "Receive" row 2: valid traceparent, no braintrust.parent baggage → the span shares the
     * inbound trace id and parent; routing falls back to the active logger/experiment.
     */
    @Test
    void extractWithTraceparentNoBaggageFallsBackToConfig() {
        String traceId = "0123456789abcdef0123456789abcdef";
        String spanId = "0123456789abcdef";
        Map<String, String> carrier = new HashMap<>();
        carrier.put(TRACEPARENT, "00-" + traceId + "-" + spanId + "-01");

        Context extracted = propagator.extract(Context.root(), carrier, MapGetter.INSTANCE);

        SpanContext parent = Span.fromContext(extracted).getSpanContext();
        assertEquals(traceId, parent.getTraceId(), "must adopt inbound trace id");
        assertEquals(spanId, parent.getSpanId(), "must be parented to inbound span");
        // No braintrust.parent present in baggage.
        assertTrue(
                BraintrustContext.getParentFromBaggage(extracted).isEmpty(),
                "no braintrust.parent baggage should be present");

        // Routing falls back to the configured default project (active logger/experiment).
        Span child = tracer.spanBuilder("server").setParent(extracted).startSpan();
        child.end();
        var spans = harness.awaitExportedSpans();
        var serverSpan =
                spans.stream().filter(s -> s.getName().equals("server")).findFirst().orElseThrow();
        assertEquals(
                traceId,
                serverSpan.getTraceId(),
                "child shares inbound trace id even without baggage parent");
        assertEquals(
                "project_name:" + TestHarness.defaultProjectName(),
                serverSpan.getAttributes().get(PARENT_ATTR_KEY),
                "routing falls back to the configured default project");
    }

    /**
     * Spec "Receive" row 3: no propagation headers → span is a fresh root (new trace, no parent).
     */
    @Test
    void extractWithNoHeadersStartsFreshRoot() {
        Map<String, String> carrier = new HashMap<>();
        Context extracted = propagator.extract(Context.root(), carrier, MapGetter.INSTANCE);

        SpanContext parent = Span.fromContext(extracted).getSpanContext();
        assertFalse(parent.isValid(), "no inbound parent should be resolved");

        Span root = tracer.spanBuilder("root").setParent(extracted).startSpan();
        try {
            assertTrue(root.getSpanContext().isValid());
            assertNotEquals(ZERO_TRACE_ID, root.getSpanContext().getTraceId());
        } finally {
            root.end();
        }
    }

    /**
     * Spec "Receive" row 4: malformed traceparent (bad version, wrong length, zero ids) is treated
     * as absent → fresh root span.
     */
    @Test
    void extractWithMalformedTraceparentStartsFreshRoot() {
        String[] malformed = {
            "garbage",
            "ff-f53d4cd03acedba3ca85a4605ca4bdce-baeeec9367deae51-01", // invalid version sentinel
            "0g-f53d4cd03acedba3ca85a4605ca4bdce-baeeec9367deae51-01", // non-hex version
            "00-f53d4cd0-baeeec9367deae51-01", // short trace id
            "00-" + ZERO_TRACE_ID + "-baeeec9367deae51-01", // zero trace id
            "00-f53d4cd03acedba3ca85a4605ca4bdce-" + ZERO_SPAN_ID + "-01", // zero span id
        };
        for (String tp : malformed) {
            Map<String, String> carrier = new HashMap<>();
            carrier.put(TRACEPARENT, tp);
            Context extracted = propagator.extract(Context.root(), carrier, MapGetter.INSTANCE);
            SpanContext parent = Span.fromContext(extracted).getSpanContext();
            assertFalse(
                    parent.isValid(),
                    "malformed traceparent must be treated as absent: '" + tp + "'");

            Span root = tracer.spanBuilder("root").setParent(extracted).startSpan();
            try {
                // Fresh root: not the (invalid) inbound ids.
                assertTrue(root.getSpanContext().isValid());
                assertNotEquals(ZERO_TRACE_ID, root.getSpanContext().getTraceId());
            } finally {
                root.end();
            }
        }
    }

    /**
     * Spec "Receive" row 5: header names in non-lowercase form (e.g. {@code Traceparent}, {@code
     * Baggage}) are extracted correctly (case-insensitive lookup).
     */
    @Test
    void extractIsCaseInsensitiveForHeaderNames() {
        String traceId = "abcdefabcdefabcdefabcdefabcdefab";
        String spanId = "abcdefabcdefabcd";
        Map<String, String> carrier = new LinkedHashMap<>();
        carrier.put("Traceparent", "00-" + traceId + "-" + spanId + "-01");
        carrier.put("Baggage", PARENT_KEY + "=experiment_id:exp-ci");

        Context extracted = propagator.extract(Context.root(), carrier, MapGetter.INSTANCE);

        SpanContext parent = Span.fromContext(extracted).getSpanContext();
        assertEquals(traceId, parent.getTraceId(), "title-cased Traceparent must be honored");
        assertEquals(spanId, parent.getSpanId());
        assertEquals(
                "experiment_id:exp-ci",
                BraintrustContext.getParentFromBaggage(extracted).orElse(null),
                "title-cased Baggage must be honored");
    }

    /**
     * Spec "Receive" row 6: baggage with both braintrust.parent and unrelated keys →
     * braintrust.parent is consumed; unrelated keys are ignored, not errored.
     */
    @Test
    void extractBaggageWithUnrelatedKeys() {
        Map<String, String> carrier = new HashMap<>();
        carrier.put(BAGGAGE, "user.id=u-7," + PARENT_KEY + "=project_id:proj-7,session=abc");

        Context extracted = propagator.extract(Context.root(), carrier, MapGetter.INSTANCE);

        assertEquals(
                "project_id:proj-7",
                BraintrustContext.getParentFromBaggage(extracted).orElse(null),
                "braintrust.parent must be consumed");
        // Unrelated keys remain available (ignored, not errored).
        Baggage baggage = Baggage.fromContext(extracted);
        assertEquals("u-7", baggage.getEntryValue("user.id"));
        assertEquals("abc", baggage.getEntryValue("session"));
    }

    /**
     * Spec "Receive" row 7 + "Round trip": valid traceparent + tracestate → the inbound tracestate
     * is captured and forwarded unchanged on any later inject within the trace.
     */
    @Test
    void extractCapturesTracestateAndForwardsItOnInject() {
        String traceId = "11111111111111111111111111111111";
        String spanId = "2222222222222222";
        // W3C tracestate keys must be lowercase.
        String tracestate = "vendora=t61rcWkgMzE,vendorb=00f067aa0ba902b7";

        Map<String, String> inbound = new HashMap<>();
        inbound.put(TRACEPARENT, "00-" + traceId + "-" + spanId + "-01");
        inbound.put(TRACESTATE, tracestate);

        Context extracted = propagator.extract(Context.root(), inbound, MapGetter.INSTANCE);

        // Start a child span within the extracted trace, then inject onward.
        Span child = tracer.spanBuilder("server").setParent(extracted).startSpan();
        try {
            Context active = extracted.with(child);
            Map<String, String> outbound = inject(active);

            assertEquals(
                    traceId,
                    outbound.get(TRACEPARENT).split("-")[1],
                    "onward trace id matches inbound");
            assertEquals(
                    tracestate,
                    outbound.get(TRACESTATE),
                    "inbound tracestate must be forwarded unchanged on later inject");
        } finally {
            child.end();
        }
    }

    // ------------------------------------------------------------------
    // Round trip
    // ------------------------------------------------------------------

    /**
     * Spec "Round trip": inject from a parent span, then extract on a fresh context using the
     * produced headers. The extracted trace id and parent span id match the originating span, and
     * the resolved Braintrust parent matches.
     */
    @Test
    void injectThenExtractRoundTrips() {
        String experimentId = "exp-roundtrip";
        Context ctx =
                BraintrustContext.setParentInBaggage(Context.root(), "experiment_id", experimentId);
        Span span = tracer.spanBuilder("origin").setParent(ctx).startSpan();
        try {
            Map<String, String> carrier = inject(ctx.with(span));

            Context extracted = propagator.extract(Context.root(), carrier, MapGetter.INSTANCE);
            SpanContext resolved = Span.fromContext(extracted).getSpanContext();

            assertEquals(
                    span.getSpanContext().getTraceId(),
                    resolved.getTraceId(),
                    "round-trip trace id must match");
            assertEquals(
                    span.getSpanContext().getSpanId(),
                    resolved.getSpanId(),
                    "round-trip parent span id must match");
            assertEquals(
                    "experiment_id:" + experimentId,
                    BraintrustContext.getParentFromBaggage(extracted).orElse(null),
                    "round-trip Braintrust parent must match");
        } finally {
            span.end();
        }
    }

    /**
     * Spec "Round trip" tracestate clause: when no inbound tracestate was present, none is emitted
     * on inject.
     */
    @Test
    void noInboundTracestateEmitsNoneOnInject() {
        Context ctx = BraintrustContext.setParentInBaggage(Context.root(), "project_id", "proj-x");
        Span span = tracer.spanBuilder("origin").setParent(ctx).startSpan();
        try {
            Map<String, String> carrier = inject(ctx.with(span));
            String tracestate = carrier.get(TRACESTATE);
            assertTrue(
                    tracestate == null || tracestate.isEmpty(),
                    "no tracestate should be emitted when none was inbound: " + tracestate);
        } finally {
            span.end();
        }
    }

    // ------------------------------------------------------------------
    // Negative / robustness
    // ------------------------------------------------------------------

    /**
     * Spec "Negative / robustness": injecting then exporting to Braintrust MUST NOT fail or drop
     * the span if the Braintrust parent is unknown — propagation is best-effort and MUST NOT break
     * span emission.
     */
    @Test
    void injectWithUnknownParentDoesNotBreakSpanEmission() {
        Span span = tracer.spanBuilder("emit").setParent(Context.root()).startSpan();
        Map<String, String> carrier = inject(Context.root().with(span));
        assertNotNull(carrier.get(TRACEPARENT));
        span.end();

        var spans = harness.awaitExportedSpans();
        assertTrue(
                spans.stream().anyMatch(s -> s.getName().equals("emit")),
                "span must still be exported even though propagation had no Braintrust parent");
    }

    /**
     * Spec "Negative / robustness": an oversized or syntactically invalid baggage header MUST NOT
     * throw; the SDK falls back to trace identity from traceparent (or a fresh root).
     */
    @Test
    void invalidBaggageDoesNotThrowAndFallsBackToTraceparent() {
        String traceId = "33333333333333333333333333333333";
        String spanId = "4444444444444444";

        // Build an absurdly large, partially malformed baggage value.
        StringBuilder huge = new StringBuilder("=not-a-pair,,,;;;" + PARENT_KEY + "=");
        for (int i = 0; i < 20000; i++) {
            huge.append("x");
        }
        Map<String, String> carrier = new HashMap<>();
        carrier.put(TRACEPARENT, "00-" + traceId + "-" + spanId + "-01");
        carrier.put(BAGGAGE, huge.toString());

        Context extracted =
                assertDoesNotThrow(
                        () -> propagator.extract(Context.root(), carrier, MapGetter.INSTANCE),
                        "invalid/oversized baggage must not throw on extract");

        // Trace identity still resolves from traceparent.
        SpanContext parent = Span.fromContext(extracted).getSpanContext();
        assertEquals(traceId, parent.getTraceId(), "trace identity must survive bad baggage");
        assertEquals(spanId, parent.getSpanId());

        // Reading the (garbage) braintrust.parent must not throw either.
        assertDoesNotThrow(() -> BraintrustContext.getParentFromBaggage(extracted));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Map<String, String> inject(Context context) {
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context, carrier, MapSetter.INSTANCE);
        return carrier;
    }

    /** TextMapSetter writing into a plain Map. */
    private enum MapSetter implements TextMapSetter<Map<String, String>> {
        INSTANCE;

        @Override
        public void set(@Nullable Map<String, String> carrier, String key, String value) {
            if (carrier != null) {
                carrier.put(key, value);
            }
        }
    }

    /** Case-insensitive TextMapGetter reading from a Map (mirrors HTTP header semantics). */
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
            String value = carrier.get(key);
            if (value != null) {
                return value;
            }
            for (Map.Entry<String, String> entry : carrier.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key)) {
                    return entry.getValue();
                }
            }
            return null;
        }
    }
}
