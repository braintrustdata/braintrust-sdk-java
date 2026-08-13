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
import org.springframework.ai.tool.function.FunctionToolCallback;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class BraintrustSpringAITest {
    private static final String TEST_MODEL = "claude-haiku-4-5";
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
                        TEST_MODEL,
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
                                        .model(TEST_MODEL)
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
        assertFalse(
                metrics(span).has("time_to_first_token"),
                "time_to_first_token should not be present for non-streaming");
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
        // Find messages by role — ordering may differ between providers.
        JsonNode systemMsg = null, userMsg = null;
        for (int i = 0; i < messages.size(); i++) {
            String role = messages.get(i).get("role").asText();
            if ("system".equals(role)) systemMsg = messages.get(i);
            if ("user".equals(role)) userMsg = messages.get(i);
        }
        assertNotNull(systemMsg, "should have a system message");
        assertNotNull(userMsg, "should have a user message");
        JsonNode content = userMsg.get("content");
        String contentText =
                content.isArray() ? content.get(0).get("text").asText() : content.asText();
        assertTrue(contentText.contains("capital"), "user message should contain the prompt text");
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

    /** Tool-call parameter type; its shape drives the generated JSON schema. */
    record WeatherRequest(String location) {}

    /**
     * OpenAI-only: a streaming call that provokes a tool call. The OpenAI streaming path routes
     * through {@code reassembleOpenAISSE} → the shared {@link
     * dev.braintrust.instrumentation.SseResponseAccumulator}, which merges the streamed tool-call
     * argument fragments by index. {@code internalToolExecutionEnabled(false)} makes Spring return
     * the tool call instead of executing it, so there is exactly one llm span to inspect.
     */
    @org.junit.jupiter.api.Test
    @SneakyThrows
    void testStreamWithTools() {
        Provider provider =
                new Provider("openai", "openai", "gpt-4o-mini", TestHarness::openAiBaseUrl, true);
        ChatModel chatModel = buildChatModel(provider);

        var weatherTool =
                FunctionToolCallback.builder("get_weather", (WeatherRequest req) -> "unused")
                        .description("Get the current weather for a location")
                        .inputType(WeatherRequest.class)
                        .build();

        var prompt =
                new Prompt(
                        "What is the weather in Paris, France?",
                        OpenAiChatOptions.builder()
                                .model("gpt-4o-mini")
                                .temperature(0.0)
                                .maxTokens(256)
                                .streamUsage(true)
                                .toolCallbacks(weatherTool)
                                .internalToolExecutionEnabled(false)
                                .build());

        boolean[] streamSurfacedToolCall = {false};
        chatModel.stream(prompt)
                .doOnNext(
                        chunk -> {
                            var result = chunk.getResult();
                            if (result != null
                                    && result.getOutput() != null
                                    && result.getOutput().hasToolCalls()) {
                                for (var toolCall : result.getOutput().getToolCalls()) {
                                    if ("get_weather".equals(toolCall.name())) {
                                        streamSurfacedToolCall[0] = true;
                                    }
                                }
                            }
                        })
                .blockLast();
        assertTrue(streamSurfacedToolCall[0], "stream should surface a get_weather tool call");

        var spans = testHarness.awaitExportedSpans(1);
        assertEquals(1, spans.size());
        SpanData span = spans.get(0);
        assertCommonSpanAttributes(span, provider);

        // The reconstructed streaming span output must carry the tool call, with its argument
        // fragments merged into valid JSON — the bug this module previously had dropped them.
        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson, "braintrust.output_json should be set");
        JsonNode toolCalls =
                JSON_MAPPER.readTree(outputJson).get(0).get("message").get("tool_calls");
        assertNotNull(toolCalls, "streaming span output should contain tool_calls");
        assertEquals(1, toolCalls.size(), "expected a single tool call");
        JsonNode function = toolCalls.get(0).get("function");
        assertEquals("get_weather", function.get("name").asText(), "tool name should match");
        assertTrue(
                JSON_MAPPER.readTree(function.get("arguments").asText()).has("location"),
                "merged tool arguments should be valid JSON containing 'location'");
    }

    /**
     * Anthropic-only: a streaming call that provokes a tool call. The Anthropic streaming path
     * routes through {@code reassembleAnthropicSSE}, which must preserve the {@code tool_use} block
     * — accumulating the streamed {@code input_json_delta} fragments into the block's {@code input}
     * — rather than dropping everything but text. {@code internalToolExecutionEnabled(false)} keeps
     * it to a single llm span.
     *
     * <p>(Extended thinking is not exercised here: Spring AI 1.0.0's own streaming deserializer
     * only knows the {@code text}/{@code tool_use} content-block types and throws on {@code
     * thinking} blocks, so a thinking stream can't complete through this client. The reassembly
     * handles {@code thinking_delta} regardless, for clients/versions that do emit it.)
     */
    @org.junit.jupiter.api.Test
    @SneakyThrows
    void testAnthropicStreamWithTools() {
        Provider provider =
                new Provider(
                        "anthropic", "anthropic", TEST_MODEL, TestHarness::anthropicBaseUrl, false);
        ChatModel chatModel = buildChatModel(provider);

        var weatherTool =
                FunctionToolCallback.builder("get_weather", (WeatherRequest req) -> "unused")
                        .description("Get the current weather for a location")
                        .inputType(WeatherRequest.class)
                        .build();

        var options =
                AnthropicChatOptions.builder()
                        .model(TEST_MODEL)
                        .temperature(0.0)
                        .maxTokens(1024)
                        .toolCallbacks(weatherTool)
                        .internalToolExecutionEnabled(false)
                        .build();

        chatModel.stream(new Prompt("What is the weather in Paris, France?", options)).blockLast();

        var spans = testHarness.awaitExportedSpans(1);
        assertEquals(1, spans.size());
        SpanData span = spans.get(0);
        assertCommonSpanAttributes(span, provider);

        // The reconstructed streaming output must carry the tool_use block with its input JSON
        // merged from input_json_delta fragments — previously dropped by the text-only reassembly.
        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson, "braintrust.output_json should be set");
        JsonNode content = JSON_MAPPER.readTree(outputJson).get("content");
        assertNotNull(content, "anthropic output should have a content array");

        JsonNode toolUse = null;
        for (JsonNode block : content) {
            if ("tool_use".equals(block.path("type").asText())) toolUse = block;
        }
        assertNotNull(toolUse, "streaming output should preserve the tool_use block");
        assertEquals("get_weather", toolUse.path("name").asText(), "tool name should match");
        assertTrue(
                toolUse.path("input").has("location"),
                "tool_use input should be reconstructed JSON containing 'location'");
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
