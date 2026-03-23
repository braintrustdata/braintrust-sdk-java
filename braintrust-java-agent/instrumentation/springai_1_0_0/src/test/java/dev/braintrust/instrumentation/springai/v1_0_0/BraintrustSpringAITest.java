package dev.braintrust.instrumentation.springai.v1_0_0;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.TestHarness;
import dev.braintrust.instrumentation.Instrumenter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lombok.SneakyThrows;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

public class BraintrustSpringAITest {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @BeforeAll
    public static void beforeAll() {
        var instrumentation = ByteBuddyAgent.install();
        Instrumenter.install(instrumentation, BraintrustSpringAITest.class.getClassLoader());
    }

    private TestHarness testHarness;

    @BeforeEach
    void beforeEach() {
        testHarness = TestHarness.setup();
    }

    /**
     * Spring AI's OpenAiApi appends "/v1/chat/completions" itself, so it expects the base URL
     * without the "/v1" suffix that TestHarness.openAiBaseUrl() includes.
     */
    private String springAiBaseUrl() {
        String url = testHarness.openAiBaseUrl();
        return url.endsWith("/v1") ? url.substring(0, url.length() - 3) : url;
    }

    @Test
    @SneakyThrows
    void testSpringAiChatModelCall() {
        var openAiApi =
                OpenAiApi.builder()
                        .baseUrl(springAiBaseUrl())
                        .apiKey(testHarness.openAiApiKey())
                        .build();

        var chatModel =
                OpenAiChatModel.builder()
                        .openAiApi(openAiApi)
                        .defaultOptions(
                                OpenAiChatOptions.builder()
                                        .model("gpt-4o-mini")
                                        .temperature(0.0)
                                        .maxTokens(50)
                                        .build())
                        .build();

        var response = chatModel.call(new Prompt("What is the capital of France?"));

        assertNotNull(response);
        assertNotNull(response.getResult());
        String text = response.getResult().getOutput().getText();
        assertNotNull(text);
        assertTrue(text.toLowerCase().contains("paris"), "Response should mention Paris: " + text);

        var spans = testHarness.awaitExportedSpans();
        // one LLM span from the observation handler
        assertEquals(1, spans.size());
        SpanData span = spans.get(0);

        // span name set by InstrumentationSemConv for chat completions
        assertEquals("Chat Completion", span.getName());

        // span_attributes: type=llm
        String spanAttributesJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.span_attributes"));
        assertNotNull(spanAttributesJson, "braintrust.span_attributes should be set");
        JsonNode spanAttributes = JSON_MAPPER.readTree(spanAttributesJson);
        assertEquals("llm", spanAttributes.get("type").asText());

        // metadata: provider=openai, model present
        String metadataJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(metadataJson, "braintrust.metadata should be set");
        JsonNode metadata = JSON_MAPPER.readTree(metadataJson);
        assertEquals("openai", metadata.get("provider").asText());
        assertTrue(
                metadata.get("model").asText().startsWith("gpt-4o-mini"),
                "model should start with gpt-4o-mini, got: " + metadata.get("model").asText());

        // input_json: messages array with user message
        String inputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.input_json"));
        assertNotNull(inputJson, "braintrust.input_json should be set");
        JsonNode inputMessages = JSON_MAPPER.readTree(inputJson);
        assertTrue(inputMessages.isArray(), "input_json should be an array");
        assertTrue(inputMessages.size() > 0, "input_json should have at least one message");
        assertEquals("user", inputMessages.get(0).get("role").asText());
        assertTrue(
                inputMessages.get(0).get("content").asText().contains("capital"),
                "input message should contain the prompt text");

        // output_json: choices array with assistant message
        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson, "braintrust.output_json should be set");
        JsonNode outputChoices = JSON_MAPPER.readTree(outputJson);
        assertTrue(outputChoices.isArray(), "output_json should be an array");
        assertEquals(1, outputChoices.size());
        JsonNode choice = outputChoices.get(0);
        assertEquals("assistant", choice.get("message").get("role").asText());
        assertTrue(
                choice.get("message").get("content").asText().toLowerCase().contains("paris"),
                "output message should mention Paris");
        assertNotNull(choice.get("finish_reason"), "finish_reason should be present");

        // metrics: token counts
        String metricsJson = span.getAttributes().get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson, "braintrust.metrics should be set");
        JsonNode metrics = JSON_MAPPER.readTree(metricsJson);
        assertTrue(metrics.has("prompt_tokens"), "prompt_tokens should be present");
        assertTrue(metrics.get("prompt_tokens").asInt() > 0, "prompt_tokens should be positive");
        assertTrue(metrics.has("completion_tokens"), "completion_tokens should be present");
        assertTrue(
                metrics.get("completion_tokens").asInt() > 0,
                "completion_tokens should be positive");
        // total_tokens (mapped from prompt_tokens + completion_tokens)
        if (metrics.has("prompt_tokens") && metrics.has("completion_tokens")) {
            assertTrue(
                    metrics.has("tokens"), "tokens should be present when prompt+completion are");
            assertTrue(metrics.get("tokens").asInt() > 0, "tokens should be positive");
        }
        assertFalse(
                metrics.has("time_to_first_token"),
                "time_to_first_token should not be present for non-streaming");
    }

    @Test
    @SneakyThrows
    void testSpringAiChatModelWithSystemMessage() {
        var openAiApi =
                OpenAiApi.builder()
                        .baseUrl(springAiBaseUrl())
                        .apiKey(testHarness.openAiApiKey())
                        .build();

        var chatModel =
                OpenAiChatModel.builder()
                        .openAiApi(openAiApi)
                        .defaultOptions(
                                OpenAiChatOptions.builder()
                                        .model("gpt-4o-mini")
                                        .temperature(0.0)
                                        .maxTokens(50)
                                        .build())
                        .build();

        var prompt =
                new Prompt(
                        List.of(
                                new SystemMessage("You are a helpful geography assistant."),
                                new UserMessage("What is the capital of France?")));

        var response = chatModel.call(prompt);

        assertNotNull(response);
        String text = response.getResult().getOutput().getText();
        assertTrue(text.toLowerCase().contains("paris"), "Response should mention Paris: " + text);

        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        SpanData span = spans.get(0);

        assertEquals("Chat Completion", span.getName());

        // input_json should contain both system and user messages
        String inputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.input_json"));
        assertNotNull(inputJson);
        JsonNode inputMessages = JSON_MAPPER.readTree(inputJson);
        assertTrue(inputMessages.isArray());
        assertEquals(2, inputMessages.size(), "Should have system + user messages");
        assertEquals("system", inputMessages.get(0).get("role").asText());
        assertEquals("user", inputMessages.get(1).get("role").asText());
    }

    /**
     * Spring AI's streaming path ({@code stream(Prompt)}) returns a {@code Flux<ChatResponse>}
     * where each chunk is a partial response. The observation fires once the flux completes with an
     * aggregated {@code ChatResponse} containing the full text and usage.
     *
     * <p>Note: Spring AI does not have a separate async/non-streaming path; {@code call()} is
     * always synchronous and {@code stream()} is always Reactor-based.
     */
    @Test
    @SneakyThrows
    void testSpringAiChatModelStream() {
        var openAiApi =
                OpenAiApi.builder()
                        .baseUrl(springAiBaseUrl())
                        .apiKey(testHarness.openAiApiKey())
                        .build();

        var chatModel =
                OpenAiChatModel.builder()
                        .openAiApi(openAiApi)
                        .defaultOptions(
                                OpenAiChatOptions.builder()
                                        .model("gpt-4o-mini")
                                        .temperature(0.0)
                                        .maxTokens(50)
                                        .build())
                        .build();

        // Collect streamed text chunks and capture the last (aggregated) response
        var fullText = new StringBuilder();
        var lastResponse = new AtomicReference<org.springframework.ai.chat.model.ChatResponse>();

        chatModel.stream(new Prompt("What is the capital of France?"))
                .doOnNext(
                        chunk -> {
                            if (chunk.getResult() != null
                                    && chunk.getResult().getOutput() != null
                                    && chunk.getResult().getOutput().getText() != null) {
                                fullText.append(chunk.getResult().getOutput().getText());
                            }
                            lastResponse.set(chunk);
                        })
                .blockLast();

        assertFalse(fullText.isEmpty(), "Should have received streaming chunks");
        assertTrue(
                fullText.toString().toLowerCase().contains("paris"),
                "Streamed response should mention Paris: " + fullText);

        // The observation span is completed on the Reactor scheduler thread; wait for it
        var spans = testHarness.awaitExportedSpans(1);
        assertEquals(1, spans.size());
        SpanData span = spans.get(0);

        assertEquals("Chat Completion", span.getName());

        // span_attributes: type=llm
        String spanAttributesJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.span_attributes"));
        assertNotNull(spanAttributesJson, "braintrust.span_attributes should be set");
        JsonNode spanAttributes = JSON_MAPPER.readTree(spanAttributesJson);
        assertEquals("llm", spanAttributes.get("type").asText());

        // metadata: provider=openai, model present
        String metadataJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(metadataJson, "braintrust.metadata should be set");
        JsonNode metadata = JSON_MAPPER.readTree(metadataJson);
        assertEquals("openai", metadata.get("provider").asText());
        assertTrue(
                metadata.get("model").asText().startsWith("gpt-4o-mini"),
                "model should start with gpt-4o-mini");

        // input_json: user message captured at observation start
        String inputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.input_json"));
        assertNotNull(inputJson, "braintrust.input_json should be set");
        JsonNode inputMessages = JSON_MAPPER.readTree(inputJson);
        assertTrue(inputMessages.isArray());
        assertTrue(inputMessages.size() > 0);
        assertEquals("user", inputMessages.get(0).get("role").asText());

        // output_json: choices array built from accumulated streamed text
        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson, "braintrust.output_json should be set for streaming");
        JsonNode outputChoices = JSON_MAPPER.readTree(outputJson);
        assertTrue(outputChoices.isArray());
        assertEquals(1, outputChoices.size());
        assertEquals("assistant", outputChoices.get(0).get("message").get("role").asText());
        assertTrue(
                outputChoices
                        .get(0)
                        .get("message")
                        .get("content")
                        .asText()
                        .toLowerCase()
                        .contains("paris"),
                "streamed output should mention Paris");
    }
}
