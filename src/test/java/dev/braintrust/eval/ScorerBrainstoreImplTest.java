package dev.braintrust.eval;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.TestHarness;
import dev.braintrust.VCR;
import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.trace.BraintrustContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@Slf4j
public class ScorerBrainstoreImplTest {
    // NOTE: the remote scorers under test are standard boilerplate
    // TODO: test is VCR'd so it's fine, but would be nice to have logic to (re)create the score
    // objects if they are absent

    // returns 1.0 for an exact match, 0.0 otherwise
    private static final String SCORER_SLUG = "typescriptexactmatch-9e44";

    // LLM judge scorer that returns {"name":"close-enough-judge","metadata":{"choice":"0.9",...}}
    private static final String LLM_JUDGE_SLUG = "close-enough-judge-d31b";

    private TestHarness testHarness;
    private BraintrustApiClient apiClient;

    @BeforeEach
    void beforeEach() {
        testHarness = TestHarness.setup();
        apiClient =
                testHarness.braintrust().apiClient(); // TODO -- do we need a separate var for this?
    }

    @Test
    void testScorerReturnsOneForExactMatch() {
        Scorer<String, String> scorer =
                Scorer.fetchFromBraintrust(
                        apiClient,
                        testHarness.braintrust().config().defaultProjectName().orElseThrow(),
                        SCORER_SLUG,
                        null);
        assertNotNull(scorer);
        assertNotNull(scorer.getName());

        var datasetCase = DatasetCase.of("test input", "hello world");
        var taskResult = new TaskResult<>("hello world", datasetCase);

        var scores = scorer.score(taskResult);

        assertFalse(scores.isEmpty(), "Expected scores but got empty list");
        assertEquals(1.0, scores.get(0).value(), 0.001, "Exact match should return 1.0");
    }

    @Test
    void testScorerReturnsZeroForMismatch() {
        Scorer<String, String> scorer =
                Scorer.fetchFromBraintrust(
                        apiClient,
                        testHarness.braintrust().config().defaultProjectName().orElseThrow(),
                        SCORER_SLUG,
                        null);
        assertNotNull(scorer);
        assertNotNull(scorer.getName());

        var datasetCase = DatasetCase.of("test input", "expected");
        var taskResult = new TaskResult<>("different", datasetCase);

        var scores = scorer.score(taskResult);

        assertFalse(scores.isEmpty(), "Expected scores but got empty list");
        assertEquals(0.0, scores.get(0).value(), 0.001, "Mismatch should return 0.0");
    }

    @Test
    void testScorerOldVersion() {
        // Version 485dbf64e486ab3a of the exact match scorer always returns 0, even for exact
        // matches
        String oldVersion = "485dbf64e486ab3a";
        Scorer<String, String> scorer =
                Scorer.fetchFromBraintrust(
                        apiClient,
                        testHarness.braintrust().config().defaultProjectName().orElseThrow(),
                        SCORER_SLUG,
                        oldVersion);
        assertNotNull(scorer);
        assertNotNull(scorer.getName());

        var datasetCase = DatasetCase.of("test input", "hello world");
        var taskResult = new TaskResult<>("hello world", datasetCase);

        var scores = scorer.score(taskResult);

        assertFalse(scores.isEmpty(), "Expected scores but got empty list");
        assertEquals(
                0.0,
                scores.get(0).value(),
                0.001,
                "Old version %s should always return 0.0, even for exact match"
                        .formatted(oldVersion));
    }

    @Test
    void testLlmJudgeScorerReturnsScoreFromMetadataChoice() {
        Scorer<String, String> scorer =
                Scorer.fetchFromBraintrust(
                        apiClient,
                        testHarness.braintrust().config().defaultProjectName().orElseThrow(),
                        LLM_JUDGE_SLUG,
                        null);
        assertNotNull(scorer);
        assertNotNull(scorer.getName());

        // LLM judge evaluates whether output is "close enough" to expected
        var datasetCase = DatasetCase.of("What is 2+2?", "4");
        var taskResult = new TaskResult<>("four", datasetCase);

        var scores = scorer.score(taskResult);

        assertFalse(scores.isEmpty(), "Expected scores but got empty list");
        // LLM judge returns score in metadata.choice field
        // The score should be between 0.0 and 1.0
        assertTrue(
                scores.get(0).value() >= 0.0 && scores.get(0).value() <= 1.0,
                "LLM judge score should be between 0.0 and 1.0, got: " + scores.get(0).value());
        // Verify the scorer name comes from the response
        assertEquals(
                "close-enough-judge",
                scores.get(0).name(),
                "Scorer name should come from the LLM judge response");
    }

    @Test
    void testDistributedTracingWithRemoteScorer() throws InterruptedException {
        String projectName = testHarness.braintrust().config().defaultProjectName().orElseThrow();

        Scorer<String, String> scorer =
                Scorer.fetchFromBraintrust(apiClient, projectName, LLM_JUDGE_SLUG, null);
        assertNotNull(scorer);

        // Get tracer from test harness
        Tracer tracer = testHarness.openTelemetry().getTracer("distributed-trace-test");
        Span parentSpan = tracer.spanBuilder("test-distributed-trace-parent").startSpan();
        Context ctx =
                BraintrustContext.setParentInBaggage(
                        Context.root().with(parentSpan),
                        "project_id",
                        TestHarness.defaultProjectId());
        try (var scope = ctx.makeCurrent()) {
            // Call the scorer - it should pick up the OTEL context and baggage
            // and pass parent info to the remote function
            var datasetCase = DatasetCase.of("test input", "hello world");
            var taskResult = new TaskResult<>("hello world", datasetCase);

            var scores = scorer.score(taskResult);

            assertFalse(scores.isEmpty(), "Expected scores but got empty list");
        } finally {
            parentSpan.end();
        }

        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var rootSpan = spans.get(0);
        var traceId = rootSpan.getTraceId();
        var spanId = rootSpan.getSpanId();

        // Step 2: Query Braintrust for spans with our root_span_id to verify tracing
        if (TestHarness.getVcrMode() == VCR.VcrMode.REPLAY) {
            // TODO -- come up with a long-term solution for querying objects with dynamic IDs
            log.info(
                    "Skipping distributed trace verification in VCR replay mode (trace IDs are"
                            + " dynamic)");
            return;
        }

        // The OTEL traceId (32 hex chars) maps to Braintrust root_span_id
        String projectId = TestHarness.defaultProjectId();

        // Poll for eventual consistency - spans may take a moment to be indexed
        BraintrustApiClient.BtqlQueryResponse response = null;
        int maxAttempts = 30;
        int attemptDelayMs = 2000;

        // First, query by root_span_id to find all spans in our trace
        String rootSpanQuery =
                "select: span_id, span_parents, root_span_id, name | from: project_logs('%s') | filter: root_span_id = '%s'"
                        .formatted(projectId, traceId);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            response = apiClient.btqlQuery(rootSpanQuery);
            if (response != null && response.data() != null && !response.data().isEmpty()) {
                break;
            }
            if (attempt < maxAttempts) {
                Thread.sleep(attemptDelayMs);
            }
        }

        assertNotNull(response, "BTQL query response should not be null");
        assertNotNull(response.data(), "BTQL query data should not be null");

        // Now check if any span has our spanId in its span_parents
        boolean foundChildSpan =
                response.data().stream()
                        .anyMatch(
                                row -> {
                                    Object spanParents = row.get("span_parents");
                                    if (spanParents instanceof List<?> list) {
                                        return list.contains(spanId);
                                    }
                                    return false;
                                });

        assertTrue(
                foundChildSpan,
                "Expected to find a span with parent spanId '%s' in trace '%s'. Found %d spans total."
                        .formatted(spanId, traceId, response.data().size()));
    }
}
