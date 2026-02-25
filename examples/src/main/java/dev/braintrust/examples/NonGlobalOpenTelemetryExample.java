package dev.braintrust.examples;

import dev.braintrust.Braintrust;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.json.BraintrustJsonMapper;
import dev.braintrust.trace.BraintrustContext;
import dev.braintrust.trace.BraintrustTracing;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.util.HashMap;
import java.util.Map;

public class NonGlobalOpenTelemetryExample {
    public static void main(String[] args) throws Exception {
        var projectName = "andrew-misc";
        BraintrustConfig config =
                BraintrustConfig.of(
                        "BRAINTRUST_DEFAULT_PROJECT_NAME", projectName, "BRAINTRUST_DEBUG", "true");
        Braintrust braintrust = Braintrust.get(config);
        OpenTelemetry openTelemetry = braintrust.openTelemetryCreate(false);
        Tracer tracer = BraintrustTracing.getTracer(openTelemetry);

        Context featureContext =
                BraintrustContext.setParentInBaggage(
                        Context.current(), "project_name", projectName);
        Span span =
                tracer.spanBuilder("open-ai-prompt")
                        .setParent(featureContext)
                        .setSpanKind(SpanKind.SERVER)
                        .startSpan();

        span.setAttribute(
                "braintrust.span_attributes",
                String.format("{\"type\":\"llm\",\"name\":\"%s\"}", "open-ai-prompt"));

        String model = "test";
        String executionId = "abc";
        String envirnment = "local";

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("provider", "OpenAI");
        metadata.put("model", model);
        metadata.put("feature", "foo");
        metadata.put("env", envirnment);
        metadata.put("execution_id", executionId);
        span.setAttribute("braintrust.metadata", BraintrustJsonMapper.toJson(metadata));

        try (var ignored = span.makeCurrent()) {
            Thread.sleep(5);
        } finally {
            span.end();
        }
        var url =
                braintrust.projectUri()
                        + "/logs?r=%s&s=%s"
                                .formatted(
                                        span.getSpanContext().getTraceId(),
                                        span.getSpanContext().getSpanId());
        System.out.println("\n\n  Example complete! View your data in Braintrust: " + url);
    }
}
