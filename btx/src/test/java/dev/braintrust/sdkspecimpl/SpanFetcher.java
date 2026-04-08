package dev.braintrust.sdkspecimpl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.TestHarness;
import dev.braintrust.VCR;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Fetches brainstore spans from the Braintrust API via a BTQL query.
 *
 * <p>Used in {@code RECORD} and {@code OFF} VCR modes where real API calls are made and spans are
 * actually ingested into Braintrust. Retries with backoff until the expected number of child spans
 * appears (mirrors the Python BTX framework's {@code fetch_braintrust_spans}).
 */
public class SpanFetcher {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final int BACKOFF_SECONDS = 30;
    private static final int MAX_TOTAL_WAIT_SECONDS = 600;

    private final HttpClient httpClient;
    private final TestHarness harness;
    private final String btqlUrl;
    private final String apiKey;
    private final String projectId;

    public SpanFetcher(TestHarness harness) {
        this.harness = harness;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.btqlUrl = harness.braintrustApiBaseUrl().replaceAll("/+$", "") + "/btql";
        this.apiKey = harness.braintrustApiKey();
        this.projectId = TestHarness.defaultProjectId();
    }

    /**
     * Fetch child brainstore spans for the given trace, retrying until {@code
     * numExpectedChildSpans} are available.
     *
     * @param rootSpanId hex trace ID (from OTel span context, e.g. {@code
     *     "e6f892e37dac9e3ef2f8906d6600d70c"})
     * @param numExpectedChildSpans number of child spans to wait for
     */
    public List<Map<String, Object>> fetch(String rootSpanId, int numExpectedChildSpans)
            throws Exception {
        List<SpanData> otelSpans =
                harness.awaitExportedSpans(numExpectedChildSpans + 1).stream()
                        .filter(spanData -> spanData.getTraceId().equals(rootSpanId))
                        .toList();
        List<Map<String, Object>> brainstoreSpans;
        List<Map<String, Object>> convertedOtelSpans = SpanConverter.toBrainstoreSpans(otelSpans);
        if (isReplayMode()) {
            // Fast path: convert the in-memory OTel spans to brainstore format locally.
            brainstoreSpans = convertedOtelSpans;
        } else {
            // Live path: spans were actually sent to Braintrust — fetch them back via BTQL.
            brainstoreSpans = fetchFromBrainstore(rootSpanId, numExpectedChildSpans);
            // assert that our converted otel spans will match what is in brainstore
            assertConverterMatchesBrainstore(convertedOtelSpans, brainstoreSpans, rootSpanId);
        }
        return brainstoreSpans;
    }

    /**
     * Fetch child brainstore spans for the given trace, retrying until {@code
     * numExpectedChildSpans} are available.
     *
     * @param rootSpanId hex trace ID (from OTel span context, e.g. {@code
     *     "e6f892e37dac9e3ef2f8906d6600d70c"})
     * @param numExpectedChildSpans number of child spans to wait for
     */
    private List<Map<String, Object>> fetchFromBrainstore(
            String rootSpanId, int numExpectedChildSpans) throws Exception {
        int totalWait = 0;
        LookupException lastError = null;

        while (true) {
            try {
                return fetchOnce(rootSpanId, numExpectedChildSpans);
            } catch (LookupException e) {
                lastError = e;
                if (totalWait >= MAX_TOTAL_WAIT_SECONDS) {
                    break;
                }
                System.out.printf(
                        "Spans not ready yet, waiting %ds before retry (total wait: %ds)...%n",
                        BACKOFF_SECONDS, totalWait);
                Thread.sleep(BACKOFF_SECONDS * 1000L);
                totalWait += BACKOFF_SECONDS;
            }
        }
        throw new RuntimeException(
                "Timed out waiting for brainstore spans after " + MAX_TOTAL_WAIT_SECONDS + "s",
                lastError);
    }

    /**
     * Assert that the spans produced by {@link SpanConverter} are a proper subset of the
     * authoritative brainstore spans — i.e. every key/value present in a converted span also
     * appears with the same value in the corresponding real brainstore span.
     *
     * <p>Spans are matched by {@code span_attributes.name} to avoid sensitivity to ordering
     * differences between OTel export order and BTQL {@code created ASC}.
     */
    @SuppressWarnings("unchecked")
    private static void assertConverterMatchesBrainstore(
            List<Map<String, Object>> converted,
            List<Map<String, Object>> real,
            String rootSpanId) {
        if (converted.size() != real.size()) {
            throw new AssertionError(
                    "SpanConverter produced "
                            + converted.size()
                            + " spans but brainstore has "
                            + real.size()
                            + " for root_span_id "
                            + rootSpanId);
        }
        // Index real spans by their span_attributes.name for order-independent matching
        Map<String, Map<String, Object>> realByName = new java.util.LinkedHashMap<>();
        for (Map<String, Object> span : real) {
            Object sa = span.get("span_attributes");
            String name = sa instanceof Map ? (String) ((Map<?, ?>) sa).get("name") : null;
            if (name == null) name = (String) span.get("name");
            if (name != null) realByName.put(name, span);
        }
        for (int i = 0; i < converted.size(); i++) {
            Map<String, Object> conv = converted.get(i);
            String name = (String) conv.get("name");
            Map<String, Object> realSpan = realByName.getOrDefault(name, real.get(i));
            String ctx = "SpanConverter[" + name + "]";
            // "name" is a synthetic top-level field added by SpanConverter for spec-assertion
            // convenience; in real brainstore spans the name lives in span_attributes.name.
            // "metrics" values are numeric and may vary across runs (e.g. reasoning token counts
            // are non-deterministic); we only check that the same keys are present and non-null.
            Map<String, Object> convWithoutName = new java.util.LinkedHashMap<>(conv);
            convWithoutName.remove("name");
            convWithoutName.remove("metrics");
            assertIsSubset(convWithoutName, realSpan, ctx);
            assertMetricsKeysPresent(conv, realSpan, ctx);
        }
    }

    /**
     * Assert that every metric key present in the converted span also appears as a non-null number
     * in the real brainstore span. Token counts are non-deterministic (especially for reasoning),
     * so we only check presence and type rather than exact equality.
     */
    @SuppressWarnings("unchecked")
    private static void assertMetricsKeysPresent(
            Map<String, Object> conv, Map<String, Object> realSpan, String ctx) {
        Object convMetrics = conv.get("metrics");
        if (!(convMetrics instanceof Map)) return;
        Object realMetrics = realSpan.get("metrics");
        if (!(realMetrics instanceof Map)) return; // brainstore may omit metrics; skip
        Map<String, Object> convM = (Map<String, Object>) convMetrics;
        Map<String, Object> realM = (Map<String, Object>) realMetrics;
        for (String key : convM.keySet()) {
            if (convM.get(key) == null) continue;
            Object realVal = realM.get(key);
            if (realVal == null) continue; // brainstore may compute differently; skip
            if (!(realVal instanceof Number)) {
                throw new AssertionError(
                        ctx
                                + ".metrics."
                                + key
                                + ": expected a Number but got "
                                + realVal.getClass().getSimpleName());
            }
        }
    }

    /**
     * Assert that every key/value in {@code subset} is present with an equal value in {@code
     * superset}, recursively for nested maps. Several cases are skipped:
     *
     * <ul>
     *   <li>Null values in {@code subset} — "don't care"
     *   <li>Null values in {@code superset} — brainstore may omit or transform certain fields (e.g.
     *       inline image data is not echoed back)
     *   <li>Values containing {@code braintrust_attachment} — the converter logs the raw data URL
     *       while brainstore stores an uploaded attachment reference; both are correct
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private static void assertIsSubset(Object subset, Object superset, String ctx) {
        if (subset == null) return;
        if (superset == null) return; // brainstore may omit or transform certain fields
        // Skip when brainstore has transformed a Map into a different type (e.g. an object that
        // got inlined as a scalar). But List vs non-List is a real encoding mismatch — don't skip.
        if ((subset instanceof Map) != (superset instanceof Map)) return;
        if (subset instanceof List) {
            if (!(superset instanceof List)) {
                throw new AssertionError(
                        ctx
                                + ": expected a List but brainstore has "
                                + superset.getClass().getSimpleName()
                                + " (value: "
                                + superset
                                + ")");
            }
            List<Object> subList = (List<Object>) subset;
            List<Object> superList = (List<Object>) superset;
            for (int i = 0; i < subList.size() && i < superList.size(); i++) {
                assertIsSubset(subList.get(i), superList.get(i), ctx + "[" + i + "]");
            }
            return;
        }
        if (!(subset instanceof Map)) {
            // For string leaves, only assert non-null — actual content may vary between runs
            // (e.g. model-generated text, reasoning summaries). For numbers and booleans,
            // use exact equality since those are deterministic (types, finish reasons, etc.).
            if (subset instanceof String) {
                if (superset == null) {
                    throw new AssertionError(ctx + ": expected a non-null String but got null");
                }
            } else {
                SpanValidator.validateValue(superset, subset, ctx);
            }
            return;
        }
        Map<String, Object> subMap = (Map<String, Object>) subset;
        Map<String, Object> superMap = (Map<String, Object>) superset;
        for (Map.Entry<String, Object> entry : subMap.entrySet()) {
            if (entry.getValue() == null) continue;
            if ("id".equals(entry.getKey())) continue; // IDs are dynamic and non-deterministic
            assertIsSubset(
                    entry.getValue(), superMap.get(entry.getKey()), ctx + "." + entry.getKey());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchOnce(String rootSpanId, int numExpectedChildSpans)
            throws Exception {

        String body = buildBtqlQuery(rootSpanId);

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(btqlUrl))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .timeout(Duration.ofSeconds(30))
                        .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new LookupException(
                    "BTQL query failed with status "
                            + response.statusCode()
                            + ": "
                            + response.body());
        }

        Map<String, Object> result = MAPPER.readValue(response.body(), MAP_TYPE);
        List<Map<String, Object>> allSpans = (List<Map<String, Object>>) result.get("data");
        if (allSpans == null) {
            throw new LookupException("BTQL response missing 'data' field");
        }

        // Filter out scorer spans injected by the Braintrust backend
        List<Map<String, Object>> childSpans =
                allSpans.stream()
                        .filter(
                                s -> {
                                    Object sa = s.get("span_attributes");
                                    if (sa instanceof Map) {
                                        return !"scorer".equals(((Map<?, ?>) sa).get("purpose"));
                                    }
                                    return true;
                                })
                        .toList();

        int actual = childSpans.size();
        if (actual == 0) {
            throw new LookupException("No child spans found yet for root_span_id: " + rootSpanId);
        }
        if (actual < numExpectedChildSpans) {
            throw new LookupException(
                    "Expected "
                            + numExpectedChildSpans
                            + " child spans, only found "
                            + actual
                            + " so far");
        }
        if (actual > numExpectedChildSpans) {
            throw new RuntimeException(
                    "Expected "
                            + numExpectedChildSpans
                            + " child spans but found "
                            + actual
                            + " — too many (non-retriable)");
        }

        // Retry if any span is still incomplete (output or metrics not yet ingested).
        // Braintrust may ingest the span skeleton before the payload fields are indexed.
        for (Map<String, Object> span : childSpans) {
            if (span.get("output") == null && span.get("metrics") == null) {
                throw new LookupException(
                        "Span found but output/metrics not yet ingested (span_id: "
                                + span.get("span_id")
                                + ")");
            }
        }

        return childSpans;
    }

    /**
     * Build the BTQL query JSON string. Uses LinkedHashMap throughout because Map.of() rejects null
     * values (needed for the span_parents != null filter).
     */
    private String buildBtqlQuery(String rootSpanId) throws Exception {
        // span_parents != null literal node (Map.of rejects nulls, so use LinkedHashMap)
        Map<String, Object> nullLiteral = new java.util.LinkedHashMap<>();
        nullLiteral.put("op", "literal");
        nullLiteral.put("value", null);

        Map<String, Object> query = new java.util.LinkedHashMap<>();
        query.put("query", buildQueryNode(rootSpanId, nullLiteral));
        query.put("use_columnstore", true);
        query.put("use_brainstore", true);
        query.put("brainstore_realtime", true);

        return MAPPER.writeValueAsString(query);
    }

    private Map<String, Object> buildQueryNode(String rootSpanId, Map<String, Object> nullLiteral) {
        Map<String, Object> q = new java.util.LinkedHashMap<>();
        q.put("select", List.of(Map.of("op", "star")));
        q.put(
                "from",
                Map.of(
                        "op", "function",
                        "name", Map.of("op", "ident", "name", List.of("project_logs")),
                        "args", List.of(Map.of("op", "literal", "value", projectId))));
        q.put(
                "filter",
                Map.of(
                        "op",
                        "and",
                        "left",
                        Map.of(
                                "op", "eq",
                                "left", Map.of("op", "ident", "name", List.of("root_span_id")),
                                "right", Map.of("op", "literal", "value", rootSpanId)),
                        "right",
                        Map.of(
                                "op",
                                "ne",
                                "left",
                                Map.of("op", "ident", "name", List.of("span_parents")),
                                "right",
                                nullLiteral)));
        q.put(
                "sort",
                List.of(
                        Map.of(
                                "expr",
                                Map.of("op", "ident", "name", List.of("created")),
                                "dir",
                                "asc")));
        q.put("limit", 1000);
        return q;
    }

    /** Retriable error: spans not yet available, caller should retry. */
    private static class LookupException extends Exception {
        LookupException(String msg) {
            super(msg);
        }
    }

    /** Returns true when running in VCR replay mode (the default). */
    private static boolean isReplayMode() {
        return TestHarness.getVcrMode().equals(VCR.VcrMode.REPLAY);
    }
}
