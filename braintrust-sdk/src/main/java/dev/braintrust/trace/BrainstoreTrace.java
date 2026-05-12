package dev.braintrust.trace;

import dev.braintrust.api.BraintrustOpenApiClient;
import dev.braintrust.api.BtqlRateLimitException;
import dev.braintrust.json.BraintrustJsonMapper;
import io.opentelemetry.api.GlobalOpenTelemetry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * Provides access to the distributed trace spans for a single eval task stored in Braintrust.
 *
 * <p>Spans are fetched lazily on first access and cached for subsequent calls. Score-type spans are
 * excluded from all results (they are filtered out by the BTQL query).
 */
@Slf4j
public class BrainstoreTrace {
    /** Maximum number of attempts (exponential backoff: 1s→2s→4s→…→30s). */
    private static final int MAX_ATTEMPTS = 8;

    /** Fixed sleep when the server returns a 429 rate-limit; the attempt is not counted. */
    private static final int MAX_RATE_LIMIT_ATTEMPTS = 30;

    private static final long RATE_LIMIT_SLEEP_MS = 30_000;

    private static final int BASE_DELAY_MS = 5_000;
    private static final int MAX_DELAY_MS = 30_000;

    private final Supplier<List<Map<String, Object>>> spansSupplier;
    private final ReentrantLock lock = new ReentrantLock();

    @Nullable private volatile List<Map<String, Object>> cachedSpans;

    /**
     * Creates a {@code BrainstoreTrace} that fetches spans for a trace stored in a Braintrust
     * experiment.
     *
     * <p>Queries {@code experiment('<experimentId>')} and excludes score-type spans. The retry loop
     * blocks until all {@code expectedSpanIds} are present in the results.
     *
     * @param client the API client used to execute BTQL queries
     * @param experimentId the experiment whose spans to query
     * @param rootTraceId the OTel trace ID (hex string, 32 chars)
     * @param expectedSpanIds OTel span IDs (16 hex chars each) that must all appear in the results
     *     before the fetch is considered complete
     */
    public static BrainstoreTrace forExperiment(
            @Nonnull BraintrustOpenApiClient client,
            @Nonnull String experimentId,
            @Nonnull String rootTraceId,
            @Nonnull List<String> expectedSpanIds) {
        var safeExperimentId = experimentId.replace("'", "''");
        var safeRootTraceId = rootTraceId.replace("'", "''");
        var query =
                "SELECT * FROM experiment('%s') WHERE root_span_id = '%s' AND span_attributes.type != 'score' ORDER BY created ASC LIMIT 1000"
                        .formatted(safeExperimentId, safeRootTraceId);
        return new BrainstoreTrace(() -> fetchWithRetry(client, query, expectedSpanIds));
    }

    /**
     * Creates a {@code BrainstoreTrace} that fetches spans for a trace stored in Braintrust project
     * logs.
     *
     * <p>Queries {@code project_logs('<projectId>')} for all spans in the trace. The retry loop
     * blocks until all {@code expectedSpanIds} are present in the results.
     *
     * @param client the API client used to execute BTQL queries
     * @param projectId the project whose logs to query
     * @param rootTraceId the OTel trace ID (hex string, 32 chars)
     * @param expectedSpanIds OTel span IDs (16 hex chars each) that must all appear in the results
     *     before the fetch is considered complete
     */
    public static BrainstoreTrace forTrace(
            @Nonnull BraintrustOpenApiClient client,
            @Nonnull String projectId,
            @Nonnull String rootTraceId,
            @Nonnull List<String> expectedSpanIds) {
        var safeProjectId = projectId.replace("'", "''");
        var safeRootTraceId = rootTraceId.replace("'", "''");
        var query =
                "SELECT * FROM project_logs('%s') WHERE root_span_id = '%s' ORDER BY created ASC LIMIT 1000"
                        .formatted(safeProjectId, safeRootTraceId);
        return new BrainstoreTrace(() -> fetchWithRetry(client, query, expectedSpanIds));
    }

    /**
     * Creates a {@code BrainstoreTrace} backed by a custom supplier. Primarily useful for testing.
     */
    BrainstoreTrace(@Nonnull Supplier<List<Map<String, Object>>> spansSupplier) {
        this.spansSupplier = spansSupplier;
    }

    /**
     * Returns all non-score spans for this trace. Results are fetched on first call and cached.
     *
     * @return an immutable list of span maps; each map contains the span's fields as returned by
     *     BTQL (e.g. {@code "input"}, {@code "output"}, {@code "span_attributes"}, {@code
     *     "start_time"}, {@code "end_time"})
     */
    public List<Map<String, Object>> getSpans() {
        var cached = cachedSpans;
        if (cached != null) {
            return cached;
        }
        lock.lock();
        try {
            if (cachedSpans == null) {
                cachedSpans = List.copyOf(spansSupplier.get());
            }
            return cachedSpans;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns spans filtered by {@code span_attributes.type}.
     *
     * <p>Common types: {@code "llm"}, {@code "task"}, {@code "eval"}, {@code "tool"}, {@code
     * "function"}.
     *
     * @param spanType the value of {@code span_attributes.type} to filter by
     * @return spans whose {@code span_attributes.type} matches {@code spanType}
     */
    public List<Map<String, Object>> getSpans(@Nonnull String spanType) {
        return getSpans().stream().filter(span -> spanType.equals(getSpanType(span))).toList();
    }

    /**
     * Reconstructs the LLM conversation thread from all LLM spans in this trace.
     *
     * <p>Flattens the span tree via pre-order DFS (parent before children, siblings in {@code
     * metrics.start} order), then walks the resulting LLM-span sequence and de-duplicates using a
     * seen-set: any input or output item already added to the thread is skipped.
     *
     * @return a flat, ordered list of message/output maps from all LLM spans in the trace
     */
    public List<Map<String, Object>> getLLMConversationThread() {
        var allSpans = getSpans();

        // Build children map: parent_id → children sorted by start_time
        var children = new java.util.LinkedHashMap<String, List<Map<String, Object>>>();
        for (var span : allSpans) {
            var parents = span.get("span_parents");
            if (parents instanceof List<?> parentList && !parentList.isEmpty()) {
                if (parentList.get(0) instanceof String pid) {
                    children.computeIfAbsent(pid, k -> new ArrayList<>()).add(span);
                }
            }
        }
        children.values()
                .forEach(
                        list ->
                                list.sort(
                                        (a, b) ->
                                                Double.compare(getStartTime(a), getStartTime(b))));

        // Find root span (no parents)
        var root =
                allSpans.stream()
                        .filter(
                                s -> {
                                    var p = s.get("span_parents");
                                    return p == null || (p instanceof List<?> l && l.isEmpty());
                                })
                        .findFirst()
                        .orElse(null);
        if (root == null) return List.of();

        // Pre-order DFS to get all LLM spans in hierarchy order.
        // Prune entire subtrees rooted at scorer spans (purpose == "scorer") — these are
        // synthetic spans injected by the Braintrust backend and not part of the real trace.
        var llmSpansInOrder = new ArrayList<Map<String, Object>>();
        var stack = new java.util.ArrayDeque<Map<String, Object>>();
        stack.push(root);
        while (!stack.isEmpty()) {
            var span = stack.pop();
            if ("automation".equals(getSpanType(span))) {
                // prune topics and other synthetic spans
                continue;
            }
            if ("llm".equals(getSpanType(span))) {
                llmSpansInOrder.add(span);
            }
            // Push children in reverse order so first child is processed first
            var spanId = span.get("span_id");
            if (spanId instanceof String sid) {
                var childList = children.getOrDefault(sid, List.of());
                for (int i = childList.size() - 1; i >= 0; i--) {
                    stack.push(childList.get(i));
                }
            }
        }

        // Walk LLM spans in order, adding unseen input/output items
        var thread = new ArrayList<Map<String, Object>>();
        var seen = new java.util.LinkedHashSet<Object>();
        for (var span : llmSpansInOrder) {
            for (var msg : getInputMessages(span)) {
                if (seen.add(msg)) {
                    thread.add(msg);
                }
            }
            for (var out : getOutputMessages(span)) {
                if (seen.add(out)) {
                    thread.add(out);
                }
            }
        }
        return List.copyOf(thread);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    @Nullable
    private static String getSpanType(Map<String, Object> span) {
        var attrs = span.get("span_attributes");
        if (attrs instanceof Map<?, ?> attrsMap) {
            var type = attrsMap.get("type");
            return type instanceof String s ? s : null;
        }
        return null;
    }

    private static double getStartTime(Map<String, Object> span) {
        var t = span.get("metrics");
        if (t instanceof Map<?, ?> metrics) {
            var start = metrics.get("start");
            if (start instanceof Number n) return n.doubleValue();
        }
        return Double.MAX_VALUE;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> getInputMessages(Map<String, Object> span) {
        var input = span.get("input");
        // Input may be a raw List or a JSON-encoded string
        if (input instanceof List<?> inputList) {
            return inputList.isEmpty() ? List.of() : (List<Map<String, Object>>) inputList;
        }
        if (input instanceof String s && !s.isBlank()) {
            try {
                var parsed = BraintrustJsonMapper.fromJson(s, List.class);
                if (parsed instanceof List<?> l) {
                    return (List<Map<String, Object>>) l;
                }
            } catch (Exception e) {
                log.debug("could not parse input as JSON array: {}", e.getMessage());
            }
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> getOutputMessages(Map<String, Object> span) {
        var output = span.get("output");
        if (output instanceof List<?> outputList) {
            return outputList.isEmpty() ? List.of() : (List<Map<String, Object>>) outputList;
        }
        log.debug("unexpected output type: {}", BraintrustJsonMapper.toJson(output));
        return List.of();
    }

    /**
     * Polls BTQL with the given {@code query}, retrying with exponential backoff until all {@code
     * expectedSpanIds} appear in the results, or until max attempts are exhausted.
     *
     * <p>429 rate-limit responses are handled transparently: the thread sleeps {@link
     * #RATE_LIMIT_SLEEP_MS} and the attempt is retried without consuming an attempt slot.
     */
    @SneakyThrows
    private static List<Map<String, Object>> fetchWithRetry(
            BraintrustOpenApiClient client, String query, List<String> expectedSpanIds) {

        BraintrustOpenApiClient.BtqlQueryResponse lastResponse = null;
        int delayMs = BASE_DELAY_MS;

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("BrainstoreTrace: interrupted while waiting for expected spans");
                    break;
                }
                delayMs = Math.min(delayMs * 2, MAX_DELAY_MS);
            }

            // Retry rate-limit responses without consuming an attempt slot.
            for (int rateLimitAttempt = 0;
                    rateLimitAttempt < MAX_RATE_LIMIT_ATTEMPTS;
                    ++rateLimitAttempt) {
                try {
                    lastResponse = client.btqlQuery(query);
                    break;
                } catch (BtqlRateLimitException e) {
                    if (rateLimitAttempt == MAX_RATE_LIMIT_ATTEMPTS - 1) {
                        log.error(
                                "Failed to fetch spans from Braintrust. Max attempts exceeded."
                                        + " Giving up.");
                        throw e;
                    }
                    log.debug(
                            "BrainstoreTrace: rate limited, sleeping {}ms then retrying: {}",
                            RATE_LIMIT_SLEEP_MS,
                            e.getMessage());
                    Thread.sleep(RATE_LIMIT_SLEEP_MS);
                }
            }
            if (lastResponse == null) break;

            var presentSpanIds =
                    lastResponse.data().stream()
                            .map(row -> row.get("span_id"))
                            .filter(id -> id instanceof String)
                            .map(id -> (String) id)
                            .collect(Collectors.toSet());
            var missingSpanIds =
                    expectedSpanIds.stream().filter(id -> !presentSpanIds.contains(id)).toList();

            log.debug(
                    "BrainstoreTrace BTQL attempt {}/{}: rows={}, missing={}/{}",
                    attempt + 1,
                    MAX_ATTEMPTS,
                    lastResponse.data().size(),
                    missingSpanIds.size(),
                    expectedSpanIds.size());

            if (missingSpanIds.isEmpty()) {
                break;
            } else if (attempt == 0) {
                // OPTIMIZATION: force flush otel to get data into braintrust faster
                BraintrustTracing.attemptForceFlush(GlobalOpenTelemetry.get());
            }

            if (attempt >= (MAX_ATTEMPTS - 1)) {
                throw new RuntimeException(
                        ("BrainstoreTrace: max attempts reached waiting for expected spans. "
                                        + "missing span IDs: %s")
                                .formatted(missingSpanIds));
            }
        }

        return lastResponse == null ? List.of() : List.copyOf(lastResponse.data());
    }
}
