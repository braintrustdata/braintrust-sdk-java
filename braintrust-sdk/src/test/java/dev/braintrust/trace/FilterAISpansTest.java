package dev.braintrust.trace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;

public class FilterAISpansTest {

    private final BraintrustSampler.FilterAISpans filter = new BraintrustSampler.FilterAISpans();
    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build();

    private ReadableSpan createSpan(String name, String attrKey, String attrValue) {
        var tracer = tracerProvider.get("test");
        var spanBuilder = tracer.spanBuilder(name);
        if (attrKey != null) {
            spanBuilder.setAttribute(AttributeKey.stringKey(attrKey), attrValue);
        }
        var span = spanBuilder.startSpan();
        span.end();
        return (ReadableSpan) span;
    }

    @Test
    public void shouldKeepSpanWithMatchingPrefix() {
        var span = createSpan("llm-call", "gen_ai.model", "gpt-4");
        assertTrue(filter.sample(span));
    }

    @Test
    public void shouldDropSpanWithNoMatchingAttributes() {
        var span = createSpan("http-call", "http.method", "GET");
        assertFalse(filter.sample(span));
    }

    @Test
    public void shouldDropSpanWithEmptyAttributes() {
        var span = createSpan("empty-span", null, null);
        assertFalse(filter.sample(span));
    }

    @Test
    public void shouldNotMatchSubstring() {
        var span = createSpan("not-ai", "zbraintrust.foo", "bar");
        assertFalse(filter.sample(span));
    }
}
