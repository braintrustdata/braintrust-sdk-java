package dev.braintrust.sdkspecimpl;

import dev.braintrust.TestHarness;
import dev.braintrust.VCR;
import dev.braintrust.trace.BrainstoreTrace;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.Map;

/**
 * Fetches brainstore spans from the Braintrust API via a BTQL query.
 *
 * <p>Used in {@code RECORD} and {@code OFF} VCR modes where real API calls are made and spans are
 * actually ingested into Braintrust. Delegates to {@link BrainstoreTrace} for retry logic.
 */
public class SpanFetcher {

    private final TestHarness harness;

    public SpanFetcher(TestHarness harness) {
        this.harness = harness;
    }

    /**
     * Fetch brainstore spans for the given trace, retrying until all child spans are available.
     *
     * @param rootSpanId hex trace ID (from OTel span context, e.g. {@code
     *     "e6f892e37dac9e3ef2f8906d6600d70c"})
     * @param numExpectedChildSpans number of child spans to wait for (used to await in-memory OTel
     *     export before querying the live API)
     */
    public List<Map<String, Object>> fetch(String rootSpanId, int numExpectedChildSpans)
            throws Exception {
        // Wait for all spans to flush through the in-memory OTel exporter first.
        // +1 accounts for the root wrapper span created by SpecExecutor.
        List<SpanData> otelSpans =
                harness.awaitExportedSpans(numExpectedChildSpans + 1).stream()
                        .filter(spanData -> spanData.getTraceId().equals(rootSpanId))
                        .toList();
        List<Map<String, Object>> convertedOtelSpans = SpanConverter.toBrainstoreSpans(otelSpans);

        if (isReplayMode()) {
            // Fast path: convert the in-memory OTel spans to brainstore format locally.
            return convertedOtelSpans;
        }

        // Live path: spans were actually sent to Braintrust — fetch them back via BTQL.
        // Use the child span IDs from in-memory OTel as the completion signal: we block
        // until every one of those specific spans has appeared in the backend.
        var childSpanIds =
                otelSpans.stream()
                        .filter(s -> s.getParentSpanContext().isValid()) // exclude root wrapper
                        .map(s -> s.getSpanContext().getSpanId())
                        .toList();

        var trace =
                BrainstoreTrace.forTrace(
                        harness.braintrust().openApiClient(),
                        TestHarness.defaultProjectId(),
                        rootSpanId,
                        childSpanIds);

        // getSpans() triggers the lazy fetch + retry loop
        List<Map<String, Object>> allSpans = trace.getSpans();

        // Exclude the root wrapper span (span_parents is null) and scorer spans injected by
        // the backend — btx only validates the child LLM/tool spans against the spec.
        List<Map<String, Object>> brainstoreSpans =
                allSpans.stream()
                        .filter(
                                s -> {
                                    // Root span has no parents — skip it
                                    Object parents = s.get("span_parents");
                                    if (parents == null
                                            || (parents instanceof List<?> l && l.isEmpty())) {
                                        return false;
                                    }
                                    // Skip scorer spans injected by the backend
                                    Object sa = s.get("span_attributes");
                                    if (sa instanceof Map<?, ?> saMap) {
                                        return !"scorer".equals(saMap.get("purpose"));
                                    }
                                    return true;
                                })
                        .toList();

        // Cross-check that our local OTel→brainstore conversion matches the real thing.
        assertConverterMatchesBrainstore(convertedOtelSpans, brainstoreSpans, rootSpanId);
        return brainstoreSpans;
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

    /** Returns true when running in VCR replay mode (the default). */
    private static boolean isReplayMode() {
        return TestHarness.getVcrMode().equals(VCR.VcrMode.REPLAY);
    }
}
