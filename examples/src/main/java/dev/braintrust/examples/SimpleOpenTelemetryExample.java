package dev.braintrust.examples;

import dev.braintrust.Braintrust;

public class SimpleOpenTelemetryExample {
    public static void main(String[] args) throws Exception {
        var braintrust = Braintrust.get();
        var openTelemetry = braintrust.openTelemetryCreate();
        var tracer = openTelemetry.getTracer("my-instrumentation");

        var span = tracer.spanBuilder("hello-java").startSpan();
        try (var ignored = span.makeCurrent()) {
            System.out.println("Performing simple operation...");
            span.setAttribute("some boolean attribute", true);
            Thread.sleep(100); // Not required. This is just to make the span look interesting
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
