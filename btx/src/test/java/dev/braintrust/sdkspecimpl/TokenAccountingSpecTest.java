package dev.braintrust.sdkspecimpl;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TokenAccountingSpec} itself. This class is an assertion helper wired into every
 * spec in the suite, so a bug in it either fails good spans or — worse — silently passes bad ones.
 * These cover both directions.
 */
class TokenAccountingSpecTest {

    /** Builds a metrics map from alternating key/value pairs. */
    private static Map<String, Object> metrics(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private static List<String> check(Map<String, Object> metrics) {
        return TokenAccountingSpec.violations(metrics, null);
    }

    private static void assertClean(Map<String, Object> metrics) {
        assertEquals(List.of(), check(metrics));
    }

    /** Asserts exactly one violation, mentioning each of {@code expectedMentions}. */
    private static void assertViolation(Map<String, Object> metrics, String... expectedMentions) {
        List<String> problems = check(metrics);
        assertEquals(1, problems.size(), () -> "expected exactly one violation, got " + problems);
        for (String mention : expectedMentions) {
            assertTrue(
                    problems.get(0).contains(mention),
                    () -> "violation should mention '" + mention + "': " + problems.get(0));
        }
    }

    @Nested
    class Totals {

        @Test
        void consistentTotalsAreClean() {
            assertClean(metrics("prompt_tokens", 10, "completion_tokens", 5, "tokens", 15));
        }

        @Test
        void mismatchedTotalIsAViolation() {
            assertViolation(
                    metrics("prompt_tokens", 10, "completion_tokens", 5, "tokens", 99),
                    "tokens (99)",
                    "= 15");
        }

        /** Embedding spans omit completion_tokens; the total check must not fire. */
        @Test
        void missingCompletionTokensSkipsTotalCheck() {
            assertClean(metrics("prompt_tokens", 10, "tokens", 10));
        }

        @Test
        void totalAloneIsClean() {
            assertClean(metrics("tokens", 42));
        }

        @Test
        void emptyAndNullMetricsAreClean() {
            assertClean(metrics());
            assertEquals(List.of(), TokenAccountingSpec.violations(null, null));
        }
    }

    @Nested
    class TypesAndSigns {

        @Test
        void negativeCountIsAViolation() {
            assertViolation(metrics("prompt_tokens", -1), "prompt_tokens", "non-negative");
        }

        @Test
        void fractionalCountIsAViolation() {
            assertViolation(metrics("completion_tokens", 1.5), "completion_tokens", "integer");
        }

        @Test
        void nonNumericCountIsAViolation() {
            assertViolation(metrics("tokens", "many"), "tokens", "integer");
        }

        /** JSON may deserialize a whole number as a double; that is still an integer count. */
        @Test
        void wholeNumberDoubleIsAccepted() {
            assertClean(metrics("prompt_tokens", 10.0, "completion_tokens", 5.0, "tokens", 15.0));
        }

        @Test
        void longCountsAreAccepted() {
            assertClean(metrics("prompt_tokens", 10L, "completion_tokens", 5L, "tokens", 15L));
        }

        /** A bad value is reported once, not cascaded into every downstream arithmetic check. */
        @Test
        void badValueDoesNotCascade() {
            assertViolation(
                    metrics("prompt_tokens", -5, "prompt_cached_tokens", 100), "prompt_tokens");
        }

        @Test
        void timeToFirstTokenIsFractionalButMustBeNonNegative() {
            assertClean(metrics("time_to_first_token", 0.734));
            assertViolation(metrics("time_to_first_token", -0.5), "time_to_first_token");
        }

        @Test
        void nonFiniteEstimatedCostIsAViolation() {
            assertViolation(metrics("estimated_cost", Double.NaN), "estimated_cost", "finite");
        }
    }

    @Nested
    class SubsetRules {

        @Test
        void cachedTokensWithinPromptAreClean() {
            assertClean(metrics("prompt_tokens", 100, "prompt_cached_tokens", 80));
        }

        @Test
        void cachedTokensExceedingPromptIsAViolation() {
            List<String> problems = check(metrics("prompt_tokens", 10, "prompt_cached_tokens", 80));
            assertFalse(problems.isEmpty());
            assertTrue(
                    problems.stream().anyMatch(p -> p.contains("prompt_cached_tokens")),
                    () -> problems.toString());
        }

        @Test
        void reasoningTokensExceedingCompletionIsAViolation() {
            assertViolation(
                    metrics("completion_tokens", 10, "completion_reasoning_tokens", 50),
                    "completion_reasoning_tokens",
                    "completion_tokens");
        }

        @Test
        void audioAndImageDetailsAreSubsets() {
            assertClean(
                    metrics(
                            "prompt_tokens", 100,
                            "prompt_audio_tokens", 40,
                            "completion_tokens", 50,
                            "completion_audio_tokens", 20,
                            "completion_image_tokens", 10,
                            "tokens", 150));
            assertViolation(
                    metrics("completion_tokens", 5, "completion_image_tokens", 6),
                    "completion_image_tokens");
        }

        /** Detail metrics are only checked against a parent that is actually present. */
        @Test
        void detailWithoutParentIsClean() {
            assertClean(metrics("prompt_cached_tokens", 500));
        }
    }

    @Nested
    class CacheRollIn {

        /**
         * The regression this whole helper exists for: Anthropic and Bedrock report their native
         * input count exclusive of cache tokens, so copying it into prompt_tokens as-is leaves the
         * cache metrics larger than the total they are a subset of.
         */
        @Test
        void cacheTokensExceedingPromptIsAViolation() {
            List<String> problems =
                    check(
                            metrics(
                                    "prompt_tokens", 12,
                                    "completion_tokens", 30,
                                    "tokens", 42,
                                    "prompt_cached_tokens", 0,
                                    "prompt_cache_creation_5m_tokens", 1365));
            assertTrue(
                    problems.stream().anyMatch(p -> p.contains("exceeds prompt_tokens")),
                    () -> "expected a roll-in violation, got " + problems);
        }

        /** The same payload with cache tokens rolled in is clean. */
        @Test
        void rolledInPromptTokensAreClean() {
            assertClean(
                    metrics(
                            "prompt_tokens", 1377,
                            "completion_tokens", 30,
                            "tokens", 1407,
                            "prompt_cached_tokens", 0,
                            "prompt_cache_creation_5m_tokens", 1365));
        }

        @Test
        void cacheReadsAndWritesAreSummedAgainstPrompt() {
            List<String> problems =
                    check(
                            metrics(
                                    "prompt_tokens", 100,
                                    "prompt_cached_tokens", 60,
                                    "prompt_cache_creation_tokens", 60));
            assertTrue(
                    problems.stream().anyMatch(p -> p.contains("exceeds prompt_tokens")),
                    () -> "reads + writes (120) exceed prompt (100): " + problems);
        }

        /**
         * The per-TTL split is an alternative representation of the aggregate, not extra tokens, so
         * the two are reconciled with max — a span carrying both must not be double-counted.
         */
        @Test
        void aggregateAndSplitAreReconciledWithMaxNotSum() {
            assertClean(
                    metrics(
                            "prompt_tokens", 1000,
                            "prompt_cache_creation_tokens", 600,
                            "prompt_cache_creation_5m_tokens", 600));
            // Summing them would give 1200 and falsely exceed prompt_tokens.
        }

        @Test
        void perTtlBucketsSumTogether() {
            List<String> problems =
                    check(
                            metrics(
                                    "prompt_tokens", 100,
                                    "prompt_cache_creation_5m_tokens", 60,
                                    "prompt_cache_creation_1h_tokens", 60));
            assertTrue(
                    problems.stream().anyMatch(p -> p.contains("exceeds prompt_tokens")),
                    () -> problems.toString());
        }
    }

    @Nested
    class SingleRepresentation {

        @Test
        void anthropicMustNotEmitBothRepresentations() {
            List<String> problems =
                    TokenAccountingSpec.violations(
                            metrics(
                                    "prompt_tokens", 5000,
                                    "prompt_cache_creation_tokens", 1000,
                                    "prompt_cache_creation_5m_tokens", 1000),
                            "anthropic");
            assertTrue(
                    problems.stream().anyMatch(p -> p.contains("not both")),
                    () -> problems.toString());
        }

        /** The rule is a MUST only for Anthropic; elsewhere it is a SHOULD, so it stays quiet. */
        @Test
        void otherProvidersMayEmitBoth() {
            assertEquals(
                    List.of(),
                    TokenAccountingSpec.violations(
                            metrics(
                                    "prompt_tokens", 5000,
                                    "prompt_cache_creation_tokens", 1000,
                                    "prompt_cache_creation_5m_tokens", 1000),
                            "bedrock"));
        }
    }

    @Nested
    class SpanTreeWalking {

        private static Map<String, Object> llmSpan(String name, Map<String, Object> metrics) {
            Map<String, Object> span = new LinkedHashMap<>();
            span.put("name", name);
            span.put("span_attributes", Map.of("type", "llm", "name", name));
            span.put("metadata", Map.of("provider", "anthropic"));
            span.put("metrics", metrics);
            return span;
        }

        @Test
        void cleanTreePasses() {
            TokenAccountingSpec.assertSpanTree(
                    List.of(llmSpan("llm", metrics("prompt_tokens", 10, "tokens", 10))), "spec");
        }

        @Test
        void nullTreePasses() {
            TokenAccountingSpec.assertSpanTree(null, "spec");
        }

        /** Non-LLM spans (e.g. tool spans) carry no token metrics and must be skipped. */
        @Test
        void nonLlmSpansAreIgnored() {
            Map<String, Object> toolSpan = new LinkedHashMap<>();
            toolSpan.put("name", "web_search");
            toolSpan.put("span_attributes", Map.of("type", "tool"));
            toolSpan.put("metrics", metrics("prompt_tokens", -999));
            TokenAccountingSpec.assertSpanTree(List.of(toolSpan), "spec");
        }

        @Test
        void violationInNestedChildSpanIsReported() {
            Map<String, Object> parent =
                    llmSpan("parent", metrics("prompt_tokens", 10, "tokens", 10));
            parent.put(
                    "child_spans",
                    List.of(
                            llmSpan(
                                    "child",
                                    metrics(
                                            "prompt_tokens", 1,
                                            "completion_tokens", 1,
                                            "tokens", 999))));

            AssertionError error =
                    assertThrows(
                            AssertionError.class,
                            () -> TokenAccountingSpec.assertSpanTree(List.of(parent), "spec"));
            assertTrue(error.getMessage().contains("child"), error.getMessage());
            assertTrue(error.getMessage().contains("spec[0][0]"), error.getMessage());
        }

        /** Every violation across the tree is reported at once, not just the first. */
        @Test
        void allViolationsAcrossTheTreeAreReported() {
            AssertionError error =
                    assertThrows(
                            AssertionError.class,
                            () ->
                                    TokenAccountingSpec.assertSpanTree(
                                            List.of(
                                                    llmSpan("a", metrics("prompt_tokens", -1)),
                                                    llmSpan("b", metrics("completion_tokens", -2))),
                                            "spec"));
            assertTrue(error.getMessage().contains("prompt_tokens"), error.getMessage());
            assertTrue(error.getMessage().contains("completion_tokens"), error.getMessage());
        }
    }
}
