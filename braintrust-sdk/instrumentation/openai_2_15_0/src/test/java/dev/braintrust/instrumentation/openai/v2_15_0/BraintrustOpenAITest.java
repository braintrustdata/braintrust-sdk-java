package dev.braintrust.instrumentation.openai.v2_15_0;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.helpers.ChatCompletionAccumulator;
import com.openai.helpers.ResponseAccumulator;
import com.openai.models.ChatModel;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.responses.*;
import dev.braintrust.TestHarness;
import dev.braintrust.instrumentation.Instrumenter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BraintrustOpenAITest {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @BeforeAll
    public static void beforeAll() {
        var instrumentation = ByteBuddyAgent.install();
        Instrumenter.install(instrumentation, BraintrustOpenAITest.class.getClassLoader());
    }

    private TestHarness testHarness;

    @BeforeEach
    void beforeEach() {
        testHarness = TestHarness.setup();
    }

    @Test
    @SneakyThrows
    void testCompletions() {
        OpenAIClient openAIClient =
                OpenAIOkHttpClient.builder()
                        .baseUrl(testHarness.openAiBaseUrl())
                        .apiKey(testHarness.openAiApiKey())
                        .build();

        var request =
                ChatCompletionCreateParams.builder()
                        .model(ChatModel.GPT_4O_MINI)
                        .addSystemMessage("You are a helpful assistant")
                        .addUserMessage("What is the capital of France?")
                        .temperature(0.0)
                        .build();

        var response = openAIClient.chat().completions().create(request);
        assertNotNull(response);
        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        assertValidOpenAISpan(spans.get(0), false);
    }

    @Test
    @SneakyThrows
    void testCompletionsStreaming() {
        OpenAIClient openAIClient =
                OpenAIOkHttpClient.builder()
                        .baseUrl(testHarness.openAiBaseUrl())
                        .apiKey(testHarness.openAiApiKey())
                        .build();

        var request =
                ChatCompletionCreateParams.builder()
                        .model(ChatModel.GPT_4O_MINI)
                        .addSystemMessage("You are a helpful assistant")
                        .addUserMessage("What is the capital of France?")
                        .temperature(0.0)
                        .streamOptions(
                                ChatCompletionStreamOptions.builder().includeUsage(true).build())
                        .build();

        var accumulator = ChatCompletionAccumulator.create();
        try (var stream = openAIClient.chat().completions().createStreaming(request)) {
            stream.stream().forEach(accumulator::accumulate);
        }
        assertFalse(accumulator.chatCompletion().choices().isEmpty(), "should generate a response");

        // Verify spans
        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        assertValidOpenAISpan(spans.get(0), true);
    }

    @Test
    @SneakyThrows
    void testCompletionsAsync() {
        OpenAIClient openAIClient =
                OpenAIOkHttpClient.builder()
                        .baseUrl(testHarness.openAiBaseUrl())
                        .apiKey(testHarness.openAiApiKey())
                        .build();

        var request =
                ChatCompletionCreateParams.builder()
                        .model(ChatModel.GPT_4O_MINI)
                        .addSystemMessage("You are a helpful assistant")
                        .addUserMessage("What is the capital of France?")
                        .temperature(0.0)
                        .build();

        var response =
                openAIClient.async().chat().completions().create(request).get(5, TimeUnit.MINUTES);
        assertNotNull(response);
        assertNotNull(response.id());

        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        assertValidOpenAISpan(spans.get(0), false);
    }

    @Test
    @SneakyThrows
    void testCompletionsAsyncStreaming() {
        OpenAIClient openAIClient =
                OpenAIOkHttpClient.builder()
                        .baseUrl(testHarness.openAiBaseUrl())
                        .apiKey(testHarness.openAiApiKey())
                        .build();

        var request =
                ChatCompletionCreateParams.builder()
                        .model(ChatModel.GPT_4O_MINI)
                        .addSystemMessage("You are a helpful assistant")
                        .addUserMessage("What is the capital of France?")
                        .temperature(0.0)
                        .streamOptions(
                                ChatCompletionStreamOptions.builder().includeUsage(true).build())
                        .build();

        var fullResponse = new StringBuilder();
        var stream = openAIClient.async().chat().completions().createStreaming(request);
        stream.subscribe(
                chunk -> {
                    if (!chunk.choices().isEmpty()) {
                        chunk.choices().get(0).delta().content().ifPresent(fullResponse::append);
                    }
                });
        stream.onCompleteFuture().get(30, TimeUnit.SECONDS);

        assertFalse(fullResponse.toString().isEmpty());

        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var span = spans.get(0);

        assertEquals("Chat Completion", span.getName());

        String metadataJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(metadataJson);
        JsonNode metadata = JSON_MAPPER.readTree(metadataJson);
        assertEquals("openai", metadata.get("provider").asText());

        assertNotNull(span.getAttributes().get(AttributeKey.stringKey("braintrust.input_json")));

        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson);
        var outputChoices = JSON_MAPPER.readTree(outputJson);
        assertEquals(1, outputChoices.size());
        assertEquals("assistant", outputChoices.get(0).get("message").get("role").asText());

        String metricsJson = span.getAttributes().get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson);
        JsonNode metrics = JSON_MAPPER.readTree(metricsJson);
        assertTrue(metrics.has("prompt_tokens"));
        assertTrue(metrics.has("completion_tokens"));
        assertTrue(metrics.has("tokens"));
        assertTrue(
                metrics.has("time_to_first_token"),
                "time_to_first_token should be present for streaming");
        assertTrue(metrics.get("time_to_first_token").asDouble() >= 0.0);
    }

    @Test
    @SneakyThrows
    void testResponses() {
        OpenAIClient openAIClient =
                OpenAIOkHttpClient.builder()
                        .baseUrl(testHarness.openAiBaseUrl())
                        .apiKey(testHarness.openAiApiKey())
                        .build();

        var inputMsg =
                EasyInputMessage.builder()
                        .role(EasyInputMessage.Role.USER)
                        .content("What is the capital of France? Reply in one word.")
                        .build();

        var request =
                ResponseCreateParams.builder()
                        .model("o4-mini")
                        .reasoning(
                                Reasoning.builder()
                                        .effort(ReasoningEffort.LOW)
                                        .summary(Reasoning.Summary.AUTO)
                                        .build())
                        .inputOfResponse(List.of(ResponseInputItem.ofEasyInputMessage(inputMsg)))
                        .build();

        var response = openAIClient.responses().create(request);

        assertNotNull(response);
        assertNotNull(response.id());

        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        assertValidOpenAISpan(spans.get(0), false);
    }

    @Test
    void testResponsesStreaming() {
        OpenAIClient client =
                OpenAIOkHttpClient.builder()
                        .baseUrl(testHarness.openAiBaseUrl())
                        .apiKey(testHarness.openAiApiKey())
                        .build();
        try (StreamResponse<ResponseStreamEvent> stream =
                client.responses()
                        .createStreaming(
                                ResponseCreateParams.builder()
                                        .model(ChatModel.GPT_4O_MINI)
                                        .instructions("You are a helpful assistant")
                                        .inputOfResponse(
                                                List.of(
                                                        ResponseInputItem.ofEasyInputMessage(
                                                                EasyInputMessage.builder()
                                                                        .role(
                                                                                EasyInputMessage
                                                                                        .Role.USER)
                                                                        .content(
                                                                                "What is the"
                                                                                    + " capital of"
                                                                                    + " France?")
                                                                        .build())))
                                        .build())) {
            ResponseAccumulator accumulator = ResponseAccumulator.create();
            stream.stream().forEach(accumulator::accumulate);
            Response response = accumulator.response();
            assertFalse(
                    response.output()
                            .get(0)
                            .asMessage()
                            .content()
                            .get(0)
                            .asOutputText()
                            .text()
                            .isEmpty(),
                    "should generate a response");
        }
        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        assertValidOpenAISpan(spans.get(0), true);
    }

    @Test
    @SneakyThrows
    void testResponsesAsync() {
        OpenAIClient openAIClient =
                OpenAIOkHttpClient.builder()
                        .baseUrl(testHarness.openAiBaseUrl())
                        .apiKey(testHarness.openAiApiKey())
                        .build();

        var inputMsg =
                EasyInputMessage.builder()
                        .role(EasyInputMessage.Role.USER)
                        .content("What is the capital of France? Reply in one word.")
                        .build();

        var request =
                ResponseCreateParams.builder()
                        .model(ChatModel.GPT_4O_MINI)
                        .instructions("You are a helpful assistant")
                        .inputOfResponse(List.of(ResponseInputItem.ofEasyInputMessage(inputMsg)))
                        .build();

        var response = openAIClient.async().responses().create(request).get(5, TimeUnit.MINUTES);
        assertNotNull(response);
        assertNotNull(response.id());

        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        assertValidOpenAISpan(spans.get(0), false);
    }

    @Test
    @SneakyThrows
    void testResponsesAsyncStreaming() {
        OpenAIClient openAIClient =
                OpenAIOkHttpClient.builder()
                        .baseUrl(testHarness.openAiBaseUrl())
                        .apiKey(testHarness.openAiApiKey())
                        .build();

        var request =
                ResponseCreateParams.builder()
                        .model(ChatModel.GPT_4O_MINI)
                        .instructions("You are a helpful assistant")
                        .inputOfResponse(
                                List.of(
                                        ResponseInputItem.ofEasyInputMessage(
                                                EasyInputMessage.builder()
                                                        .role(EasyInputMessage.Role.USER)
                                                        .content("What is the capital of France?")
                                                        .build())))
                        .build();

        var fullResponse = new StringBuilder();
        var stream = openAIClient.async().responses().createStreaming(request);
        stream.subscribe(
                event ->
                        event.outputTextDelta()
                                .ifPresent(delta -> fullResponse.append(delta.delta())));
        stream.onCompleteFuture().get(30, TimeUnit.SECONDS);

        assertFalse(fullResponse.toString().isEmpty());

        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        assertValidOpenAISpan(spans.get(0), true);
    }

    @SneakyThrows
    private static void assertValidOpenAISpan(SpanData span, boolean isStreaming) {
        var attributes = span.getAttributes();
        // proper provider
        {
            String metadataJson = attributes.get(AttributeKey.stringKey("braintrust.metadata"));
            assertNotNull(metadataJson, "metadata must be set");
            JsonNode metadata = JSON_MAPPER.readTree(metadataJson);
            assertTrue(metadata.has("provider"));
            assertEquals("openai", metadata.get("provider").asText());
        }
        // ttft check
        {
            String metricsJson = attributes.get(AttributeKey.stringKey("braintrust.metrics"));
            assertNotNull(metricsJson, "metrics must be set");
            JsonNode metrics = JSON_MAPPER.readTree(metricsJson);
            var ttft = metrics.get("time_to_first_token");
            if (isStreaming) {
                assertNotNull(ttft);
            } else {
                assertNull(ttft);
            }
        }
        // input + output
        assertNotNull(
                attributes.get(AttributeKey.stringKey("braintrust.input_json")),
                "input must be set");
        assertNotNull(
                attributes.get(AttributeKey.stringKey("braintrust.output_json")),
                "output must be set");
    }
}
