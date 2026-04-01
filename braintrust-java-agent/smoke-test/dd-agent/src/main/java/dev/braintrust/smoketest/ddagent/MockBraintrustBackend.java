package dev.braintrust.smoketest.ddagent;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A mock Braintrust OTLP backend that captures trace export requests sent to {@code POST
 * /otel/v1/traces}.
 *
 * <p>The Braintrust agent exports spans via HTTP/Protobuf (OtlpHttpSpanExporter). This server
 * accepts those requests, parses the protobuf payloads, and stores decoded spans for assertion.
 */
public class MockBraintrustBackend {

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
        public final int statusCode;
        public final String statusMessage;

        OtlpSpan(Span protoSpan) {
            this.name = protoSpan.getName();
            this.traceId = hexString(protoSpan.getTraceId().toByteArray());
            this.spanId = hexString(protoSpan.getSpanId().toByteArray());
            this.parentSpanId = hexString(protoSpan.getParentSpanId().toByteArray());
            this.kind = protoSpan.getKind();
            this.startTimeUnixNano = protoSpan.getStartTimeUnixNano();
            this.endTimeUnixNano = protoSpan.getEndTimeUnixNano();
            this.attributes = extractAttributes(protoSpan.getAttributesList());
            this.statusCode = protoSpan.getStatus().getCodeValue();
            this.statusMessage = protoSpan.getStatus().getMessage();
        }

        /** Get a string attribute value, or null if not present. */
        public String stringAttr(String key) {
            Object v = attributes.get(key);
            return v instanceof String s ? s : null;
        }

        /** Get a boolean attribute value, or null if not present. */
        public Boolean boolAttr(String key) {
            Object v = attributes.get(key);
            return v instanceof Boolean b ? b : null;
        }

        /** Get a long attribute value, or null if not present. */
        public Long longAttr(String key) {
            Object v = attributes.get(key);
            return v instanceof Long l ? l : null;
        }

        @Override
        public String toString() {
            return "OtlpSpan{name='%s', traceId=%s, spanId=%s, parentSpanId=%s, kind=%s, attrs=%s}"
                    .formatted(name, traceId, spanId, parentSpanId, kind, attributes.keySet());
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
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    }

    /** A captured OTLP trace export request with parsed spans. */
    public static class CapturedRequest {
        public final byte[] body;
        public final Map<String, List<String>> headers;
        public final List<OtlpSpan> spans;

        CapturedRequest(byte[] body, Map<String, List<String>> headers, List<OtlpSpan> spans) {
            this.body = body;
            this.headers = headers;
            this.spans = spans;
        }

        /** Returns the value of the x-bt-parent header, or null if not present. */
        public String btParent() {
            var values = headers.get("X-bt-parent");
            if (values == null) values = headers.get("x-bt-parent");
            return values != null && !values.isEmpty() ? values.get(0) : null;
        }

        /** Returns the Authorization header value, or null if not present. */
        public String authorization() {
            var values = headers.get("Authorization");
            if (values == null) values = headers.get("authorization");
            return values != null && !values.isEmpty() ? values.get(0) : null;
        }
    }

    private final CopyOnWriteArrayList<CapturedRequest> traceRequests =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CapturedRequest> logRequests = new CopyOnWriteArrayList<>();
    private final CountDownLatch traceLatch;
    private final HttpServer server;
    private final int port;

    /**
     * @param expectedTraceRequestCount how many trace export requests to wait for before unblocking
     *     {@link #awaitTraces}.
     */
    public MockBraintrustBackend(int expectedTraceRequestCount, int port) throws IOException {
        this.traceLatch = new CountDownLatch(expectedTraceRequestCount);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.port = server.getAddress().getPort();

        server.createContext("/otel/v1/traces", this::handleTraces);
        server.createContext("/otel/v1/logs", this::handleLogs);
        server.createContext("/", this::handleDefault);
    }

    public void start() {
        server.start();
        System.out.println("[mock-bt-backend] Listening on port " + port);
    }

    public void stop() {
        server.stop(0);
    }

    public int getPort() {
        return port;
    }

    /** Block until the expected number of trace export requests have been received, or timeout. */
    public boolean awaitTraces(long timeout, TimeUnit unit) throws InterruptedException {
        return traceLatch.await(timeout, unit);
    }

    /** Returns all captured trace export requests. */
    public List<CapturedRequest> getTraceRequests() {
        return Collections.unmodifiableList(traceRequests);
    }

    /** Returns all captured log export requests. */
    public List<CapturedRequest> getLogRequests() {
        return Collections.unmodifiableList(logRequests);
    }

    /** Total number of trace export requests received. */
    public int traceRequestCount() {
        return traceRequests.size();
    }

    /** Total number of log export requests received. */
    public int logRequestCount() {
        return logRequests.size();
    }

    /** Returns a flattened list of all spans across all trace requests. */
    public List<OtlpSpan> getAllSpans() {
        List<OtlpSpan> all = new ArrayList<>();
        for (var req : traceRequests) {
            all.addAll(req.spans);
        }
        return all;
    }

    /** Find a span by name across all received trace requests. */
    public OtlpSpan findSpanByName(String name) {
        for (var req : traceRequests) {
            for (var span : req.spans) {
                if (name.equals(span.name)) {
                    return span;
                }
            }
        }
        return null;
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
            ExportTraceServiceRequest request = ExportTraceServiceRequest.parseFrom(body);
            for (ResourceSpans rs : request.getResourceSpansList()) {
                for (ScopeSpans ss : rs.getScopeSpansList()) {
                    for (Span span : ss.getSpansList()) {
                        spans.add(new OtlpSpan(span));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[mock-bt-backend] Failed to parse protobuf: " + e.getMessage());
        }
        var captured = new CapturedRequest(body, exchange.getRequestHeaders(), spans);
        traceRequests.add(captured);
        traceLatch.countDown();

        System.out.println(
                "[mock-bt-backend] Received trace export (%d bytes, %d spans, x-bt-parent=%s)"
                        .formatted(body.length, spans.size(), captured.btParent()));

        // OTLP expects a 200 with an empty ExportTraceServiceResponse protobuf.
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().close();
    }

    private void handleLogs(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        byte[] body = exchange.getRequestBody().readAllBytes();
        var captured = new CapturedRequest(body, exchange.getRequestHeaders(), List.of());
        logRequests.add(captured);

        System.out.println(
                "[mock-bt-backend] Received log export (%d bytes)".formatted(body.length));

        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().close();
    }

    private void handleDefault(HttpExchange exchange) throws IOException {
        System.out.println(
                "[mock-bt-backend] Unexpected request: %s %s"
                        .formatted(exchange.getRequestMethod(), exchange.getRequestURI()));
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }
}
