package dev.braintrust.instrumentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

class InstrumentationSemConvTest {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final AttributeKey<String> BRAINTRUST_METRICS =
            AttributeKey.stringKey("braintrust.metrics");

    @Test
    @SneakyThrows
    void mapsOpenAIChatCompletionsCachedTokens() {
        JsonNode metrics =
                metricsForOpenAIResponse(
                        """
                        {
                          "choices": [],
                          "usage": {
                            "prompt_tokens": 100,
                            "completion_tokens": 10,
                            "total_tokens": 110,
                            "prompt_tokens_details": {
                              "cached_tokens": 80
                            }
                          }
                        }
                        """);

        assertEquals(100, metrics.get("prompt_tokens").asInt());
        assertEquals(10, metrics.get("completion_tokens").asInt());
        assertEquals(110, metrics.get("tokens").asInt());
        assertEquals(80, metrics.get("prompt_cached_tokens").asInt());
    }

    @Test
    @SneakyThrows
    void mapsOpenAIResponsesCachedTokens() {
        JsonNode metrics =
                metricsForOpenAIResponse(
                        """
                        {
                          "output": [],
                          "usage": {
                            "input_tokens": 100,
                            "input_tokens_details": {
                              "cached_tokens": 80
                            },
                            "output_tokens": 10,
                            "output_tokens_details": {
                              "reasoning_tokens": 3
                            },
                            "total_tokens": 110
                          }
                        }
                        """);

        assertEquals(100, metrics.get("prompt_tokens").asInt());
        assertEquals(10, metrics.get("completion_tokens").asInt());
        assertEquals(110, metrics.get("tokens").asInt());
        assertEquals(80, metrics.get("prompt_cached_tokens").asInt());
        assertEquals(3, metrics.get("completion_reasoning_tokens").asInt());
    }

    @SneakyThrows
    private static JsonNode metricsForOpenAIResponse(String responseJson) {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        try {
            OpenTelemetrySdk openTelemetry =
                    OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
            var span = openTelemetry.getTracer("test").spanBuilder("llm").startSpan();
            try {
                InstrumentationSemConv.tagLLMSpanResponse(
                        span, InstrumentationSemConv.PROVIDER_NAME_OPENAI, responseJson);
            } finally {
                span.end();
            }

            String metricsJson =
                    exporter.getFinishedSpanItems().get(0).getAttributes().get(BRAINTRUST_METRICS);
            return JSON_MAPPER.readTree(metricsJson);
        } finally {
            tracerProvider.close();
        }
    }
}
