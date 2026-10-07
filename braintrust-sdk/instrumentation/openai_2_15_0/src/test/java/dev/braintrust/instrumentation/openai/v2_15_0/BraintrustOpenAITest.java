package dev.braintrust.instrumentation.openai.v2_15_0;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.openai.core.JsonValue;
import com.openai.core.MultipartField;
import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpMethod;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpRequestBody;
import com.openai.core.http.HttpResponse;
import com.openai.core.http.StreamResponse;
import com.openai.helpers.ChatCompletionAccumulator;
import com.openai.helpers.ResponseAccumulator;
import com.openai.models.ChatModel;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.audio.AudioModel;
import com.openai.models.audio.AudioResponseFormat;
import com.openai.models.audio.speech.SpeechCreateParams;
import com.openai.models.audio.speech.SpeechModel;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams;
import com.openai.models.batches.BatchListParams;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.images.ImageGenerateParams;
import com.openai.models.images.ImageModel;
import com.openai.models.moderations.ModerationCreateParams;
import com.openai.models.moderations.ModerationModel;
import com.openai.models.responses.*;
import dev.braintrust.TestHarness;
import dev.braintrust.instrumentation.Instrumenter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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
    void testBatchListAsync() {
        OpenAIClient openAIClient =
                OpenAIOkHttpClient.builder()
                        .baseUrl(testHarness.openAiBaseUrl())
                        .apiKey(testHarness.openAiApiKey())
                        .build();
        var parent =
                testHarness
                        .openTelemetry()
                        .getTracer("batch-test")
                        .spanBuilder("parent")
                        .startSpan();
        try (var ignored = parent.makeCurrent()) {
            var page =
                    openAIClient
                            .async()
                            .batches()
                            .list(BatchListParams.builder().limit(1L).build())
                            .get(5, TimeUnit.MINUTES);
            assertEquals(JsonValue.from("list"), page.response()._object_());
            assertTrue(page.response().data().size() <= 1);
        } finally {
            parent.end();
        }
        assertHttpSpan(parent, false, false);
    }

    @Test
    @SneakyThrows
    void testImageGeneration() {
        OpenAIClient client =
                OpenAIOkHttpClient.builder()
                        .baseUrl(testHarness.openAiBaseUrl())
                        .apiKey(testHarness.openAiApiKey())
                        .build();
        var parent =
                testHarness
                        .openTelemetry()
                        .getTracer("media-test")
                        .spanBuilder("parent")
                        .startSpan();
        try (var ignored = parent.makeCurrent()) {
            var response =
                    client.images()
                            .generate(
                                    ImageGenerateParams.builder()
                                            .model(ImageModel.GPT_IMAGE_1)
                                            .prompt(
                                                    "A small blue circle on a plain white"
                                                            + " background.")
                                            .quality(ImageGenerateParams.Quality.LOW)
                                            .size(ImageGenerateParams.Size._1024X1024)
                                            .outputFormat(ImageGenerateParams.OutputFormat.PNG)
                                            .n(1L)
                                            .build())
                            .validate();
            assertTrue(response.created() > 0);
            var images = response.data().orElseThrow();
            assertEquals(1, images.size());
            byte[] image = Base64.getDecoder().decode(images.get(0).b64Json().orElseThrow());
            assertTrue(image.length > 8, "image response must contain PNG bytes");
            assertArrayEquals(
                    new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10},
                    Arrays.copyOf(image, 8));
        } finally {
            parent.end();
        }
        assertSingleMediaSpan(parent, "images/generations", "gpt-image-1");
    }

    @Test
    @SneakyThrows
    void testModerationAsync() {
        OpenAIClientAsync client =
                OpenAIOkHttpClientAsync.builder()
                        .baseUrl(testHarness.openAiBaseUrl())
                        .apiKey(testHarness.openAiApiKey())
                        .build();
        var parent =
                testHarness
                        .openTelemetry()
                        .getTracer("media-test")
                        .spanBuilder("parent")
                        .startSpan();
        try (var ignored = parent.makeCurrent()) {
            var response =
                    client.moderations()
                            .create(
                                    ModerationCreateParams.builder()
                                            .model(ModerationModel.OMNI_MODERATION_LATEST)
                                            .input("The garden has bright flowers.")
                                            .build())
                            .get(5, TimeUnit.MINUTES)
                            .validate();
            assertFalse(response.id().isBlank());
            assertFalse(response.model().isBlank());
            assertEquals(1, response.results().size());
            var result = response.results().get(0);
            assertNotNull(result.categories());
            double score = result.categoryScores().violence();
            assertTrue(score >= 0.0 && score <= 1.0);
        } finally {
            parent.end();
        }
        assertSingleMediaSpan(parent, "moderations", "omni-moderation-latest");
    }

    @Test
    @SneakyThrows
    void testSpeechAndTranscription() {
        OpenAIClient client =
                OpenAIOkHttpClient.builder()
                        .baseUrl(testHarness.openAiBaseUrl())
                        .apiKey(testHarness.openAiApiKey())
                        .build();
        var parent =
                testHarness
                        .openTelemetry()
                        .getTracer("media-test")
                        .spanBuilder("parent")
                        .startSpan();
        String transcript;
        try (var ignored = parent.makeCurrent()) {
            byte[] audio;
            try (var response =
                    client.audio()
                            .speech()
                            .create(
                                    SpeechCreateParams.builder()
                                            .model(SpeechModel.GPT_4O_MINI_TTS)
                                            .voice(SpeechCreateParams.Voice.ALLOY)
                                            .input("The garden has bright flowers.")
                                            .responseFormat(SpeechCreateParams.ResponseFormat.WAV)
                                            .build())) {
                assertEquals(200, response.statusCode());
                audio = response.body().readAllBytes();
            }
            assertTrue(audio.length > 44, "speech response must contain WAV audio");
            assertEquals("RIFF", new String(audio, 0, 4, StandardCharsets.US_ASCII));
            assertEquals("WAVE", new String(audio, 8, 4, StandardCharsets.US_ASCII));
            var response =
                    client.async()
                            .audio()
                            .transcriptions()
                            .create(
                                    TranscriptionCreateParams.builder()
                                            .model(AudioModel.GPT_4O_MINI_TRANSCRIBE)
                                            .file(
                                                    MultipartField.<InputStream>builder()
                                                            .value(new ByteArrayInputStream(audio))
                                                            .filename("speech.wav")
                                                            .contentType("audio/wav")
                                                            .build())
                                            .responseFormat(AudioResponseFormat.JSON)
                                            .build())
                            .get(5, TimeUnit.MINUTES);
            assertTrue(response.isTranscription());
            transcript = response.asTranscription().validate().text();
            assertFalse(transcript.isBlank());
        } finally {
            parent.end();
        }
        var spans = testHarness.awaitExportedSpans(3);
        assertEquals(3, spans.size(), "one parent and two ended inference spans");
        var inferenceSpans =
                spans.stream()
                        .filter(s -> !s.getSpanId().equals(parent.getSpanContext().getSpanId()))
                        .toList();
        for (var span : inferenceSpans) {
            String path = mediaMetadata(span).path("request_path").asText();
            assertTrue(path.equals("audio/speech") || path.equals("audio/transcriptions"));
            assertMediaSpan(
                    span, parent, path, path.equals("audio/speech") ? "gpt-4o-mini-tts" : null);
            if (path.equals("audio/transcriptions")) {
                assertEquals(
                        transcript,
                        JSON_MAPPER
                                .readTree(
                                        span.getAttributes()
                                                .get(
                                                        AttributeKey.stringKey(
                                                                "braintrust.output_json")))
                                .path("text")
                                .asText());
            }
        }
        assertEquals(
                List.of("audio/speech", "audio/transcriptions"),
                inferenceSpans.stream()
                        .map(s -> mediaMetadata(s).path("request_path").asText())
                        .sorted()
                        .toList());
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

    /**
     * Unlike {@link #testCompletionsAsync()}, which derives the async view from an instrumented
     * sync client via {@code .async()}, this builds {@link OpenAIOkHttpClientAsync} directly —
     * exercising the async-builder auto-instrumentation hook.
     *
     * <p>Also verifies context linking: the SDK dispatches async requests onto a worker pool, but
     * the LLM span must still parent to the span that was current when the request was kicked off.
     */
    @Test
    @SneakyThrows
    void testDirectAsyncClientCompletions() {
        // Built OUTSIDE any span on purpose: parenting must come from the context at request
        // time, not from the context at client-construction time.
        OpenAIClientAsync openAIClient =
                OpenAIOkHttpClientAsync.builder()
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

        var parentSpan =
                testHarness.openTelemetry().getTracer("test").spanBuilder("foo").startSpan();
        try (var ignored = parentSpan.makeCurrent()) {
            var response =
                    openAIClient.chat().completions().create(request).get(5, TimeUnit.MINUTES);
            assertNotNull(response);
            assertNotNull(response.id());
        } finally {
            parentSpan.end();
        }

        var spans = testHarness.awaitExportedSpans(2);
        assertEquals(2, spans.size());
        var llmSpan =
                spans.stream().filter(s -> !"foo".equals(s.getName())).findFirst().orElseThrow();
        assertValidOpenAISpan(llmSpan, false);
        assertEquals(
                parentSpan.getSpanContext().getTraceId(),
                llmSpan.getTraceId(),
                "async LLM span should be in the caller's trace");
        assertEquals(
                parentSpan.getSpanContext().getSpanId(),
                llmSpan.getParentSpanId(),
                "async LLM span should be a child of the span current at request time");
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

    /**
     * When the client's HTTP layer cannot be instrumented (custom implementations, changed SDK
     * internals), wrapOpenAI must return the client untouched — installing the context-capturing
     * proxy without a TracingHttpClient underneath would leak internal trace IDs to the provider
     * via the un-stripped context header.
     */
    @Test
    void testUninstrumentableClientIsLeftUntouched() {
        OpenAIClient custom =
                (OpenAIClient)
                        java.lang.reflect.Proxy.newProxyInstance(
                                OpenAIClient.class.getClassLoader(),
                                new Class<?>[] {OpenAIClient.class},
                                (proxy, method, args) -> {
                                    throw new UnsupportedOperationException(method.getName());
                                });

        OpenAIClient wrapped = BraintrustOpenAI.wrapOpenAI(testHarness.openTelemetry(), custom);

        assertSame(custom, wrapped, "uninstrumentable client should not get the context proxy");
    }

    /** The context-capturing proxy must keep Object identity semantics usable (maps/sets). */
    @Test
    void testWrappedClientObjectContract() {
        OpenAIClient client =
                OpenAIOkHttpClient.builder()
                        .baseUrl(testHarness.openAiBaseUrl())
                        .apiKey(testHarness.openAiApiKey())
                        .build();
        OpenAIClient wrapped = BraintrustOpenAI.wrapOpenAI(testHarness.openTelemetry(), client);
        OpenAIClient rewrapped = BraintrustOpenAI.wrapOpenAI(testHarness.openTelemetry(), client);

        assertEquals(wrapped, wrapped, "equals must be reflexive");
        assertEquals(wrapped, rewrapped, "proxies over the same delegate should be equal");
        assertEquals(rewrapped, wrapped, "equals must be symmetric");
        assertEquals(wrapped.hashCode(), rewrapped.hashCode(), "hashCode consistent with equals");
        assertFalse(wrapped.equals(null), "equals(null) must be false");
        assertTrue(
                new java.util.HashSet<>(java.util.List.of(wrapped)).contains(wrapped),
                "wrapped client must work as a set element");
        assertTrue(
                wrapped.toString().contains("ContextCapturingProxy"),
                "toString should identify the proxy, got: " + wrapped);
    }

    @Test
    @SneakyThrows
    void testCompletionsStreamingWithTools() {
        OpenAIClient openAIClient =
                OpenAIOkHttpClient.builder()
                        .baseUrl(testHarness.openAiBaseUrl())
                        .apiKey(testHarness.openAiApiKey())
                        .build();

        var weatherTool =
                ChatCompletionTool.builder()
                        .function(
                                FunctionDefinition.builder()
                                        .name("get_weather")
                                        .description("Get the current weather for a location")
                                        .parameters(weatherParameters(FunctionParameters.builder()))
                                        .build())
                        .build();

        var request =
                ChatCompletionCreateParams.builder()
                        .model(ChatModel.GPT_4O)
                        .addUserMessage("What is the weather in Paris, France?")
                        .temperature(0.0)
                        .addTool(weatherTool)
                        .streamOptions(
                                ChatCompletionStreamOptions.builder().includeUsage(true).build())
                        .build();

        var accumulator = ChatCompletionAccumulator.create();
        try (var stream = openAIClient.chat().completions().createStreaming(request)) {
            stream.stream().forEach(accumulator::accumulate);
        }
        var toolCalls = accumulator.chatCompletion().choices().get(0).message().toolCalls();
        assertTrue(toolCalls.isPresent() && !toolCalls.get().isEmpty(), "model should call a tool");

        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var span = spans.get(0);
        assertValidOpenAISpan(span, true);

        // The reconstructed streaming span output must carry the same tool calls the client saw.
        JsonNode spanToolCalls = spanOutput(span).get(0).get("message").get("tool_calls");
        assertNotNull(spanToolCalls, "streaming span output should contain tool_calls");
        assertEquals(toolCalls.get().size(), spanToolCalls.size(), "tool_calls count should match");
        for (int i = 0; i < toolCalls.get().size(); i++) {
            var tc = toolCalls.get().get(i);
            JsonNode spanFn = spanToolCalls.get(i).get("function");
            assertEquals(
                    tc.function().name(), spanFn.get("name").asText(), "tool name should match");
            assertEquals(
                    JSON_MAPPER.readTree(tc.function().arguments()),
                    JSON_MAPPER.readTree(spanFn.get("arguments").asText()),
                    "tool arguments should match");
            assertEquals(tc.id(), spanToolCalls.get(i).get("id").asText(), "tool id should match");
        }
    }

    @Test
    @SneakyThrows
    void testResponsesStreamingWithTools() {
        OpenAIClient client =
                OpenAIOkHttpClient.builder()
                        .baseUrl(testHarness.openAiBaseUrl())
                        .apiKey(testHarness.openAiApiKey())
                        .build();

        var weatherTool =
                FunctionTool.builder()
                        .name("get_weather")
                        .description("Get the current weather for a location")
                        .parameters(weatherParameters(FunctionTool.Parameters.builder()))
                        .strict(false)
                        .build();

        var request =
                ResponseCreateParams.builder()
                        .model(ChatModel.GPT_4O)
                        .inputOfResponse(
                                List.of(
                                        ResponseInputItem.ofEasyInputMessage(
                                                EasyInputMessage.builder()
                                                        .role(EasyInputMessage.Role.USER)
                                                        .content(
                                                                "What is the weather in Paris,"
                                                                        + " France?")
                                                        .build())))
                        .addTool(weatherTool)
                        .build();

        var accumulator = ResponseAccumulator.create();
        try (var stream = client.responses().createStreaming(request)) {
            stream.stream().forEach(accumulator::accumulate);
        }
        var functionCalls =
                accumulator.response().output().stream()
                        .filter(ResponseOutputItem::isFunctionCall)
                        .map(ResponseOutputItem::asFunctionCall)
                        .toList();
        assertFalse(functionCalls.isEmpty(), "model should call a function tool");

        // function_call is a client-side tool call — it stays in the LLM span output and does NOT
        // produce a child span (only server-side tool calls do). So we expect exactly one span.
        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var span = spans.get(0);
        assertValidOpenAISpan(span, true);

        // The Responses API serializes output as an "output" array; the streamed function_call
        // items must survive reconstruction into the span exactly as the client accumulated them.
        var spanFunctionCalls = new java.util.ArrayList<JsonNode>();
        spanOutput(span)
                .forEach(
                        item -> {
                            if ("function_call".equals(item.path("type").asText())) {
                                spanFunctionCalls.add(item);
                            }
                        });
        assertEquals(
                functionCalls.size(), spanFunctionCalls.size(), "function_call count should match");
        for (int i = 0; i < functionCalls.size(); i++) {
            var fc = functionCalls.get(i);
            JsonNode spanFc = spanFunctionCalls.get(i);
            assertEquals(fc.name(), spanFc.get("name").asText(), "function name should match");
            assertEquals(
                    JSON_MAPPER.readTree(fc.arguments()),
                    JSON_MAPPER.readTree(spanFc.get("arguments").asText()),
                    "function arguments should match");
            assertEquals(fc.callId(), spanFc.get("call_id").asText(), "call_id should match");
        }
    }

    /** A minimal {@code get_weather(location)} JSON-schema, shared by both tool-call tests. */
    private static FunctionParameters weatherParameters(FunctionParameters.Builder builder) {
        return builder.putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty(
                        "properties",
                        JsonValue.from(
                                Map.of(
                                        "location",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "The city and state"))))
                .putAdditionalProperty("required", JsonValue.from(List.of("location")))
                .build();
    }

    /** Same {@code get_weather} schema for the Responses API's parameter type. */
    private static FunctionTool.Parameters weatherParameters(
            FunctionTool.Parameters.Builder builder) {
        return builder.putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty(
                        "properties",
                        JsonValue.from(
                                Map.of(
                                        "location",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "The city and state"))))
                .putAdditionalProperty("required", JsonValue.from(List.of("location")))
                .build();
    }

    @SneakyThrows
    private JsonNode spanOutput(SpanData span) {
        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson, "span braintrust.output_json must be set");
        return JSON_MAPPER.readTree(outputJson);
    }

    @SneakyThrows
    private static void assertValidOpenAISpan(SpanData span, boolean isStreaming) {
        var attributes = span.getAttributes();
        JsonNode instrumentation =
                JSON_MAPPER
                        .readTree(attributes.get(AttributeKey.stringKey("braintrust.context_json")))
                        .path("span_origin")
                        .path("instrumentation");
        assertEquals("openai", instrumentation.path("name").asText());
        assertEquals(
                System.getProperty("braintrust.muzzle.minimumVersion"),
                instrumentation.path("version").asText(),
                "span origin version must match the minimum passing muzzle version");
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
        assertOpenAIIdsCaptured(span);
    }

    /**
     * Both correlation IDs OpenAI hands back. Deliberately asserts only presence and the object-ID
     * prefix: {@code x-request-id} is opaque, and OpenAI returns both {@code req_*} and bare UUIDs
     * for it, so pinning its shape would be wrong.
     */
    private static void assertOpenAIIdsCaptured(SpanData span) {
        var attributes = span.getAttributes();

        String requestId = attributes.get(AttributeKey.stringKey("x-request-id"));
        assertNotNull(requestId, "x-request-id header must be captured");
        assertFalse(requestId.isBlank(), "x-request-id must not be blank");

        String responseId = attributes.get(AttributeKey.stringKey("response_id"));
        assertNotNull(responseId, "response_id must be captured from the response body");
        assertTrue(
                responseId.startsWith("resp_") || responseId.startsWith("chatcmpl-"),
                "unexpected response_id: " + responseId);
    }

    /**
     * A failed call is the case where the vendor's request id matters most, and the only one where
     * it is the *sole* ID available: an error body carries no object id of its own. openai-java
     * raises its exception above the HTTP layer we instrument, so the error status on the span is
     * ours alone to set.
     */
    @Test
    @SneakyThrows
    void testHttpErrorTagsSpan() {
        OpenAIClient openAIClient =
                OpenAIOkHttpClient.builder()
                        .baseUrl(testHarness.openAiBaseUrl())
                        .apiKey(testHarness.openAiApiKey())
                        .build();

        var request =
                ChatCompletionCreateParams.builder()
                        .model("gpt-4o-mini-nonexistent-model")
                        .addUserMessage("What is the capital of France?")
                        .build();

        assertThrows(Exception.class, () -> openAIClient.chat().completions().create(request));

        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var span = spans.get(0);

        assertEquals(
                StatusCode.ERROR,
                span.getStatus().getStatusCode(),
                "a non-2xx response must mark the span failed");

        var attributes = span.getAttributes();
        String requestId = attributes.get(AttributeKey.stringKey("x-request-id"));
        assertNotNull(requestId, "x-request-id must still be captured on a failed call");
        assertNull(
                attributes.get(AttributeKey.stringKey("response_id")),
                "an error body has no object id to capture");
    }

    /**
     * Request headers now flow into the semconv layer, and outgoing OpenAI request headers carry
     * the caller's API key — so this pins the allow-list: the only non-{@code braintrust.*}
     * attributes on the span are the two correlation IDs, nothing else from either header set.
     */
    @Test
    @SneakyThrows
    void testOnlyAllowListedHeadersAreTagged() {
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

        openAIClient.chat().completions().create(request);

        var span = testHarness.awaitExportedSpans().get(0);
        var foreignKeys =
                span.getAttributes().asMap().keySet().stream()
                        .map(AttributeKey::getKey)
                        .filter(k -> !k.startsWith("braintrust."))
                        .sorted()
                        .toList();
        assertEquals(List.of("response_id", "x-request-id"), foreignKeys);

        String rendered = span.getAttributes().toString();
        assertFalse(
                rendered.contains(testHarness.openAiApiKey()),
                "the api key must never reach a span attribute");
        assertFalse(
                rendered.toLowerCase().contains("authorization"),
                "no authorization header may reach a span attribute");
    }

    /**
     * Proves the request-header path independently of the response one. OpenAI returns {@code
     * x-request-id} but never {@code request-id}, so a caller-set {@code request-id} is the one
     * allow-listed header that can only have come from the request.
     */
    @Test
    @SneakyThrows
    void testCallerSuppliedCorrelationHeaderIsTagged() {
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
                        .putAdditionalHeader("request-id", "caller-supplied-correlation-id")
                        .build();

        openAIClient.chat().completions().create(request);

        var span = testHarness.awaitExportedSpans().get(0);
        assertEquals(
                "caller-supplied-correlation-id",
                span.getAttributes().get(AttributeKey.stringKey("request-id")),
                "a caller-set correlation header must be captured from the request");
        // The vendor's own id still lands from the response, independently.
        assertNotNull(span.getAttributes().get(AttributeKey.stringKey("x-request-id")));
    }

    // -------------------------------------------------------------------------
    // Status-code handling
    //
    // Driven against a stub delegate rather than the VCR proxy: a 3xx or a 1xx cannot
    // realistically be recorded from the vendor, and those are exactly the statuses that
    // distinguish "non-2xx" from "4xx and up".
    // -------------------------------------------------------------------------

    /**
     * openai-java's ErrorHandler treats success as exactly 200..299 and raises everything else to
     * the caller, so every one of these must mark the span failed. 304 is the realistic case: the
     * http client does not follow it, so it arrives here as a final response.
     */
    @ParameterizedTest(name = "status {0}")
    @ValueSource(ints = {100, 204, 301, 304, 400, 500})
    @SneakyThrows
    void nonSuccessStatusMarksSpanFailed(int statusCode) {
        var client =
                new TracingHttpClient(
                        testHarness.openTelemetry(), new StubHttpClient(statusCode, "{}"));

        try (var response = client.execute(chatCompletionsRequest(), RequestOptions.none())) {
            response.body().readAllBytes();
        }

        var span = testHarness.awaitExportedSpans().get(0);
        boolean isSuccess = statusCode >= 200 && statusCode < 300;
        assertEquals(
                isSuccess ? StatusCode.UNSET : StatusCode.ERROR,
                span.getStatus().getStatusCode(),
                "status "
                        + statusCode
                        + (isSuccess ? " should not" : " should")
                        + " mark the span failed");
    }

    /** The correlation header is still captured on a status the SDK will reject. */
    @Test
    @SneakyThrows
    void requestIdIsCapturedOnANonSuccessStatus() {
        var client =
                new TracingHttpClient(testHarness.openTelemetry(), new StubHttpClient(304, ""));

        try (var response = client.execute(chatCompletionsRequest(), RequestOptions.none())) {
            response.body().readAllBytes();
        }

        var span = testHarness.awaitExportedSpans().get(0);
        assertEquals(
                "req_stubbed",
                span.getAttributes().get(AttributeKey.stringKey("x-request-id")),
                "an empty-bodied non-2xx must still yield the vendor request id");
    }

    @Test
    void nonJsonResponseTaggingIsBestEffort() {
        var client =
                new TracingHttpClient(
                        testHarness.openTelemetry(),
                        new StubHttpClient(502, "<html>Bad Gateway</html>"));

        assertDoesNotThrow(
                () -> {
                    try (var response =
                            client.execute(chatCompletionsRequest(), RequestOptions.none())) {
                        response.body().readAllBytes();
                    }
                });

        var span = testHarness.awaitExportedSpans().get(0);
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals(
                "req_stubbed", span.getAttributes().get(AttributeKey.stringKey("x-request-id")));
    }

    @ParameterizedTest(name = "async={0}, {1}")
    @CsvSource({
        "false, images/generations, true",
        "true, v1/images/generations, true",
        "false, v1/images/edits, false",
        "true, openai/deployments/media/images/edits, false",
        "false, openai/deployments/media/images/variations, false",
        "true, images/variations, false",
        "false, audio/speech, true",
        "true, openai/deployments/media/audio/speech, true",
        "false, v1/audio/transcriptions, false",
        "true, openai/deployments/media/audio/transcriptions, false",
        "false, openai/deployments/media/audio/translations, false",
        "true, v1/audio/translations, false",
        "false, v1/moderations, true",
        "true, openai/deployments/media/moderations, true"
    })
    @SneakyThrows
    void mediaInferenceRequestsAreLlmSpans(boolean async, String path, boolean jsonRequest) {
        String input = "{\"model\":\"media-test-model\",\"input\":\"A blue circle.\"}";
        var request =
                testRequest(
                        path,
                        HttpMethod.POST,
                        jsonRequest
                                ? (async ? "APPLICATION/JSON; charset=utf-8" : "application/json")
                                : "multipart/form-data; boundary=media-test",
                        jsonRequest ? input : null);
        boolean speech = path.endsWith("audio/speech");
        String jsonResponse =
                path.endsWith("moderations")
                        ? "{\"id\":\"modr_test\",\"results\":[{\"flagged\":false}]}"
                        : path.contains("images/")
                                ? "{\"data\":[{\"b64_json\":\"aW1hZ2U=\"}]}"
                                : "{\"text\":\"A blue circle.\"}";
        byte[] payload =
                speech
                        ? new byte[] {'R', 'I', 'F', 'F', 0, (byte) 0xff, (byte) 0x80, 1}
                        : jsonResponse.getBytes(StandardCharsets.UTF_8);
        var delegate = new PassthroughHttpClient();
        delegate.response =
                new StubHttpClient(
                                200,
                                payload,
                                speech
                                        ? (async ? "application/octet-stream" : "audio/wav")
                                        : "application/json")
                        .execute(request, RequestOptions.none());
        var client = new TracingHttpClient(testHarness.openTelemetry(), delegate);
        var parent =
                testHarness
                        .openTelemetry()
                        .getTracer("media-test")
                        .spanBuilder("parent")
                        .startSpan();
        try {
            HttpResponse response;
            if (async) {
                // Exercise context restoration without a thread-local parent.
                var context = parent.getSpanContext();
                var withContext =
                        request.toBuilder()
                                .replaceHeaders(
                                        ContextCapturingProxy.CONTEXT_HEADER,
                                        "00-"
                                                + context.getTraceId()
                                                + "-"
                                                + context.getSpanId()
                                                + "-01")
                                .build();
                var future = client.executeAsync(withContext, RequestOptions.none());
                assertFalse(future.isDone());
                delegate.pending.complete(delegate.response);
                response = future.join();
            } else {
                try (var ignored = parent.makeCurrent()) {
                    response = client.execute(request, RequestOptions.none());
                }
            }
            assertTrue(
                    testHarness.awaitExportedSpans().isEmpty(),
                    "response consumption owns span lifetime");
            assertTrue(
                    delegate.sentRequest
                            .headers()
                            .values(ContextCapturingProxy.CONTEXT_HEADER)
                            .isEmpty());
            if (jsonRequest) {
                var sentBody = new java.io.ByteArrayOutputStream();
                delegate.sentRequest.body().writeTo(sentBody);
                assertEquals(input, sentBody.toString(StandardCharsets.UTF_8));
            } else {
                assertSame(
                        request.body(),
                        delegate.sentRequest.body(),
                        "multipart uploads must not be consumed or wrapped by instrumentation");
            }
            try (response) {
                assertEquals(200, response.statusCode());
                assertArrayEquals(payload, response.body().readAllBytes());
            }
            assertEquals(
                    1,
                    testHarness.awaitExportedSpans(1).size(),
                    "consuming the response ends its inference span");
        } finally {
            parent.end();
        }
        assertSingleMediaSpan(parent, path, jsonRequest ? "media-test-model" : null);
    }

    @ParameterizedTest
    @CsvSource({
        "false, audio/wav, 200",
        "true, application/octet-stream, 200",
        "false, application/octet-stream, 503",
        "true, audio/mpeg, 503"
    })
    @SneakyThrows
    void binaryMediaResponsesEndOnEarlyClose(boolean async, String contentType, int statusCode) {
        var request =
                testRequest(
                        "audio/speech",
                        HttpMethod.POST,
                        "application/json",
                        "{\"model\":\"tts-1\"}");
        var client =
                new TracingHttpClient(
                        testHarness.openTelemetry(),
                        new StubHttpClient(
                                statusCode, new byte[] {42, 0, (byte) 0xff}, contentType));
        var response =
                async
                        ? client.executeAsync(request, RequestOptions.none()).join()
                        : client.execute(request, RequestOptions.none());
        try (response) {
            assertEquals(statusCode, response.statusCode());
            assertEquals(42, response.body().read());
            assertTrue(testHarness.awaitExportedSpans().isEmpty());
        }
        response.close();
        var spans = testHarness.awaitExportedSpans(1);
        assertEquals(1, spans.size(), "repeated close must not export another span");
        var span = spans.get(0);
        assertEquals(
                statusCode == 200 ? StatusCode.UNSET : StatusCode.ERROR,
                span.getStatus().getStatusCode());
        assertTrue(span.hasEnded());
        assertTrue(span.getEvents().isEmpty());
        assertEquals(
                "req_stubbed", span.getAttributes().get(AttributeKey.stringKey("x-request-id")));
        assertNull(span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json")));
        assertEquals(
                "llm",
                JSON_MAPPER
                        .readTree(
                                span.getAttributes()
                                        .get(AttributeKey.stringKey("braintrust.span_attributes")))
                        .path("type")
                        .asText());
    }

    @ParameterizedTest
    @CsvSource({
        "false, batches, POST, 200",
        "true, v1/batches, POST, 200",
        "false, v1/batches, GET, 200",
        "true, batches, GET, 200",
        "false, batches/batch_123, GET, 200",
        "true, v1/batches/batch_123, GET, 200",
        "false, batches/batch_123/cancel, POST, 200",
        "true, v1/batches/batch_123/cancel, POST, 200",
        "false, v1/batches/batch_123, GET, 304",
        "true, batches/batch_123, GET, 429",
        "true, files, POST, 200",
        "false, v1/files/file_123/content, GET, 200",
        "true, models, GET, 200",
        "false, chat/completions, GET, 200",
        "true, v1/responses/resp_123, GET, 200",
        "false, vector_stores, POST, 200",
        "false, v1/images/generations, GET, 200",
        "true, openai/deployments/media/audio/transcriptions, GET, 200",
        "false, v1/moderations, GET, 200",
        "true, v1/images/generations/image_123, POST, 200",
        "false, v1/audio/transcriptions/transcript_123, POST, 200",
        "true, v1/files/generations, POST, 200",
        "false, v1/files/edits, POST, 200",
        "true, v1/files/variations, POST, 200",
        "false, v1/files/speech, POST, 200",
        "true, v1/files/transcriptions, POST, 200",
        "false, v1/files/translations, POST, 200",
        "false, batches, POST, 500",
        "true, v1/batches, POST, 502"
    })
    @SneakyThrows
    void nonLlmResponsesBypassLlmTagging(
            boolean async, String path, HttpMethod method, int statusCode) {
        String payload =
                "{\"id\":\"batch_123\",\"model\":\"not-an-llm\","
                        + "\"output\":[{\"type\":\"web_search_call\",\"id\":\"tool_1\"}]}\n"
                        + "{\"custom_id\":\"second-result\"}\n";
        var request = unbufferedRequest(path, method);
        var delegate = new PassthroughHttpClient();
        delegate.response =
                new StubHttpClient(statusCode, payload).execute(request, RequestOptions.none());
        var client = new TracingHttpClient(testHarness.openTelemetry(), delegate);
        var parent =
                testHarness
                        .openTelemetry()
                        .getTracer("batch-test")
                        .spanBuilder("parent")
                        .startSpan();
        HttpResponse response;
        if (async) {
            // Simulate the service proxy's context header on a thread with no current parent.
            var context = parent.getSpanContext();
            var withContext =
                    request.toBuilder()
                            .replaceHeaders(
                                    ContextCapturingProxy.CONTEXT_HEADER,
                                    "00-"
                                            + context.getTraceId()
                                            + "-"
                                            + context.getSpanId()
                                            + "-01")
                            .build();
            var future = client.executeAsync(withContext, RequestOptions.none());
            assertFalse(future.isDone());
            assertTrue(delegate.requestSpan.isRecording());
            delegate.pending.complete(delegate.response);
            response = future.join();
        } else {
            try (var ignored = parent.makeCurrent()) {
                response = client.execute(request, RequestOptions.none());
            }
        }
        assertSame(delegate.response, response, "non-LLM responses must not be wrapped");
        assertSame(
                request.body(), delegate.sentRequest.body(), "non-LLM bodies must not be buffered");
        assertTrue(
                delegate.sentRequest
                        .headers()
                        .values(ContextCapturingProxy.CONTEXT_HEADER)
                        .isEmpty());
        assertFalse(delegate.requestSpan.isRecording(), "transport completion ends the span");
        try (response) {
            assertEquals(
                    payload, new String(response.body().readAllBytes(), StandardCharsets.UTF_8));
        }
        response.close();
        parent.end();
        assertHttpSpan(parent, statusCode < 200 || statusCode >= 300, false);
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "true, true"})
    void nonLlmTransportFailuresAreGeneric(boolean async, boolean immediateFailure) {
        var request = unbufferedRequest("v1/batches", HttpMethod.POST);
        var failure = new IllegalStateException("transport failed");
        var delegate = new PassthroughHttpClient();
        delegate.failure = failure;
        delegate.immediateFailure = immediateFailure;
        var client = new TracingHttpClient(testHarness.openTelemetry(), delegate);
        var parent =
                testHarness
                        .openTelemetry()
                        .getTracer("batch-test")
                        .spanBuilder("parent")
                        .startSpan();
        try (var ignored = parent.makeCurrent()) {
            if (!async) {
                assertSame(
                        failure,
                        assertThrows(
                                IllegalStateException.class,
                                () -> client.execute(request, RequestOptions.none())));
            } else if (immediateFailure) {
                assertSame(
                        failure,
                        assertThrows(
                                IllegalStateException.class,
                                () -> client.executeAsync(request, RequestOptions.none())));
            } else {
                var future = client.executeAsync(request, RequestOptions.none());
                assertTrue(delegate.requestSpan.isRecording());
                delegate.pending.completeExceptionally(failure);
                assertSame(
                        failure,
                        assertThrows(java.util.concurrent.CompletionException.class, future::join)
                                .getCause());
            }
        }
        assertFalse(delegate.requestSpan.isRecording());
        parent.end();
        assertHttpSpan(parent, true, true);
    }

    @ParameterizedTest
    @CsvSource({"200, false", "503, false", "200, true"})
    void cancelledNonLlmFutureStillEndsSpanOnTransportCompletion(
            int statusCode, boolean transportFailure) throws Exception {
        var request = unbufferedRequest("v1/batches", HttpMethod.POST);
        var delegate = new PassthroughHttpClient();
        var client = new TracingHttpClient(testHarness.openTelemetry(), delegate);
        var parent =
                testHarness
                        .openTelemetry()
                        .getTracer("batch-test")
                        .spanBuilder("parent")
                        .startSpan();
        try (var response =
                new StubHttpClient(statusCode, "{}").execute(request, RequestOptions.none())) {
            CompletableFuture<HttpResponse> future;
            try (var ignored = parent.makeCurrent()) {
                future = client.executeAsync(request, RequestOptions.none());
            }
            assertTrue(future.cancel(true));
            assertFalse(delegate.pending.isDone());
            assertTrue(delegate.requestSpan.isRecording());
            if (transportFailure) {
                delegate.pending.completeExceptionally(
                        new IllegalStateException("transport failed"));
            } else {
                delegate.pending.complete(response);
            }
            assertTrue(future.isCancelled());
            assertFalse(delegate.requestSpan.isRecording());
        } finally {
            parent.end();
        }
        assertHttpSpan(parent, transportFailure || statusCode >= 300, transportFailure);
    }

    private void assertSingleMediaSpan(Span parent, String path, String model) {
        var spans = testHarness.awaitExportedSpans(2);
        assertEquals(2, spans.size(), "one parent and one ended inference span");
        var span =
                spans.stream()
                        .filter(s -> !s.getSpanId().equals(parent.getSpanContext().getSpanId()))
                        .findFirst()
                        .orElseThrow();
        assertMediaSpan(span, parent, path, model);
    }

    @SneakyThrows
    private static void assertMediaSpan(SpanData span, Span parent, String path, String model) {
        assertTrue(span.hasEnded());
        assertEquals(parent.getSpanContext().getTraceId(), span.getTraceId());
        assertEquals(parent.getSpanContext().getSpanId(), span.getParentSpanId());
        assertEquals(StatusCode.UNSET, span.getStatus().getStatusCode());
        assertTrue(
                span.getEvents().isEmpty(), "media bodies must not cause instrumentation errors");
        var attributes = span.getAttributes();
        String spanAttributes =
                attributes.get(AttributeKey.stringKey("braintrust.span_attributes"));
        assertNotNull(spanAttributes);
        assertEquals("llm", JSON_MAPPER.readTree(spanAttributes).path("type").asText());
        var metadata = mediaMetadata(span);
        assertEquals("openai", metadata.path("provider").asText());
        assertEquals(path, metadata.path("request_path").asText());
        assertEquals("POST", metadata.path("request_method").asText());
        if (model != null) {
            assertEquals(model, metadata.path("model").asText());
        }
        String requestId = attributes.get(AttributeKey.stringKey("x-request-id"));
        assertNotNull(requestId);
        assertFalse(requestId.isBlank());
    }

    @SneakyThrows
    private static JsonNode mediaMetadata(SpanData span) {
        String metadata = span.getAttributes().get(AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(metadata);
        return JSON_MAPPER.readTree(metadata);
    }

    private void assertHttpSpan(Span parent, boolean failed, boolean exception) {
        var spans = testHarness.awaitExportedSpans();
        assertEquals(2, spans.size(), "one parent and one ended http span, no LLM/tool children");
        var span =
                spans.stream()
                        .filter(s -> s.getName().equals("openai.http"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(parent.getSpanContext().getSpanId(), span.getParentSpanId());
        assertTrue(span.hasEnded());
        for (String attribute :
                List.of(
                        "braintrust.span_attributes",
                        "braintrust.input_json",
                        "braintrust.output_json",
                        "braintrust.metadata",
                        "braintrust.metrics")) {
            assertNull(span.getAttributes().get(AttributeKey.stringKey(attribute)), attribute);
        }
        assertEquals(
                failed ? StatusCode.ERROR : StatusCode.UNSET, span.getStatus().getStatusCode());
        assertEquals(exception ? 1 : 0, span.getEvents().size());
        if (exception) {
            assertEquals("exception", span.getEvents().get(0).getName());
        }
    }

    private static HttpRequest unbufferedRequest(String path, HttpMethod method) {
        return testRequest(path, method, "application/jsonl", null);
    }

    private static HttpRequest testRequest(
            String path, HttpMethod method, String contentType, String json) {
        return HttpRequest.builder()
                .method(method)
                .baseUrl("https://api.openai.com/v1")
                .addPathSegments(path.split("/"))
                .body(
                        new HttpRequestBody() {
                            @Override
                            @SneakyThrows
                            public void writeTo(OutputStream stream) {
                                assertNotNull(
                                        json,
                                        "Instrumentation must not consume non-JSON request bodies");
                                stream.write(json.getBytes(StandardCharsets.UTF_8));
                            }

                            @Override
                            public String contentType() {
                                return contentType;
                            }

                            @Override
                            public long contentLength() {
                                return -1;
                            }

                            @Override
                            public boolean repeatable() {
                                return false;
                            }

                            @Override
                            public void close() {}
                        })
                .build();
    }

    private static final class PassthroughHttpClient implements com.openai.core.http.HttpClient {
        private final CompletableFuture<HttpResponse> pending = new CompletableFuture<>();
        private HttpResponse response;
        private RuntimeException failure;
        private boolean immediateFailure;
        private HttpRequest sentRequest;
        private Span requestSpan;

        @Override
        public HttpResponse execute(HttpRequest request, RequestOptions options) {
            sentRequest = request;
            requestSpan = Span.current();
            if (failure != null) {
                throw failure;
            }
            return response;
        }

        @Override
        public CompletableFuture<HttpResponse> executeAsync(
                HttpRequest request, RequestOptions options) {
            sentRequest = request;
            requestSpan = Span.current();
            if (immediateFailure) {
                throw failure;
            }
            return pending;
        }

        @Override
        public void close() {}
    }

    private static HttpRequest chatCompletionsRequest() {
        return HttpRequest.builder()
                .method(HttpMethod.POST)
                .baseUrl("https://api.openai.com/v1")
                .addPathSegments("chat", "completions")
                .build();
    }

    /** Returns a canned response; never touches the network. */
    private record StubHttpClient(int statusCode, byte[] body, String contentType)
            implements com.openai.core.http.HttpClient {
        private StubHttpClient(int statusCode, String body) {
            this(statusCode, body.getBytes(StandardCharsets.UTF_8), "application/json");
        }

        @Override
        public HttpResponse execute(HttpRequest request, RequestOptions requestOptions) {
            return new HttpResponse() {
                @Override
                public int statusCode() {
                    return statusCode;
                }

                @Override
                public Headers headers() {
                    return Headers.builder()
                            .put("x-request-id", "req_stubbed")
                            .put("content-type", contentType)
                            .build();
                }

                @Override
                public InputStream body() {
                    return new ByteArrayInputStream(body);
                }

                @Override
                public void close() {}
            };
        }

        @Override
        public CompletableFuture<HttpResponse> executeAsync(
                HttpRequest request, RequestOptions requestOptions) {
            return CompletableFuture.completedFuture(execute(request, requestOptions));
        }

        @Override
        public void close() {}
    }
}
