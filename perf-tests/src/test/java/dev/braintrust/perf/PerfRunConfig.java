package dev.braintrust.perf;

/**
 * Describes the configuration for a single performance test run.
 *
 * <p>Each field controls the shape of the multi-turn conversation that will be generated and
 * exported. Add new fields here as you add new scenarios (e.g. tool use, streaming, etc.).
 *
 * @param name human-readable label for this configuration
 * @param turns number of conversational turns (user messages sent to the AI service)
 * @param includeImageAttachment whether to include an image attachment in the first user message
 */
public record PerfRunConfig(String name, int turns, boolean includeImageAttachment) {

    /** A multi-turn conversation with an image attachment on the first turn. */
    public static PerfRunConfig multiTurnWithAttachment() {
        return new PerfRunConfig("multi-turn-with-attachment", 10, true);
    }

    /** A multi-turn conversation with text only (no attachments). */
    public static PerfRunConfig multiTurnTextOnly() {
        return new PerfRunConfig("multi-turn-text-only", 3, false);
    }
}
