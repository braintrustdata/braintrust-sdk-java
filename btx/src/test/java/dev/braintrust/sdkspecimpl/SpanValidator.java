package dev.braintrust.sdkspecimpl;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    @SuppressWarnings("unchecked")
    private static void validateSpan(
            Map<String, Object> actual, Map<String, Object> expected, String context) {
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            String field = entry.getKey();
            Object expectedValue = entry.getValue();
            Object actualValue = actual.get(field);
            validateValue(actualValue, expectedValue, context + "." + field);
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
            if (!valuesEqual(actual, expected)) {
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
