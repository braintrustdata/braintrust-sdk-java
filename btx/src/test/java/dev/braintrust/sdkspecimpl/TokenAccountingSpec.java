package dev.braintrust.sdkspecimpl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Provider-independent conformance checks for the token metrics on an LLM span.
 *
 * <p>These invariants come from the braintrust-spec token/cost rules and cannot be written as
 * per-field YAML assertions, because every one of them relates <em>several</em> metrics to each
 * other — {@code !fn} predicates only ever see a single value. Rather than grow the spec's matcher
 * vocabulary to support cross-field references, the runner pipes every LLM span it already collects
 * through this class, so each spec in the suite gets token-accounting coverage for free.
 *
 * <p>The rules enforced, all from {@code features/token-and-cost-metrics.md}:
 *
 * <ul>
 *   <li>Every token metric is a non-negative integer. Fractional or negative counts are a bug, and
 *       missing data must be omitted rather than fabricated as zero.
 *   <li>{@code tokens == prompt_tokens + completion_tokens} whenever all three are present.
 *   <li>Prompt-side detail metrics are <em>subsets</em> of {@code prompt_tokens}, and
 *       completion-side detail metrics are subsets of {@code completion_tokens} — never additional
 *       token classes.
 *   <li>Cache reads and cache writes are disjoint subsets of {@code prompt_tokens}, so their sum
 *       cannot exceed it. This is the check that catches a provider whose native prompt count
 *       <em>excludes</em> cache tokens (Anthropic and Bedrock both do) being copied into {@code
 *       prompt_tokens} without rolling the cache counts back in.
 *   <li>Anthropic spans carry exactly one representation of cache-creation tokens: the per-TTL
 *       breakdown or the aggregate, not both.
 * </ul>
 *
 * <p>A missing metric is never a violation — these are consistency rules, not presence rules.
 * Presence is asserted per spec in the YAML, where it belongs.
 */
public final class TokenAccountingSpec {

    private static final String PROMPT = "prompt_tokens";
    private static final String COMPLETION = "completion_tokens";
    private static final String TOTAL = "tokens";
    private static final String CACHED = "prompt_cached_tokens";
    private static final String CACHE_CREATE = "prompt_cache_creation_tokens";
    private static final String CACHE_CREATE_5M = "prompt_cache_creation_5m_tokens";
    private static final String CACHE_CREATE_1H = "prompt_cache_creation_1h_tokens";

    /** Detail metrics that must not exceed {@code prompt_tokens}. */
    private static final List<String> PROMPT_SUBSET_METRICS =
            List.of(CACHED, CACHE_CREATE, CACHE_CREATE_5M, CACHE_CREATE_1H, "prompt_audio_tokens");

    /** Detail metrics that must not exceed {@code completion_tokens}. */
    private static final List<String> COMPLETION_SUBSET_METRICS =
            List.of(
                    "completion_reasoning_tokens",
                    "completion_audio_tokens",
                    "completion_image_tokens");

    /** Every metric that must be a non-negative integer. */
    private static final List<String> INTEGER_METRICS = new ArrayList<>();

    static {
        INTEGER_METRICS.add(PROMPT);
        INTEGER_METRICS.add(COMPLETION);
        INTEGER_METRICS.add(TOTAL);
        INTEGER_METRICS.addAll(PROMPT_SUBSET_METRICS);
        INTEGER_METRICS.addAll(COMPLETION_SUBSET_METRICS);
    }

    private TokenAccountingSpec() {}

    /**
     * Checks one span's {@code metrics} map and returns a human-readable description of every rule
     * it breaks. An empty list means the span's token accounting is self-consistent.
     *
     * @param metrics the span's brainstore {@code metrics} map; {@code null} or empty yields no
     *     violations
     * @param provider the span's {@code metadata.provider}, used for the Anthropic-specific
     *     single-representation rule; may be {@code null}
     */
    public static List<String> violations(
            @Nullable Map<String, Object> metrics, @Nullable String provider) {
        List<String> problems = new ArrayList<>();
        if (metrics == null || metrics.isEmpty()) {
            return problems;
        }

        // 1. Types and signs. A metric that fails here is excluded from the arithmetic below, so a
        // single bad value yields one clear violation instead of a cascade.
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String name : INTEGER_METRICS) {
            Object raw = metrics.get(name);
            if (raw == null) {
                continue;
            }
            Long value = asIntegralLong(raw);
            if (value == null) {
                problems.add(
                        String.format(
                                "%s must be a non-negative integer but was %s (%s)",
                                name, raw, raw.getClass().getSimpleName()));
                continue;
            }
            if (value < 0) {
                problems.add(String.format("%s must be non-negative but was %d", name, value));
                continue;
            }
            counts.put(name, value);
        }

        // Non-integral metrics have their own, looser contract: finite and non-negative.
        checkFiniteNonNegative(metrics, "time_to_first_token", problems);
        checkFiniteNonNegative(metrics, "estimated_cost", problems);

        Long prompt = counts.get(PROMPT);
        Long completion = counts.get(COMPLETION);
        Long total = counts.get(TOTAL);

        // 2. Totals. Only checked when all three are known — embedding spans legitimately omit
        // completion_tokens, and a provider may report a total without a breakdown.
        if (prompt != null && completion != null && total != null && prompt + completion != total) {
            problems.add(
                    String.format(
                            "tokens (%d) must equal prompt_tokens (%d) + completion_tokens (%d) ="
                                    + " %d",
                            total, prompt, completion, prompt + completion));
        }

        // 3. Detail metrics are subsets of their parent total, not additions to it.
        for (String name : PROMPT_SUBSET_METRICS) {
            checkSubset(counts, name, prompt, PROMPT, problems);
        }
        for (String name : COMPLETION_SUBSET_METRICS) {
            checkSubset(counts, name, completion, COMPLETION, problems);
        }

        // 4. Cache reads and cache writes are disjoint slices of the prompt, so together they still
        // have to fit inside prompt_tokens. Providers whose native input count excludes cache
        // tokens (Anthropic, Bedrock) break this the moment that raw count is used as-is.
        long cached = counts.getOrDefault(CACHED, 0L);
        long effectiveCreation = effectiveCacheCreationTokens(counts);
        if (prompt != null && cached + effectiveCreation > prompt) {
            problems.add(
                    String.format(
                            "prompt_cached_tokens (%d) + effective cache-creation tokens (%d) ="
                                    + " %d exceeds prompt_tokens (%d). Cache tokens are a subset of"
                                    + " prompt_tokens, so a provider that reports its input count"
                                    + " exclusive of cache tokens must have them rolled back in.",
                            cached, effectiveCreation, cached + effectiveCreation, prompt));
        }

        // 5. Anthropic must pick one cache-creation representation.
        if ("anthropic".equals(provider)
                && counts.containsKey(CACHE_CREATE)
                && (counts.containsKey(CACHE_CREATE_5M) || counts.containsKey(CACHE_CREATE_1H))) {
            problems.add(
                    "anthropic spans must emit either prompt_cache_creation_tokens or the per-TTL"
                            + " breakdown, not both");
        }

        return problems;
    }

    /**
     * Recursively checks every LLM span in a brainstore span tree, failing the calling test with
     * every violation found across the whole tree.
     *
     * <p>Non-LLM spans are skipped: tool spans and the like carry no token metrics.
     *
     * @param spans top-level spans, each optionally carrying nested {@code child_spans}
     * @param context spec display name, used to make failures traceable
     */
    public static void assertSpanTree(@Nullable List<Map<String, Object>> spans, String context) {
        List<String> problems = new ArrayList<>();
        collect(spans, context, problems);
        if (!problems.isEmpty()) {
            org.junit.jupiter.api.Assertions.fail(
                    "token accounting violations:\n  " + String.join("\n  ", problems));
        }
    }

    @SuppressWarnings("unchecked")
    private static void collect(
            @Nullable List<Map<String, Object>> spans, String context, List<String> problems) {
        if (spans == null) {
            return;
        }
        for (int i = 0; i < spans.size(); i++) {
            Map<String, Object> span = spans.get(i);
            if (span == null) {
                continue;
            }
            String spanContext = context + "[" + i + "]";
            if (isLlmSpan(span)) {
                Map<String, Object> metrics =
                        span.get("metrics") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
                for (String problem : violations(metrics, providerOf(span))) {
                    problems.add(spanContext + " (" + spanName(span) + "): " + problem);
                }
            }
            if (span.get("child_spans") instanceof List<?> children) {
                collect((List<Map<String, Object>>) children, spanContext, problems);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean isLlmSpan(Map<String, Object> span) {
        return span.get("span_attributes") instanceof Map<?, ?> attrs
                && "llm".equals(((Map<String, Object>) attrs).get("type"));
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static String providerOf(Map<String, Object> span) {
        if (span.get("metadata") instanceof Map<?, ?> metadata) {
            Object provider = ((Map<String, Object>) metadata).get("provider");
            return provider instanceof String s ? s : null;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String spanName(Map<String, Object> span) {
        Object name = span.get("name");
        if (name instanceof String s) {
            return s;
        }
        // Spans fetched from BTQL carry the name under span_attributes rather than at the top
        // level, so fall back there before giving up.
        if (span.get("span_attributes") instanceof Map<?, ?> attrs) {
            Object attrName = ((Map<String, Object>) attrs).get("name");
            if (attrName instanceof String s) {
                return s;
            }
        }
        return "unnamed";
    }

    /**
     * Cache-creation tokens actually charged against the prompt. The per-TTL metrics are an
     * alternative representation of the aggregate rather than additional tokens, so the two are
     * reconciled with {@code max} — matching the server's own cost formula.
     */
    private static long effectiveCacheCreationTokens(Map<String, Long> counts) {
        long aggregate = counts.getOrDefault(CACHE_CREATE, 0L);
        long split =
                counts.getOrDefault(CACHE_CREATE_5M, 0L) + counts.getOrDefault(CACHE_CREATE_1H, 0L);
        return Math.max(aggregate, split);
    }

    private static void checkSubset(
            Map<String, Long> counts,
            String name,
            @Nullable Long parent,
            String parentName,
            List<String> problems) {
        Long value = counts.get(name);
        if (value == null || parent == null || value <= parent) {
            return;
        }
        problems.add(
                String.format(
                        "%s (%d) must not exceed %s (%d) — it is a subset of it, not an addition",
                        name, value, parentName, parent));
    }

    private static void checkFiniteNonNegative(
            Map<String, Object> metrics, String name, List<String> problems) {
        Object raw = metrics.get(name);
        if (raw == null) {
            return;
        }
        if (!(raw instanceof Number n)) {
            problems.add(
                    String.format(
                            "%s must be a number but was %s (%s)",
                            name, raw, raw.getClass().getSimpleName()));
            return;
        }
        double value = n.doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            problems.add(String.format("%s must be finite but was %s", name, raw));
        } else if (value < 0) {
            problems.add(String.format("%s must be non-negative but was %s", name, raw));
        }
    }

    /**
     * Returns {@code raw} as a long when it holds an integral value, else {@code null}. A {@code
     * Double} carrying a whole number (how JSON {@code 5.0} may deserialize) is accepted; a genuine
     * fraction is not.
     */
    @Nullable
    private static Long asIntegralLong(Object raw) {
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short) {
            return ((Number) raw).longValue();
        }
        if (raw instanceof java.math.BigInteger b) {
            return b.longValue();
        }
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.floor(d)) {
                return null;
            }
            return (long) d;
        }
        return null;
    }
}
