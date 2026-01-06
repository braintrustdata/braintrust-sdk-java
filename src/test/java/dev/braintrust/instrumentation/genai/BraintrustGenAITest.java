package dev.braintrust.instrumentation.genai;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import dev.braintrust.TestHarness;
import io.opentelemetry.api.common.AttributeKey;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BraintrustGenAITest {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private TestHarness testHarness;

    @BeforeEach
    void beforeEach() {
        testHarness = TestHarness.setup();
    }

    @Test
    @SneakyThrows
    void testWrapGemini() {
        // Create Gemini client using VCR
        HttpOptions httpOptions =
                HttpOptions.builder().baseUrl(testHarness.googleBaseUrl()).build();

        // Wrap with Braintrust instrumentation
        var geminiClient =
                BraintrustGenAI.wrap(
                        testHarness.openTelemetry(),
                        new Client.Builder()
                                .apiKey(testHarness.googleApiKey())
                                .httpOptions(httpOptions));

        var config = GenerateContentConfig.builder().temperature(0.0f).maxOutputTokens(50).build();

        var response =
                geminiClient.models.generateContent(
                        "gemini-2.0-flash-lite", "What is the capital of France?", config);

        // Verify the response
        assertNotNull(response);
        assertNotNull(response.text());

        // Verify spans were exported
        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size(), "Expected exactly 1 span to be created");
        var span = spans.get(0);

        // Verify span name matches the operation
        assertEquals("generate_content", span.getName());

        // Verify braintrust.metadata contains provider and model
        String metadataJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(metadataJson, "braintrust.metadata should be set");
        var metadata = JSON_MAPPER.readTree(metadataJson);
        assertEquals("gemini", metadata.get("provider").asText());
        assertEquals("gemini-2.0-flash-lite", metadata.get("model").asText());
        assertEquals(0.0, metadata.get("temperature").asDouble());
        assertEquals(50, metadata.get("maxOutputTokens").asInt());

        // Verify braintrust.metrics contains token counts
        String metricsJson = span.getAttributes().get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson, "braintrust.metrics should be set");
        var metrics = JSON_MAPPER.readTree(metricsJson);
        assertTrue(metrics.get("prompt_tokens").asInt() > 0, "prompt_tokens should be > 0");
        assertTrue(metrics.get("completion_tokens").asInt() > 0, "completion_tokens should be > 0");
        assertTrue(metrics.get("tokens").asInt() > 0, "tokens should be > 0");

        // Verify braintrust.span_attributes marks this as an LLM span
        String spanAttributesJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.span_attributes"));
        assertNotNull(spanAttributesJson, "braintrust.span_attributes should be set");
        var spanAttributes = JSON_MAPPER.readTree(spanAttributesJson);
        assertEquals("llm", spanAttributes.get("type").asText());

        // Verify braintrust.input_json contains the request
        String inputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.input_json"));
        assertNotNull(inputJson, "braintrust.input_json should be set");
        var input = JSON_MAPPER.readTree(inputJson);
        assertEquals("gemini-2.0-flash-lite", input.get("model").asText());
        assertTrue(input.has("contents"), "input should have contents");
        assertTrue(input.has("config"), "input should have config");

        // Verify braintrust.output_json contains the response
        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson, "braintrust.output_json should be set");
        var output = JSON_MAPPER.readTree(outputJson);
        assertTrue(output.has("candidates"), "output should have candidates");
        assertNotNull(output.get("candidates").get(0).get("finishReason"));
        assertNotNull(
                output.get("candidates").get(0).get("content").get("parts").get(0).get("text"));
    }

    @Test
    @SneakyThrows
    void testWrapGeminiAsync() {
        // Create Gemini client using VCR
        HttpOptions httpOptions =
                HttpOptions.builder().baseUrl(testHarness.googleBaseUrl()).build();

        // Wrap with Braintrust instrumentation
        var geminiClient =
                BraintrustGenAI.wrap(
                        testHarness.openTelemetry(),
                        new Client.Builder()
                                .apiKey(testHarness.googleApiKey())
                                .httpOptions(httpOptions));

        var config = GenerateContentConfig.builder().temperature(0.0f).maxOutputTokens(50).build();

        // Call async method and wait for completion
        var responseFuture =
                geminiClient.async.models.generateContent(
                        "gemini-2.0-flash-lite", "What is the capital of Germany?", config);

        var response = responseFuture.get(); // Wait for completion

        // Verify the response
        assertNotNull(response);
        assertNotNull(response.text());

        // Verify spans were exported
        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size(), "Expected exactly 1 span to be created");
        var span = spans.get(0);

        // Verify span name matches the operation
        assertEquals("generate_content", span.getName());

        // Verify braintrust.metadata contains provider and model
        String metadataJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(metadataJson, "braintrust.metadata should be set");
        var metadata = JSON_MAPPER.readTree(metadataJson);
        assertEquals("gemini", metadata.get("provider").asText());
        assertEquals("gemini-2.0-flash-lite", metadata.get("model").asText());
        assertEquals(0.0, metadata.get("temperature").asDouble());
        assertEquals(50, metadata.get("maxOutputTokens").asInt());

        // Verify braintrust.metrics contains token counts
        String metricsJson = span.getAttributes().get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson, "braintrust.metrics should be set");
        var metrics = JSON_MAPPER.readTree(metricsJson);
        assertTrue(metrics.get("prompt_tokens").asInt() > 0, "prompt_tokens should be > 0");
        assertTrue(metrics.get("completion_tokens").asInt() > 0, "completion_tokens should be > 0");
        assertTrue(metrics.get("tokens").asInt() > 0, "tokens should be > 0");

        // Verify braintrust.span_attributes marks this as an LLM span
        String spanAttributesJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.span_attributes"));
        assertNotNull(spanAttributesJson, "braintrust.span_attributes should be set");
        var spanAttributes = JSON_MAPPER.readTree(spanAttributesJson);
        assertEquals("llm", spanAttributes.get("type").asText());

        // Verify braintrust.input_json contains the request
        String inputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.input_json"));
        assertNotNull(inputJson, "braintrust.input_json should be set");
        var input = JSON_MAPPER.readTree(inputJson);
        assertEquals("gemini-2.0-flash-lite", input.get("model").asText());
        assertTrue(input.has("contents"), "input should have contents");
        assertTrue(input.has("config"), "input should have config");

        // Verify braintrust.output_json contains the response
        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson, "braintrust.output_json should be set");
        var output = JSON_MAPPER.readTree(outputJson);
        assertTrue(output.has("candidates"), "output should have candidates");
        assertNotNull(output.get("candidates").get(0).get("finishReason"));
        assertNotNull(
                output.get("candidates").get(0).get("content").get("parts").get(0).get("text"));
    }
}
