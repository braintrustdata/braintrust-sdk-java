package dev.braintrust.smoketest.otelagent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A minimal mock OTLP/HTTP collector that captures {@code POST /v1/traces} requests.
 *
 * <p>Used by both the OTel agent mock (at {@code otel.exporter.otlp.endpoint}) and the Braintrust
 * backend mock (at {@code BRAINTRUST_API_URL/otel/v1/traces}).
 */
public class MockOtlpCollector {

    /** A decoded OTLP span with its attributes. */
    public static class OtlpSpan {
        public final String name;
        public final String traceId;
        public final String spanId;
        public final String parentSpanId;
        public final Span.SpanKind kind;
        public final long startTimeUnixNano;
        public final long endTimeUnixNano;
        public final Map<String, Object> attributes;

        OtlpSpan(Span protoSpan) {
            this.name = protoSpan.getName();
            this.traceId = hexString(protoSpan.getTraceId().toByteArray());
            this.spanId = hexString(protoSpan.getSpanId().toByteArray());
            this.parentSpanId = hexString(protoSpan.getParentSpanId().toByteArray());
            this.kind = protoSpan.getKind();
            this.startTimeUnixNano = protoSpan.getStartTimeUnixNano();
            this.endTimeUnixNano = protoSpan.getEndTimeUnixNano();
            this.attributes = extractAttributes(protoSpan.getAttributesList());
        }

        public String stringAttr(String key) {
            Object v = attributes.get(key);
            return v instanceof String s ? s : null;
        }

        @Override
        public String toString() {
            return "OtlpSpan{name='%s', traceId=%s, spanId=%s, parentSpanId=%s, kind=%s}"
                    .formatted(name, traceId, spanId, parentSpanId, kind);
        }

        private static Map<String, Object> extractAttributes(List<KeyValue> kvList) {
            Map<String, Object> map = new HashMap<>(kvList.size());
            for (KeyValue kv : kvList) {
                AnyValue v = kv.getValue();
                Object value =
                        switch (v.getValueCase()) {
                            case STRING_VALUE -> v.getStringValue();
                            case BOOL_VALUE -> v.getBoolValue();
                            case INT_VALUE -> v.getIntValue();
                            case DOUBLE_VALUE -> v.getDoubleValue();
                            default -> v.toString();
                        };
                map.put(kv.getKey(), value);
            }
            return map;
        }

        private static String hexString(byte[] bytes) {
            if (bytes == null || bytes.length == 0) return "";
            var sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        }
    }

    private final String label;
    private final String tracesPath;
    private final CopyOnWriteArrayList<List<OtlpSpan>> receivedBatches =
            new CopyOnWriteArrayList<>();
    private final CountDownLatch traceLatch;
    private final HttpServer server;
    private final int port;

    /**
     * @param label short name used in log output, e.g. "otel-collector" or "bt-backend"
     * @param tracesPath the HTTP path to accept trace exports on, e.g. "/v1/traces" or
     *     "/otel/v1/traces"
     * @param expectedBatches number of export batches to wait for in {@link #awaitSpans}
     * @param port TCP port to bind on
     */
    public MockOtlpCollector(String label, String tracesPath, int expectedBatches, int port)
            throws IOException {
        this.label = label;
        this.tracesPath = tracesPath;
        this.traceLatch = new CountDownLatch(expectedBatches);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.port = server.getAddress().getPort();

        server.createContext(tracesPath, this::handleTraces);
        server.createContext("/", this::handleDefault);
    }

    public void start() {
        server.start();
        System.out.println("[" + label + "] listening on port " + port + " at " + tracesPath);
    }

    public void stop() {
        server.stop(0);
    }

    /** Block until the expected number of export batches have arrived, or timeout. */
    public boolean awaitSpans(long timeout, TimeUnit unit) throws InterruptedException {
        return traceLatch.await(timeout, unit);
    }

    /** Flattened list of all received spans across all batches. */
    public List<OtlpSpan> getAllSpans() {
        List<OtlpSpan> all = new ArrayList<>();
        for (var batch : receivedBatches) all.addAll(batch);
        return all;
    }

    /** Find a span by name, or null. */
    public OtlpSpan findSpanByName(String name) {
        for (var batch : receivedBatches)
            for (var span : batch) if (name.equals(span.name)) return span;
        return null;
    }

    public int batchCount() {
        return receivedBatches.size();
    }

    private void handleTraces(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        byte[] body = exchange.getRequestBody().readAllBytes();
        List<OtlpSpan> spans = new ArrayList<>();
        try {
            ExportTraceServiceRequest req = ExportTraceServiceRequest.parseFrom(body);
            for (ResourceSpans rs : req.getResourceSpansList())
                for (ScopeSpans ss : rs.getScopeSpansList())
                    for (Span span : ss.getSpansList()) spans.add(new OtlpSpan(span));
        } catch (Exception e) {
            System.err.println("[" + label + "] failed to parse protobuf: " + e.getMessage());
        }
        receivedBatches.add(spans);
        traceLatch.countDown();
        System.out.println(
                "["
                        + label
                        + "] received trace export ("
                        + body.length
                        + " bytes, "
                        + spans.size()
                        + " spans)");
        for (var span : spans) System.out.println("[" + label + "]   " + span);

        // OTLP expects a 200 with empty body
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().close();
    }

    private void handleDefault(HttpExchange exchange) throws IOException {
        System.out.println(
                "["
                        + label
                        + "] unexpected request: "
                        + exchange.getRequestMethod()
                        + " "
                        + exchange.getRequestURI());
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }
}
