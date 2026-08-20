package dev.braintrust.sdkspecimpl;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a list of brainstore spans against the {@code expected_brainstore_spans} structure from
 * a YAML spec.
 *
 * <p>Brainstore spans have the following top-level fields that the spec may assert on:
 *
 * <ul>
 *   <li>{@code metrics} — token counts, timing
 *   <li>{@code metadata} — model, provider
 *   <li>{@code span_attributes} — type, name
 *   <li>{@code input} — input messages
 *   <li>{@code output} — output choices / content
 *   <li>{@code child_spans} — a nested list of child-span assertions, validated recursively against
 *       the span's actual children (built by {@link SpanFetcher#buildSpanTree}) by {@link
 *       #validateChildSpans}, so every level honors the same per-span rules as the top level. A
 *       span that omits it asserts nothing about its children.
 * </ul>
 *
 * <p>Spans arrive here already in brainstore format, produced either by {@link SpanConverter}
 * (REPLAY mode) or {@link SpanFetcher} (RECORD / OFF mode).
 */
public class SpanValidator {

    /**
     * Validate that the brainstore spans match the expected structures from the spec.
     *
     * @param brainstoreSpans spans in brainstore format (child LLM spans only, no root wrapper)
     * @param expectedSpans the {@code expected_brainstore_spans} list from the YAML spec
     * @param specName display name for error messages
     */
    public static void validate(
            List<Map<String, Object>> brainstoreSpans,
            List<Map<String, Object>> expectedSpans,
            String specName) {

        if (brainstoreSpans.size() < expectedSpans.size()) {
            fail(
                    String.format(
                            "[%s] Expected at least %d brainstore spans but got %d",
                            specName, expectedSpans.size(), brainstoreSpans.size()));
        }

        for (int i = 0; i < expectedSpans.size(); i++) {
            validateSpan(brainstoreSpans.get(i), expectedSpans.get(i), specName + "[" + i + "]");
        }
    }

    /**
     * Top-level expected-span fields that are not validated (yet).
     *
     * <p>{@code context} (span-origin provenance, added in spec v0.0.8) is skipped because the Java
     * SDK does not emit {@code context.span_origin} yet, and the backend's OTLP ingestion does not
     * extract it from {@code braintrust.context_json} / {@code braintrust.sdk.*} attributes. TODO:
     * remove this skip once span-origin support is implemented.
     */
    private static final java.util.Set<String> SKIPPED_FIELDS = java.util.Set.of("context");

    @SuppressWarnings("unchecked")
    private static void validateSpan(
            Map<String, Object> actual, Map<String, Object> expected, String context) {
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            String field = entry.getKey();
            if (SKIPPED_FIELDS.contains(field)) {
                continue;
            }
            Object expectedValue = entry.getValue();
            Object actualValue = actual.get(field);
            if (CHILD_SPANS_FIELD.equals(field)) {
                validateChildSpans(actualValue, expectedValue, context + "." + field);
                continue;
            }
            validateValue(actualValue, expectedValue, context + "." + field);
        }
    }

    /** The nested child-span assertion list a spec may attach to any span. */
    private static final String CHILD_SPANS_FIELD = "child_spans";

    /**
     * Validate nested child-span assertions against the span's actual children (built by {@link
     * SpanFetcher#buildSpanTree}). Each child is routed back through {@link #validateSpan} rather
     * than the generic map recursion so that every level of the tree honors {@link #SKIPPED_FIELDS}
     * — a {@code context:} block on a child span must be skipped just like one on a top-level span,
     * otherwise the child assertion fails on a field the Java SDK does not emit yet. As with the
     * top-level list, extra actual children are allowed; order is significant.
     */
    @SuppressWarnings("unchecked")
    private static void validateChildSpans(Object actual, Object expected, String context) {
        if (!(expected instanceof List)) {
            fail(context + ": spec must supply a list of child-span assertions, got " + expected);
        }
        List<Object> expList = (List<Object>) expected;
        if (!(actual instanceof List)) {
            fail(
                    String.format(
                            "%s: expected a List but got %s (value: %s)",
                            context,
                            actual == null ? "null" : actual.getClass().getSimpleName(),
                            actual));
        }
        List<Object> actualList = (List<Object>) actual;
        if (actualList.size() < expList.size()) {
            fail(
                    String.format(
                            "%s: expected at least %d child spans but got %d. actual=%s",
                            context, expList.size(), actualList.size(), actualList));
        }
        for (int i = 0; i < expList.size(); i++) {
            String childCtx = context + "[" + i + "]";
            Object actualChild = actualList.get(i);
            Object expectedChild = expList.get(i);
            if (!(actualChild instanceof Map) || !(expectedChild instanceof Map)) {
                fail(childCtx + ": child spans must be maps, got " + actualChild);
            }
            validateSpan(
                    (Map<String, Object>) actualChild,
                    (Map<String, Object>) expectedChild,
                    childCtx);
        }
    }

    private static void validateMap(
            Map<String, Object> actual, Map<String, Object> expected, String context) {
        if (actual == null) {
            fail(context + ": expected a Map but got null");
        }
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            String key = entry.getKey();
            Object actualVal = actual.get(key);
            validateValue(actualVal, entry.getValue(), context + "." + key);
        }
    }

    @SuppressWarnings("unchecked")
    static void validateValue(Object actual, Object expected, String context) {
        if (expected instanceof SpecMatcher) {
            assertMatcher(actual, (SpecMatcher) expected, context);
        } else if (expected instanceof Map) {
            if (!(actual instanceof Map)) {
                fail(
                        String.format(
                                "%s: expected a Map but got %s (value: %s)",
                                context,
                                actual == null ? "null" : actual.getClass().getSimpleName(),
                                actual));
            }
            validateMap((Map<String, Object>) actual, (Map<String, Object>) expected, context);
        } else if (expected instanceof List) {
            List<Object> expList = (List<Object>) expected;
            if (!(actual instanceof List)) {
                // output may be a single Map (e.g. Anthropic returns an object, not an array)
                if (expList.size() == 1 && actual instanceof Map) {
                    validateValue(actual, expList.get(0), context + "[0]");
                    return;
                }
                fail(
                        String.format(
                                "%s: expected a List but got %s (value: %s)",
                                context,
                                actual == null ? "null" : actual.getClass().getSimpleName(),
                                actual));
            }
            List<Object> actualList = (List<Object>) actual;
            if (actualList.size() < expList.size()) {
                fail(
                        String.format(
                                "%s: expected at least %d items but got %d. actual=%s",
                                context, expList.size(), actualList.size(), actualList));
            }
            for (int i = 0; i < expList.size(); i++) {
                validateValue(actualList.get(i), expList.get(i), context + "[" + i + "]");
            }
        } else {
            // scalar: null expected means "don't care"
            if (expected == null) return;
            // A message's text content is legitimately represented either as a plain string or,
            // in the OpenAI Responses API, as a single text content part
            // ([{type: input_text|output_text, text: "..."}]). When the spec asserts the string
            // form but an SDK (e.g. langchain4j's OpenAiResponsesChatModel) emits the content-part
            // form, collapse it so the two representations compare equal.
            Object normalizedActual =
                    expected instanceof String ? collapseTextContentParts(actual) : actual;
            if (!valuesEqual(normalizedActual, expected)) {
                fail(
                        String.format(
                                "%s: expected %s (%s) but got %s (%s)",
                                context,
                                expected,
                                expected.getClass().getSimpleName(),
                                actual,
                                actual == null ? "null" : actual.getClass().getSimpleName()));
            }
        }
    }

    /**
     * The OpenAI Responses API text content-part types. Deliberately excludes the bare {@code text}
     * type used by Anthropic/Bedrock content blocks (and by Chat Completions parts): collapsing
     * those would silently weaken any spec that asserts a provider's block-shaped content against a
     * plain string.
     */
    private static final Set<String> RESPONSES_TEXT_PART_TYPES =
            Set.of("input_text", "output_text");

    /**
     * Collapses an OpenAI Responses text content-part list ({@code [{type: input_text|output_text,
     * text: "..."}]}) into its concatenated text. Returns the input unchanged if it is not such a
     * list.
     */
    private static Object collapseTextContentParts(Object actual) {
        if (!(actual instanceof List<?> parts) || parts.isEmpty()) {
            return actual;
        }
        StringBuilder text = new StringBuilder();
        for (Object part : parts) {
            if (!(part instanceof Map<?, ?> map)
                    || !(map.get("text") instanceof String partText)
                    || !RESPONSES_TEXT_PART_TYPES.contains(map.get("type"))) {
                return actual;
            }
            text.append(partText);
        }
        return text.toString();
    }

    private static void assertMatcher(Object actual, SpecMatcher matcher, String context) {
        if (matcher instanceof SpecMatcher.FnMatcher) {
            assertFnMatcher(actual, (SpecMatcher.FnMatcher) matcher, context);
        } else if (matcher instanceof SpecMatcher.StartsWithMatcher) {
            SpecMatcher.StartsWithMatcher sw = (SpecMatcher.StartsWithMatcher) matcher;
            if (!(actual instanceof String)) {
                fail(
                        String.format(
                                "%s: starts_with(%s): expected a String but got %s (value: %s)",
                                context,
                                sw.prefix(),
                                actual == null ? "null" : actual.getClass().getSimpleName(),
                                actual));
            }
            if (!((String) actual).startsWith(sw.prefix())) {
                fail(
                        String.format(
                                "%s: starts_with(%s): value '%s' does not start with prefix",
                                context, sw.prefix(), actual));
            }
        } else if (matcher instanceof SpecMatcher.OrMatcher) {
            SpecMatcher.OrMatcher or = (SpecMatcher.OrMatcher) matcher;
            List<String> failures = new ArrayList<>();
            for (Object alternative : or.alternatives()) {
                try {
                    validateValue(actual, alternative, context);
                    return; // matched
                } catch (AssertionError e) {
                    failures.add(e.getMessage());
                }
            }
            fail(
                    String.format(
                            "%s: !or: no alternative matched. Failures:\n  %s\nActual: %s",
                            context, String.join("\n  ", failures), actual));
        } else {
            fail(context + ": unknown SpecMatcher: " + matcher.getClass().getSimpleName());
        }
    }

    private static void assertFnMatcher(Object actual, SpecMatcher.FnMatcher fn, String context) {
        switch (fn.name()) {
            case "is_non_negative_number" -> {
                if (!(actual instanceof Number)) {
                    fail(
                            String.format(
                                    "%s: is_non_negative_number: expected a Number but got %s"
                                            + " (value: %s)",
                                    context,
                                    actual == null ? "null" : actual.getClass().getSimpleName(),
                                    actual));
                }
                double v = ((Number) actual).doubleValue();
                if (v < 0) {
                    fail(
                            String.format(
                                    "%s: is_non_negative_number: value %s is negative",
                                    context, v));
                }
            }
            case "is_positive_number" -> {
                if (!(actual instanceof Number)) {
                    fail(
                            String.format(
                                    "%s: is_positive_number: expected a Number but got %s"
                                            + " (value: %s)",
                                    context,
                                    actual == null ? "null" : actual.getClass().getSimpleName(),
                                    actual));
                }
                double v = ((Number) actual).doubleValue();
                if (v <= 0) {
                    fail(
                            String.format(
                                    "%s: is_positive_number: value %s is not positive",
                                    context, v));
                }
            }
            case "undefined_or_null" -> {
                if (actual != null) {
                    fail(
                            String.format(
                                    "%s: undefined_or_null: expected null but got %s (value: %s)",
                                    context, actual.getClass().getSimpleName(), actual));
                }
            }
            case "is_non_empty_string" -> {
                if (!(actual instanceof String) || ((String) actual).isEmpty()) {
                    fail(
                            String.format(
                                    "%s: is_non_empty_string: expected non-empty String but got"
                                            + " %s (value: %s)",
                                    context,
                                    actual == null ? "null" : actual.getClass().getSimpleName(),
                                    actual));
                }
            }
            case "is_reasoning_message" -> {
                // A reasoning summary is a list of {type: "summary_text", text: "..."} objects.
                // An empty list is also acceptable (reasoning may not always occur).
                if (actual == null) {
                    fail(context + ": is_reasoning_message: value is null");
                }
                if (actual instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> items = (List<Object>) actual;
                    for (Object item : items) {
                        if (!(item instanceof Map)) {
                            fail(
                                    context
                                            + ": is_reasoning_message: list item is not a Map:"
                                            + " "
                                            + item);
                        }
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) item;
                        if (!"summary_text".equals(m.get("type"))) {
                            fail(
                                    context
                                            + ": is_reasoning_message: item type is not"
                                            + " 'summary_text': "
                                            + m.get("type"));
                        }
                        Object text = m.get("text");
                        if (!(text instanceof String) || ((String) text).isBlank()) {
                            fail(context + ": is_reasoning_message: item text is empty: " + text);
                        }
                    }
                } else if (actual instanceof String && ((String) actual).isEmpty()) {
                    fail(context + ": is_reasoning_message: value is empty string");
                }
                // non-list, non-empty value is acceptable (e.g. a non-null string)
            }
            default -> {
                // Python lambda-style predicates: just assert non-null / non-empty
                if (actual == null) {
                    fail(String.format("%s: fn(%s): value is null", context, fn.name()));
                }
                if (actual instanceof String && ((String) actual).isEmpty()) {
                    fail(String.format("%s: fn(%s): value is empty string", context, fn.name()));
                }
            }
        }
    }

    private static boolean valuesEqual(Object actual, Object expected) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;
        if (actual instanceof Number && expected instanceof Number) {
            return (((Number) actual).doubleValue() - ((Number) expected).doubleValue()) < 0.000001;
        }
        return actual.equals(expected);
    }
}
