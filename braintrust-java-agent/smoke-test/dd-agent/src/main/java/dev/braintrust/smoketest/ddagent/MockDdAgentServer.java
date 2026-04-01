package dev.braintrust.smoketest.ddagent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import org.msgpack.core.MessageFormat;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

/**
 * A mock DD Agent HTTP server that captures trace payloads sent to {@code PUT /v0.4/traces}.
 *
 * <p>The DD Java agent sends traces as msgpack-encoded arrays of arrays of span maps. This server
 * parses those payloads and stores the decoded spans for assertion.
 */
public class MockDdAgentServer {

    /** A decoded DD span (subset of fields we care about). */
    public static class DdSpan {
        public final String name;
        public final String service;
        public final String resource;
        public final long traceId;
        public final long spanId;
        public final long parentId;
        public final long start; // nanoseconds
        public final long duration; // nanoseconds
        public final int error;
        public final String type;
        public final Map<String, String> meta;

        DdSpan(
                String name,
                String service,
                String resource,
                long traceId,
                long spanId,
                long parentId,
                long start,
                long duration,
                int error,
                String type,
                Map<String, String> meta) {
            this.name = name;
            this.service = service;
            this.resource = resource;
            this.traceId = traceId;
            this.spanId = spanId;
            this.parentId = parentId;
            this.start = start;
            this.duration = duration;
            this.error = error;
            this.type = type;
            this.meta = meta;
        }

        @Override
        public String toString() {
            return "DdSpan{name='%s', service='%s', resource='%s', traceId=%d, spanId=%d, parentId=%d, error=%d}"
                    .formatted(name, service, resource, traceId, spanId, parentId, error);
        }
    }

    /** One trace = a list of spans sharing the same trace ID. */
    private final CopyOnWriteArrayList<List<DdSpan>> receivedTraces = new CopyOnWriteArrayList<>();

    private final CountDownLatch traceLatch;
    private final HttpServer server;
    private final int port;

    /**
     * @param expectedTraceCount how many trace payloads to wait for before unblocking {@link
     *     #awaitTraces}.
     */
    public MockDdAgentServer(int expectedTraceCount, int port) throws IOException {
        this.traceLatch = new CountDownLatch(expectedTraceCount);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.port = server.getAddress().getPort();

        // DD agent sends PUT /v0.3/traces or /v0.4/traces or /v0.5/traces
        server.createContext("/", this::handleRequest);
    }

    public void start() {
        server.start();
        System.out.println("[mock-dd-agent] Listening on port " + port);
    }

    public void stop() {
        server.stop(0);
    }

    public int getPort() {
        return port;
    }

    /** Block until the expected number of trace batches have been received, or timeout. */
    public boolean awaitTraces(long timeout, TimeUnit unit) throws InterruptedException {
        return traceLatch.await(timeout, unit);
    }

    /** Returns all received traces (each trace is a list of spans). */
    public List<List<DdSpan>> getReceivedTraces() {
        return Collections.unmodifiableList(receivedTraces);
    }

    /** Flattened list of all received spans across all traces. */
    public List<DdSpan> getAllSpans() {
        List<DdSpan> all = new ArrayList<>();
        for (var trace : receivedTraces) {
            all.addAll(trace);
        }
        return all;
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        // DD agent sends PUT /v0.X/traces
        if ("PUT".equalsIgnoreCase(method) && path.matches("/v0\\.\\d+/traces")) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            try {
                List<List<DdSpan>> traces = decodeMsgpack(body);
                for (var trace : traces) {
                    receivedTraces.add(trace);
                    traceLatch.countDown();
                }
                System.out.println(
                        "[mock-dd-agent] Received %d trace(s) on %s"
                                .formatted(traces.size(), path));
            } catch (Exception e) {
                System.err.println(
                        "[mock-dd-agent] Failed to decode msgpack on %s (%d bytes): %s"
                                .formatted(path, body.length, e.getMessage()));
                e.printStackTrace();
            }
            // Respond 200 with a rates response (DD agent expects JSON with rate_by_service)
            byte[] resp = "{\"rate_by_service\":{}}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.getResponseBody().close();
        } else {
            // Other endpoints the DD agent may probe (e.g., /info, /v0.7/config)
            byte[] resp = "{}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.getResponseBody().close();
        }
    }

    /**
     * Decodes the DD msgpack trace payload. Format: array of traces, where each trace is an array
     * of spans, and each span is a map.
     */
    static List<List<DdSpan>> decodeMsgpack(byte[] data) throws IOException {
        List<List<DdSpan>> traces = new ArrayList<>();
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(data)) {
            int numTraces = unpacker.unpackArrayHeader();
            for (int t = 0; t < numTraces; t++) {
                int numSpans = unpacker.unpackArrayHeader();
                List<DdSpan> trace = new ArrayList<>(numSpans);
                for (int s = 0; s < numSpans; s++) {
                    trace.add(decodeSpan(unpacker));
                }
                traces.add(trace);
            }
        }
        return traces;
    }

    private static DdSpan decodeSpan(MessageUnpacker unpacker) throws IOException {
        int mapSize = unpacker.unpackMapHeader();
        String name = "";
        String service = "";
        String resource = "";
        long traceId = 0;
        long spanId = 0;
        long parentId = 0;
        long start = 0;
        long duration = 0;
        int error = 0;
        String type = "";
        Map<String, String> meta = Map.of();

        for (int i = 0; i < mapSize; i++) {
            String key = unpacker.unpackString();
            if (unpacker.tryUnpackNil()) {
                // Value is nil — skip it, keep defaults.
                continue;
            }
            switch (key) {
                case "name" -> name = unpacker.unpackString();
                case "service" -> service = unpacker.unpackString();
                case "resource" -> resource = unpacker.unpackString();
                case "trace_id" -> traceId = unpacker.unpackBigInteger().longValue();
                case "span_id" -> spanId = unpacker.unpackBigInteger().longValue();
                case "parent_id" -> parentId = unpacker.unpackBigInteger().longValue();
                case "start" -> start = unpacker.unpackLong();
                case "duration" -> duration = unpacker.unpackLong();
                case "error" -> error = unpacker.unpackInt();
                case "type" -> type = unpacker.unpackString();
                case "meta" -> meta = unpackStringMap(unpacker);
                default -> unpacker.skipValue();
            }
        }
        return new DdSpan(
                name, service, resource, traceId, spanId, parentId, start, duration, error, type,
                meta);
    }

    private static Map<String, String> unpackStringMap(MessageUnpacker unpacker)
            throws IOException {
        int size = unpacker.unpackMapHeader();
        Map<String, String> map = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            // Keys are always strings, but values might not be
            String k = unpacker.unpackString();
            MessageFormat fmt = unpacker.getNextFormat();
            if (fmt.getValueType() == org.msgpack.value.ValueType.STRING) {
                map.put(k, unpacker.unpackString());
            } else {
                // Skip non-string values (metrics map may have numbers)
                unpacker.skipValue();
            }
        }
        return map;
    }
}
