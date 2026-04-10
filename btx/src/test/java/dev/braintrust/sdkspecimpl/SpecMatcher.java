package dev.braintrust.sdkspecimpl;

import java.util.List;

/**
 * Marker interface for custom YAML matchers used in expected_brainstore_spans assertions.
 *
 * <p>These correspond to the custom tags in the spec YAML files:
 *
 * <ul>
 *   <li>{@code !fn <name>} - a named predicate (e.g. {@code is_non_negative_number})
 *   <li>{@code !starts_with "prefix"} - asserts the value starts with a string
 *   <li>{@code !or [...]} - asserts the value matches at least one of a list of structures
 * </ul>
 */
public interface SpecMatcher {

    /**
     * A named predicate matcher. The name maps to one of the well-known predicate functions defined
     * in the spec.
     *
     * <p>Supported names:
     *
     * <ul>
     *   <li>{@code is_non_negative_number} - value is a number >= 0
     *   <li>{@code is_non_empty_string} - value is a non-empty string
     *   <li>{@code is_reasoning_message} - value is a non-null object (reasoning summary)
     *   <li>Any string starting with {@code lambda} - treated as a loose "truthy" check (we can't
     *       evaluate Python lambdas; we just assert the value is non-null/non-empty)
     * </ul>
     */
    record FnMatcher(String name) implements SpecMatcher {}

    /** Asserts the string value starts with the given prefix. */
    record StartsWithMatcher(String prefix) implements SpecMatcher {}

    /**
     * Asserts the value matches at least one of the given alternatives. Each alternative is a plain
     * Map/List structure (or another SpecMatcher).
     */
    record OrMatcher(List<Object> alternatives) implements SpecMatcher {}
}
