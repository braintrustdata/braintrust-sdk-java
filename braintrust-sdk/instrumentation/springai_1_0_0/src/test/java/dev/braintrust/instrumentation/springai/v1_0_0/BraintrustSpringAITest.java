package dev.braintrust.instrumentation.springai.v1_0_0;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.TestHarness;
import dev.braintrust.instrumentation.Instrumenter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.function.Function;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class BraintrustSpringAITest {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @BeforeAll
    public void beforeAll() {
        var instrumentation = ByteBuddyAgent.install();
        Instrumenter.install(instrumentation, BraintrustSpringAITest.class.getClassLoader());
    }

    private TestHarness testHarness;

    @BeforeEach
    void beforeEach() {
        testHarness = TestHarness.setup();
    }

    // -------------------------------------------------------------------------
    // Provider descriptor — carries only name and expected assertions.
    // ChatModel is built fresh per test via buildChatModel() so it uses the
    // current testHarness's OpenTelemetry instance.
    // -------------------------------------------------------------------------

    record Provider(
            String name,
            String expectedProvider,
            String expectedModelPrefix,
            Function<TestHarness, String> expectedBaseUrl,
            boolean outputIsChoicesArray) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Provider> providers() {
        return Stream.of(
                new Provider("openai", "openai", "gpt-4o-mini", TestHarness::openAiBaseUrl, true),
                new Provider(
                        "anthropic",
                        "anthropic",
                        "claude-3-haiku",
                        TestHarness::anthropicBaseUrl,
                        false));
    }

    /** Builds a fresh {@link ChatModel} for each test so it picks up the current OTel instance. */
    private ChatModel buildChatModel(Provider provider) {
        return switch (provider.name()) {
            case "openai" -> {
                // testHarness.openAiBaseUrl() returns a URL ending in "/v1" (both the real API
                // and the VCR proxy). Spring AI's default completionsPath is "/v1/chat/completions"
                // which would double the "/v1". Override it to just "/chat/completions" so the
                // full URL resolves correctly in all VCR modes.
                var api =
                        OpenAiApi.builder()
                                .baseUrl(testHarness.openAiBaseUrl())
                                .completionsPath("/chat/completions")
                                .apiKey(testHarness.openAiApiKey())
                                .build();
                yield OpenAiChatModel.builder()
                        .openAiApi(api)
                        .defaultOptions(
                                OpenAiChatOptions.builder()
                                        .model("gpt-4o-mini")
                                        .temperature(0.0)
                                        .maxTokens(50)
                                        .build())
                        .build();
            }
            case "anthropic" -> {
                var api =
                        AnthropicApi.builder()
                                .baseUrl(testHarness.anthropicBaseUrl())
                                .apiKey(testHarness.anthropicApiKey())
                                .build();
                yield AnthropicChatModel.builder()
                        .anthropicApi(api)
                        .defaultOptions(
                                AnthropicChatOptions.builder()
                                        .model("claude-3-haiku-20240307")
                                        .temperature(0.0)
                                        .maxTokens(50)
                                        .build())
                        .build();
            }
            default -> throw new IllegalArgumentException("Unknown provider: " + provider.name());
        };
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    @SneakyThrows
    void testCall(Provider provider) {
        ChatModel chatModel = buildChatModel(provider);
        var response = chatModel.call(new Prompt("What is the capital of France?"));

        assertNotNull(response);
        String text = response.getResult().getOutput().getText();
        assertTrue(text.toLowerCase().contains("paris"), "Response should mention Paris: " + text);

        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        SpanData span = spans.get(0);

        assertCommonSpanAttributes(span, provider);
        assertInputMessages(span, 1);
        assertEquals("user", inputMessages(span).get(0).get("role").asText());
        assertOutputMentionsParis(span, provider);
        assertTokenMetrics(span);
        // FIXME: spring's observation context does not have reliable streaming detection.
        //   Probably requires a different instrumentation approach.
        //   For now, we'll have a redundant tag on non-streaming requests
        // assertFalse(metrics(span).has("time_to_first_token"), "time_to_first_token should not be
        // present for non-streaming");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    @SneakyThrows
    void testCallWithSystemMessage(Provider provider) {
        ChatModel chatModel = buildChatModel(provider);
        var prompt =
                new Prompt(
                        java.util.List.of(
                                new SystemMessage("You are a helpful geography assistant."),
                                new UserMessage("What is the capital of France?")));
        var response = chatModel.call(prompt);

        assertNotNull(response);
        String text = response.getResult().getOutput().getText();
        assertTrue(text.toLowerCase().contains("paris"), "Response should mention Paris: " + text);

        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        SpanData span = spans.get(0);

        assertCommonSpanAttributes(span, provider);
        assertInputMessages(span, 2);
        JsonNode messages = inputMessages(span);
        assertEquals("system", messages.get(0).get("role").asText());
        assertEquals("user", messages.get(1).get("role").asText());
        assertTrue(
                messages.get(1).get("content").asText().contains("capital"),
                "user message should contain the prompt text");
        assertOutputMentionsParis(span, provider);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    @SneakyThrows
    void testStream(Provider provider) {
        ChatModel chatModel = buildChatModel(provider);
        var fullText = new StringBuilder();
        chatModel.stream(streamPrompt(provider))
                .doOnNext(
                        chunk -> {
                            if (chunk.getResult() != null
                                    && chunk.getResult().getOutput() != null
                                    && chunk.getResult().getOutput().getText() != null) {
                                fullText.append(chunk.getResult().getOutput().getText());
                            }
                        })
                .blockLast();

        assertFalse(fullText.isEmpty(), "Should have received streaming chunks");
        assertTrue(
                fullText.toString().toLowerCase().contains("paris"),
                "Streamed response should mention Paris: " + fullText);

        // Observation span completes on Reactor scheduler thread; wait for it
        var spans = testHarness.awaitExportedSpans(1);
        assertEquals(1, spans.size());
        SpanData span = spans.get(0);

        assertCommonSpanAttributes(span, provider);
        assertInputMessages(span, 1);
        assertEquals("user", inputMessages(span).get(0).get("role").asText());
        assertOutputMentionsParis(span, provider);
        assertTokenMetrics(span);
        assertTrue(
                metrics(span).has("time_to_first_token")
                        && metrics(span).get("time_to_first_token").asLong() >= 0,
                "streaming responses should capture time to first token");
    }

    // -------------------------------------------------------------------------
    // Shared assertion helpers
    // -------------------------------------------------------------------------

    @SneakyThrows
    private void assertCommonSpanAttributes(SpanData span, Provider provider) {
        assertEquals("llm", spanAttributes(span).get("type").asText());
        assertEquals(provider.expectedProvider(), metadata(span).get("provider").asText());
        assertTrue(
                metadata(span).get("model").asText().startsWith(provider.expectedModelPrefix()),
                "model should start with "
                        + provider.expectedModelPrefix()
                        + ", got: "
                        + metadata(span).get("model").asText());

        assertEquals(
                provider.expectedBaseUrl().apply(testHarness),
                metadata(span).get("request_base_uri").asText(),
                "request_base_uri should match the configured base URL");
    }

    @SneakyThrows
    private void assertInputMessages(SpanData span, int expectedCount) {
        assertTrue(inputMessages(span).isArray(), "input_json should be an array");
        assertEquals(
                expectedCount,
                inputMessages(span).size(),
                "Expected " + expectedCount + " input message(s)");
    }

    @SneakyThrows
    private void assertOutputMentionsParis(SpanData span, Provider provider) {
        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson, "braintrust.output_json should be set");
        JsonNode output = JSON_MAPPER.readTree(outputJson);

        String assistantText;
        if (provider.outputIsChoicesArray()) {
            assertTrue(output.isArray(), "output_json should be an array for " + provider.name());
            assertTrue(output.size() > 0);
            assistantText = output.get(0).get("message").get("content").asText();
        } else {
            assertTrue(
                    output.has("content"),
                    "output_json should have content field for " + provider.name());
            assistantText = output.get("content").get(0).get("text").asText();
        }
        assertTrue(
                assistantText.toLowerCase().contains("paris"),
                "Output should mention Paris for " + provider.name() + ": " + assistantText);
    }

    private Prompt streamPrompt(Provider provider) {
        if ("openai".equals(provider.name())) {
            return new Prompt(
                    "What is the capital of France?",
                    OpenAiChatOptions.builder()
                            .model("gpt-4o-mini")
                            .temperature(0.0)
                            .maxTokens(50)
                            .streamUsage(true)
                            .build());
        }
        return new Prompt("What is the capital of France?");
    }

    private void assertTokenMetrics(SpanData span) {
        JsonNode m = metrics(span);
        assertTrue(m.has("prompt_tokens"), "prompt_tokens should be present");
        assertTrue(m.get("prompt_tokens").asInt() > 0, "prompt_tokens should be positive");
        assertTrue(m.has("completion_tokens"), "completion_tokens should be present");
        assertTrue(m.get("completion_tokens").asInt() > 0, "completion_tokens should be positive");
        if (m.has("prompt_tokens") && m.has("completion_tokens")) {
            assertTrue(m.has("tokens"), "tokens should be present when prompt+completion are");
            assertTrue(m.get("tokens").asInt() > 0, "tokens should be positive");
        }
    }

    // -------------------------------------------------------------------------
    // Attribute extractors
    // -------------------------------------------------------------------------

    @SneakyThrows
    private JsonNode spanAttributes(SpanData span) {
        String json =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.span_attributes"));
        assertNotNull(json, "braintrust.span_attributes should be set");
        return JSON_MAPPER.readTree(json);
    }

    @SneakyThrows
    private JsonNode metadata(SpanData span) {
        String json = span.getAttributes().get(AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(json, "braintrust.metadata should be set");
        return JSON_MAPPER.readTree(json);
    }

    @SneakyThrows
    private JsonNode inputMessages(SpanData span) {
        String json = span.getAttributes().get(AttributeKey.stringKey("braintrust.input_json"));
        assertNotNull(json, "braintrust.input_json should be set");
        return JSON_MAPPER.readTree(json);
    }

    @SneakyThrows
    private JsonNode metrics(SpanData span) {
        String json = span.getAttributes().get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(json, "braintrust.metrics should be set");
        return JSON_MAPPER.readTree(json);
    }
}
