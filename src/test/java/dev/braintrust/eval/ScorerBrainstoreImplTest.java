package dev.braintrust.eval;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.TestHarness;
import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.config.BraintrustConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

        var config =
                BraintrustConfig.builder()
                        .apiKey(testHarness.braintrustApiKey())
                        .apiUrl(testHarness.braintrustApiBaseUrl())
                        .build();

        apiClient = BraintrustApiClient.of(config);
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
}
