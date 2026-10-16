package dev.braintrust.sdkspecimpl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the spec-assertion semantics that are not themselves specified by a YAML spec —
 * chiefly which actual/expected representation mismatches the validator is allowed to paper over.
 */
class SpanValidatorTest {

    private static void validate(Object actual, Object expected) {
        SpanValidator.validateValue(actual, expected, "test");
    }

    @Test
    void googleUsagePredicateRequiresReportedReasoningAndBalancedTotals() {
        var predicate = new SpecMatcher.FnMatcher("is_google_usage_metadata_normalized");
        Map<String, Object> valid =
                Map.of(
                        "prompt_tokens", 8,
                        "completion_tokens", 19,
                        "completion_reasoning_tokens", 12,
                        "tokens", 27);
        validate(valid, predicate);
        for (String required : valid.keySet()) {
            var missing = new HashMap<>(valid);
            missing.remove(required);
            assertThrows(AssertionError.class, () -> validate(missing, predicate), required);
        }
        for (Map<String, Object> changes :
                List.<Map<String, Object>>of(
                        Map.of("completion_tokens", -1),
                        Map.of("completion_reasoning_tokens", 0),
                        Map.of("completion_reasoning_tokens", 20),
                        Map.of("tokens", 26),
                        Map.of("tokens", Double.NaN),
                        Map.of("prompt_tokens", "8"),
                        Map.of("tool_use_tokens", 1))) {
            var invalid = new HashMap<>(valid);
            invalid.putAll(changes);
            assertThrows(
                    AssertionError.class, () -> validate(invalid, predicate), changes.toString());
        }
        assertThrows(AssertionError.class, () -> validate(null, predicate));
        assertThrows(AssertionError.class, () -> validate(List.of(), predicate));
        var nullToolUsage = new HashMap<>(valid);
        nullToolUsage.put("tool_use_tokens", null);
        validate(nullToolUsage, predicate);
    }

    @Test
    void collapsesResponsesApiTextContentPartsToTheStringTheSpecAsserts() {
        // langchain4j's OpenAiResponsesChatModel sends a user message's text as a content part,
        // while the spec asserts the plain-string form.
        assertDoesNotThrow(
                () -> validate(List.of(Map.of("type", "input_text", "text", "hello")), "hello"));
        assertDoesNotThrow(
                () -> validate(List.of(Map.of("type", "output_text", "text", "hello")), "hello"));
        // Multiple parts concatenate.
        assertDoesNotThrow(
                () ->
                        validate(
                                List.of(
                                        Map.of("type", "output_text", "text", "hel"),
                                        Map.of("type", "output_text", "text", "lo")),
                                "hello"));
    }

    @Test
    void collapsedContentStillHasToMatch() {
        assertThrows(
                AssertionError.class,
                () -> validate(List.of(Map.of("type", "input_text", "text", "hello")), "goodbye"));
    }

    @Test
    void doesNotCollapseAnthropicStyleTextBlocks() {
        // {type: text} is the Anthropic/Bedrock content-block shape (and the Chat Completions
        // content-part shape). Collapsing it would let a spec that asserts a plain string pass
        // against block-shaped content, silently weakening every such assertion.
        assertThrows(
                AssertionError.class,
                () -> validate(List.of(Map.of("type", "text", "text", "hello")), "hello"));
    }

    @Test
    void leavesNonTextPartListsAlone() {
        // A list carrying anything other than text parts is not a stringly-typed content value.
        assertThrows(
                AssertionError.class,
                () ->
                        validate(
                                List.of(
                                        Map.of("type", "input_text", "text", "describe this"),
                                        Map.of("type", "input_image", "image_url", "data:...")),
                                "describe this"));
        assertThrows(AssertionError.class, () -> validate(List.of("hello"), "hello"));
        assertThrows(AssertionError.class, () -> validate(List.of(), "hello"));
    }

    @Test
    void doesNotCollapseWhenTheSpecAssertsTheContentPartForm() {
        // The spec asserting the array form must still be compared structurally.
        assertDoesNotThrow(
                () ->
                        validate(
                                List.of(Map.of("type", "output_text", "text", "hello")),
                                List.of(Map.of("type", "output_text", "text", "hello"))));
        assertThrows(
                AssertionError.class,
                () ->
                        validate(
                                List.of(Map.of("type", "output_text", "text", "hello")),
                                List.of(Map.of("type", "output_text", "text", "goodbye"))));
    }
}
