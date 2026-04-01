package dev.braintrust.trace;

import io.opentelemetry.sdk.trace.ReadableSpan;
import java.util.List;

/**
 * A filter that decides whether a finalized span should be exported. Samplers are evaluated in
 * {@link BraintrustSpanProcessor#onEnd} after all attributes have been set on the span.
 */
interface BraintrustSampler {
    /** Returns {@code true} if the span should be exported, {@code false} to discard it. */
    boolean sample(ReadableSpan span);

    /** Keeps only spans that have at least one attribute with a known AI instrumentation prefix. */
    class FilterAISpans implements BraintrustSampler {
        static final List<String> AI_ATTR_PREFIXES =
                List.of("gen_ai.", "braintrust.", "llm.", "ai.", "traceloop.");

        @Override
        public boolean sample(ReadableSpan span) {
            for (var key : span.getAttributes().asMap().keySet()) {
                var keyName = key.getKey();
                // Skip internal attributes injected by the span processor itself
                if (keyName.equals(BraintrustTracing.PARENT_KEY)) {
                    continue;
                }
                for (var prefix : AI_ATTR_PREFIXES) {
                    if (keyName.startsWith(prefix)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
