package dev.braintrust.instrumentation.anthropic.v2_2_0;

import static org.junit.jupiter.api.Assertions.*;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.Headers;
import com.anthropic.core.http.HttpMethod;
import com.anthropic.core.http.HttpRequest;
import com.anthropic.core.http.HttpRequestBody;
import com.anthropic.core.http.HttpResponse;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.batches.BatchListParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.TestHarness;
import dev.braintrust.instrumentation.Instrumenter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

public class BraintrustAnthropicTest {
    private static final String TEST_MODEL = "claude-haiku-4-5";
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @BeforeAll
    public static void beforeAll() {
        var instrumentation = ByteBuddyAgent.install();
        Instrumenter.install(instrumentation, BraintrustAnthropicTest.class.getClassLoader());
    }

    private TestHarness testHarness;

    @BeforeEach
    void beforeEach() {
        testHarness = TestHarness.setup();
    }

    @SneakyThrows
    private static void assertInstrumentationOrigin(io.opentelemetry.sdk.trace.data.SpanData span) {
        JsonNode instrumentation =
                JSON_MAPPER
                        .readTree(
                                span.getAttributes()
                                        .get(AttributeKey.stringKey("braintrust.context_json")))
                        .path("span_origin")
                        .path("instrumentation");
        assertEquals("anthropic", instrumentation.path("name").asText());
        assertEquals(
                System.getProperty("braintrust.muzzle.minimumVersion"),
                instrumentation.path("version").asText(),
                "span origin version must match the minimum passing muzzle version");
    }

    @Test
    @SneakyThrows
    void testBatchListAsync() {
        AnthropicClient anthropicClient =
                AnthropicOkHttpClient.builder()
                        .baseUrl(testHarness.anthropicBaseUrl())
                        .apiKey(testHarness.anthropicApiKey())
                        .build();
        var parent =
                testHarness
                        .openTelemetry()
                        .getTracer("batch-test")
                        .spanBuilder("parent")
                        .startSpan();
        try (var ignored = parent.makeCurrent()) {
            var page =
                    anthropicClient
                            .async()
                            .messages()
                            .batches()
                            .list(BatchListParams.builder().limit(1L).build())
                            .get(5, TimeUnit.MINUTES);
            assertTrue(page.response().data().size() <= 1);
            if (page.response().hasMore()) {
                assertEquals(1, page.response().data().size());
            }
        } finally {
            parent.end();
        }
        assertHttpSpan(parent, false, false);
        for (var span : testHarness.awaitExportedSpans()) {
            assertEquals(parent.getSpanContext().getTraceId(), span.getTraceId());
        }
    }

    @Test
    @SneakyThrows
    void testWrapAnthropic() {
        AnthropicClient anthropicClient =
                AnthropicOkHttpClient.builder()
                        .baseUrl(testHarness.anthropicBaseUrl())
                        .apiKey(testHarness.anthropicApiKey())
                        .build();

        var request =
                MessageCreateParams.builder()
                        .model(Model.of(TEST_MODEL))
                        .system("You are a helpful assistant")
                        .addUserMessage("What is the capital of France?")
                        .maxTokens(50)
                        .temperature(0.0)
                        .build();

        var response = anthropicClient.messages().create(request);

        // Verify the response
        assertNotNull(response);
        assertNotNull(response.id());
        var contentBlock = response.content().get(0);
        assertTrue(contentBlock.isText());
        assertNotNull(contentBlock.asText().text());

        // Verify spans were exported
        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var span = spans.get(0);
        assertInstrumentationOrigin(span);

        assertFalse(span.getName().isEmpty(), "span name should be non-empty");

        // Verify span_attributes
        String spanAttributesJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.span_attributes"));
        assertNotNull(spanAttributesJson);
        JsonNode spanAttributes = JSON_MAPPER.readTree(spanAttributesJson);
        assertEquals("llm", spanAttributes.get("type").asText());

        // Verify metadata
        String metadataJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(metadataJson);
        JsonNode metadata = JSON_MAPPER.readTree(metadataJson);
        assertEquals("anthropic", metadata.get("provider").asText());
        assertTrue(
                metadata.get("model").asText().startsWith("claude-haiku-4"),
                "model should start with claude-haiku-4");

        // Verify input
        String inputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.input_json"));
        assertNotNull(inputJson);
        JsonNode input = JSON_MAPPER.readTree(inputJson);
        assertTrue(input.isArray());
        assertTrue(input.size() > 0);

        // Verify output — full Message object
        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson);
        JsonNode outputMessage = JSON_MAPPER.readTree(outputJson);
        assertNotNull(outputMessage.get("id"));
        assertEquals("message", outputMessage.get("type").asText());
        assertEquals("assistant", outputMessage.get("role").asText());
        assertNotNull(outputMessage.get("content").get(0).get("text"));
        assertTrue(outputMessage.get("usage").get("output_tokens").asInt() > 0);
        assertTrue(outputMessage.get("usage").get("input_tokens").asInt() > 0);

        // Verify metrics — tokens; non-streaming should NOT have time_to_first_token
        String metricsJson = span.getAttributes().get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson);
        JsonNode metrics = JSON_MAPPER.readTree(metricsJson);
        assertTrue(metrics.has("prompt_tokens"), "prompt_tokens should be present");
        assertTrue(metrics.has("completion_tokens"), "completion_tokens should be present");
        assertTrue(metrics.has("tokens"), "tokens should be present");
        assertFalse(
                metrics.has("time_to_first_token"),
                "time_to_first_token should not be present for non-streaming");
    }

    @Test
    @SneakyThrows
    void testWrapAnthropicStreaming() {
        AnthropicClient anthropicClient =
                AnthropicOkHttpClient.builder()
                        .baseUrl(testHarness.anthropicBaseUrl())
                        .apiKey(testHarness.anthropicApiKey())
                        .build();

        var request =
                MessageCreateParams.builder()
                        .model(Model.of(TEST_MODEL))
                        .system("You are a helpful assistant")
                        .addUserMessage("What is the capital of France?")
                        .maxTokens(50)
                        .temperature(0.0)
                        .build();

        StringBuilder fullResponse = new StringBuilder();
        try (var stream = anthropicClient.messages().createStreaming(request)) {
            stream.stream()
                    .forEach(
                            event -> {
                                if (event.contentBlockDelta().isPresent()) {
                                    var delta = event.contentBlockDelta().get().delta();
                                    if (delta.text().isPresent()) {
                                        fullResponse.append(delta.text().get().text());
                                    }
                                }
                            });
        }

        assertFalse(fullResponse.toString().isEmpty());

        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var span = spans.get(0);
        assertInstrumentationOrigin(span);

        assertFalse(span.getName().isEmpty(), "span name should be non-empty");

        // Verify metadata
        String metadataJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(metadataJson);
        JsonNode metadata = JSON_MAPPER.readTree(metadataJson);
        assertEquals("anthropic", metadata.get("provider").asText());

        // Verify input
        assertNotNull(span.getAttributes().get(AttributeKey.stringKey("braintrust.input_json")));

        // Verify output — full Message object assembled from SSE stream
        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson);
        JsonNode outputMessage = JSON_MAPPER.readTree(outputJson);
        assertEquals("assistant", outputMessage.get("role").asText());
        assertFalse(
                outputMessage.get("content").get(0).get("text").asText().isEmpty(),
                "content should not be empty");

        // Verify metrics — tokens and time_to_first_token
        String metricsJson = span.getAttributes().get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson);
        JsonNode metrics = JSON_MAPPER.readTree(metricsJson);
        assertTrue(metrics.has("prompt_tokens"), "prompt_tokens should be present");
        assertTrue(metrics.has("completion_tokens"), "completion_tokens should be present");
        assertTrue(metrics.has("time_to_first_token"), "time_to_first_token should be present");
        assertTrue(
                metrics.get("time_to_first_token").asDouble() >= 0.0,
                "time_to_first_token should be non-negative");
    }

    /**
     * Unlike {@link #testWrapAnthropicAsync()}, which derives the async view from an instrumented
     * sync client via {@code .async()}, this builds {@code AnthropicOkHttpClientAsync} directly —
     * exercising the async-builder auto-instrumentation hook.
     *
     * <p>Also verifies context linking: the SDK dispatches async requests onto a worker pool, but
     * the LLM span must still parent to the span that was current when the request was kicked off.
     */
    @Test
    @SneakyThrows
    void testDirectAsyncClientParenting() {
        // Built OUTSIDE any span on purpose: parenting must come from the context at request
        // time, not from the context at client-construction time.
        com.anthropic.client.AnthropicClientAsync anthropicClient =
                com.anthropic.client.okhttp.AnthropicOkHttpClientAsync.builder()
                        .baseUrl(testHarness.anthropicBaseUrl())
                        .apiKey(testHarness.anthropicApiKey())
                        .build();

        var request =
                MessageCreateParams.builder()
                        .model(Model.of(TEST_MODEL))
                        .system("You are a helpful assistant")
                        .addUserMessage("What is the capital of France?")
                        .maxTokens(50)
                        .temperature(0.0)
                        .build();

        var parentSpan =
                testHarness.openTelemetry().getTracer("test").spanBuilder("foo").startSpan();
        try (var ignored = parentSpan.makeCurrent()) {
            var response = anthropicClient.messages().create(request).get();
            assertNotNull(response);
            assertNotNull(response.id());
        } finally {
            parentSpan.end();
        }

        var spans = testHarness.awaitExportedSpans(2);
        assertEquals(2, spans.size());
        var llmSpan =
                spans.stream().filter(s -> !"foo".equals(s.getName())).findFirst().orElseThrow();
        assertInstrumentationOrigin(llmSpan);
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
    void testWrapAnthropicAsync() {
        AnthropicClient anthropicClient =
                AnthropicOkHttpClient.builder()
                        .baseUrl(testHarness.anthropicBaseUrl())
                        .apiKey(testHarness.anthropicApiKey())
                        .build();

        var request =
                MessageCreateParams.builder()
                        .model(Model.of(TEST_MODEL))
                        .system("You are a helpful assistant")
                        .addUserMessage("What is the capital of France?")
                        .maxTokens(50)
                        .temperature(0.0)
                        .build();

        var response = anthropicClient.async().messages().create(request).get();

        assertNotNull(response);
        assertNotNull(response.id());
        var contentBlock = response.content().get(0);
        assertTrue(contentBlock.isText());
        assertNotNull(contentBlock.asText().text());

        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var span = spans.get(0);

        assertFalse(span.getName().isEmpty(), "span name should be non-empty");

        String spanAttributesJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.span_attributes"));
        assertNotNull(spanAttributesJson);
        JsonNode spanAttributes = JSON_MAPPER.readTree(spanAttributesJson);
        assertEquals("llm", spanAttributes.get("type").asText());

        String metadataJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(metadataJson);
        JsonNode metadata = JSON_MAPPER.readTree(metadataJson);
        assertEquals("anthropic", metadata.get("provider").asText());

        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson);
        JsonNode outputMessage = JSON_MAPPER.readTree(outputJson);
        assertEquals("message", outputMessage.get("type").asText());
        assertEquals("assistant", outputMessage.get("role").asText());
        assertNotNull(outputMessage.get("content").get(0).get("text"));

        String metricsJson = span.getAttributes().get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson);
        JsonNode metrics = JSON_MAPPER.readTree(metricsJson);
        assertTrue(metrics.has("prompt_tokens"));
        assertTrue(metrics.has("completion_tokens"));
        assertTrue(metrics.has("tokens"));
        assertFalse(
                metrics.has("time_to_first_token"),
                "time_to_first_token should not be present for non-streaming");
    }

    @Test
    @SneakyThrows
    void testWrapAnthropicAsyncStreaming() {
        AnthropicClient anthropicClient =
                AnthropicOkHttpClient.builder()
                        .baseUrl(testHarness.anthropicBaseUrl())
                        .apiKey(testHarness.anthropicApiKey())
                        .build();

        var request =
                MessageCreateParams.builder()
                        .model(Model.of(TEST_MODEL))
                        .system("You are a helpful assistant")
                        .addUserMessage("What is the capital of France?")
                        .maxTokens(50)
                        .temperature(0.0)
                        .build();

        var fullResponse = new StringBuilder();
        var stream = anthropicClient.async().messages().createStreaming(request);
        stream.subscribe(
                event -> {
                    if (event.contentBlockDelta().isPresent()) {
                        var delta = event.contentBlockDelta().get().delta();
                        if (delta.text().isPresent()) {
                            fullResponse.append(delta.text().get().text());
                        }
                    }
                });
        stream.onCompleteFuture().get(30, TimeUnit.SECONDS);

        assertFalse(fullResponse.toString().isEmpty());

        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var span = spans.get(0);

        assertFalse(span.getName().isEmpty(), "span name should be non-empty");

        assertNotNull(span.getAttributes().get(AttributeKey.stringKey("braintrust.input_json")));

        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson);
        JsonNode outputMessage = JSON_MAPPER.readTree(outputJson);
        assertEquals("assistant", outputMessage.get("role").asText());
        assertFalse(outputMessage.get("content").get(0).get("text").asText().isEmpty());

        String metricsJson = span.getAttributes().get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson);
        JsonNode metrics = JSON_MAPPER.readTree(metricsJson);
        assertTrue(metrics.has("prompt_tokens"));
        assertTrue(metrics.has("completion_tokens"));
        assertTrue(
                metrics.has("time_to_first_token"),
                "time_to_first_token should be present for streaming");
        assertTrue(metrics.get("time_to_first_token").asDouble() >= 0.0);
    }

    @Test
    @SneakyThrows
    void testWrapAnthropicBeta() {
        AnthropicClient anthropicClient =
                AnthropicOkHttpClient.builder()
                        .baseUrl(testHarness.anthropicBaseUrl())
                        .apiKey(testHarness.anthropicApiKey())
                        .build();

        var request =
                com.anthropic.models.beta.messages.MessageCreateParams.builder()
                        .model(Model.of(TEST_MODEL))
                        .system("You are a helpful assistant")
                        .addUserMessage("What is the capital of France?")
                        .maxTokens(50)
                        .temperature(0.0)
                        .build();

        var response = anthropicClient.beta().messages().create(request);

        assertNotNull(response);
        assertNotNull(response.id());
        var contentBlock = response.content().get(0);
        assertTrue(contentBlock.isText());
        assertNotNull(contentBlock.asText().text());

        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var span = spans.get(0);

        assertFalse(span.getName().isEmpty(), "span name should be non-empty");

        // Verify span_attributes
        String spanAttributesJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.span_attributes"));
        assertNotNull(spanAttributesJson);
        JsonNode spanAttributes = JSON_MAPPER.readTree(spanAttributesJson);
        assertEquals("llm", spanAttributes.get("type").asText());

        // Verify metadata
        String metadataJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(metadataJson);
        JsonNode metadata = JSON_MAPPER.readTree(metadataJson);
        assertEquals("anthropic", metadata.get("provider").asText());
        assertTrue(
                metadata.get("model").asText().startsWith("claude-haiku-4"),
                "model should start with claude-haiku-4");

        // Verify input
        String inputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.input_json"));
        assertNotNull(inputJson);
        JsonNode input = JSON_MAPPER.readTree(inputJson);
        assertTrue(input.isArray());
        assertTrue(input.size() > 0);

        // Verify output — full BetaMessage object
        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson);
        JsonNode outputMessage = JSON_MAPPER.readTree(outputJson);
        assertNotNull(outputMessage.get("id"));
        assertEquals("message", outputMessage.get("type").asText());
        assertEquals("assistant", outputMessage.get("role").asText());
        assertNotNull(outputMessage.get("content").get(0).get("text"));
        assertTrue(outputMessage.get("usage").get("output_tokens").asInt() > 0);
        assertTrue(outputMessage.get("usage").get("input_tokens").asInt() > 0);

        // Verify metrics — tokens; non-streaming should NOT have time_to_first_token
        String metricsJson = span.getAttributes().get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson);
        JsonNode metrics = JSON_MAPPER.readTree(metricsJson);
        assertTrue(metrics.has("prompt_tokens"), "prompt_tokens should be present");
        assertTrue(metrics.has("completion_tokens"), "completion_tokens should be present");
        assertTrue(metrics.has("tokens"), "tokens should be present");
        assertFalse(
                metrics.has("time_to_first_token"),
                "time_to_first_token should not be present for non-streaming");
    }

    @Test
    @SneakyThrows
    void testWrapAnthropicBetaStreaming() {
        AnthropicClient anthropicClient =
                AnthropicOkHttpClient.builder()
                        .baseUrl(testHarness.anthropicBaseUrl())
                        .apiKey(testHarness.anthropicApiKey())
                        .build();

        var request =
                com.anthropic.models.beta.messages.MessageCreateParams.builder()
                        .model(Model.of(TEST_MODEL))
                        .system("You are a helpful assistant")
                        .addUserMessage("What is the capital of France?")
                        .maxTokens(50)
                        .temperature(0.0)
                        .build();

        StringBuilder fullResponse = new StringBuilder();
        try (var stream = anthropicClient.beta().messages().createStreaming(request)) {
            stream.stream()
                    .forEach(
                            event -> {
                                if (event.contentBlockDelta().isPresent()) {
                                    var delta = event.contentBlockDelta().get();
                                    if (delta.delta().text().isPresent()) {
                                        fullResponse.append(delta.delta().text().get().text());
                                    }
                                }
                            });
        }

        assertFalse(fullResponse.toString().isEmpty());

        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var span = spans.get(0);

        assertFalse(span.getName().isEmpty(), "span name should be non-empty");

        // Verify metadata
        String metadataJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(metadataJson);
        JsonNode metadata = JSON_MAPPER.readTree(metadataJson);
        assertEquals("anthropic", metadata.get("provider").asText());

        // Verify input
        assertNotNull(span.getAttributes().get(AttributeKey.stringKey("braintrust.input_json")));

        // Verify output — full BetaMessage object assembled from SSE stream
        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson);
        JsonNode outputMessage = JSON_MAPPER.readTree(outputJson);
        assertEquals("assistant", outputMessage.get("role").asText());
        assertFalse(
                outputMessage.get("content").get(0).get("text").asText().isEmpty(),
                "content should not be empty");

        // Verify metrics — tokens and time_to_first_token
        String metricsJson = span.getAttributes().get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson);
        JsonNode metrics = JSON_MAPPER.readTree(metricsJson);
        assertTrue(metrics.has("prompt_tokens"), "prompt_tokens should be present");
        assertTrue(metrics.has("completion_tokens"), "completion_tokens should be present");
        assertTrue(metrics.has("time_to_first_token"), "time_to_first_token should be present");
        assertTrue(
                metrics.get("time_to_first_token").asDouble() >= 0.0,
                "time_to_first_token should be non-negative");
    }

    /**
     * When the client's HTTP layer cannot be instrumented (custom implementations, changed SDK
     * internals), wrap must return the client untouched — installing the context-capturing proxy
     * without a TracingHttpClient underneath would leak internal trace IDs to the provider via the
     * un-stripped context header.
     */
    @Test
    void testUninstrumentableClientIsLeftUntouched() {
        AnthropicClient custom =
                (AnthropicClient)
                        java.lang.reflect.Proxy.newProxyInstance(
                                AnthropicClient.class.getClassLoader(),
                                new Class<?>[] {AnthropicClient.class},
                                (proxy, method, args) -> {
                                    throw new UnsupportedOperationException(method.getName());
                                });

        AnthropicClient wrapped = BraintrustAnthropic.wrap(testHarness.openTelemetry(), custom);

        assertSame(custom, wrapped, "uninstrumentable client should not get the context proxy");
    }

    /** The context-capturing proxy must keep Object identity semantics usable (maps/sets). */
    @Test
    void testWrappedClientObjectContract() {
        AnthropicClient client =
                AnthropicOkHttpClient.builder()
                        .baseUrl(testHarness.anthropicBaseUrl())
                        .apiKey(testHarness.anthropicApiKey())
                        .build();
        AnthropicClient wrapped = BraintrustAnthropic.wrap(testHarness.openTelemetry(), client);
        AnthropicClient rewrapped = BraintrustAnthropic.wrap(testHarness.openTelemetry(), client);

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

    /**
     * Anthropic returns its correlation ID as {@code request-id} (no {@code x-} prefix, unlike
     * OpenAI) and its object ID as {@code msg_*}. Both must land on the span for streaming and
     * non-streaming alike — the header comes off the HTTP response, the object ID out of the
     * reassembled body, so the two travel independent paths.
     */
    @Test
    @SneakyThrows
    void testCorrelationIdsCaptured() {
        AnthropicClient anthropicClient =
                AnthropicOkHttpClient.builder()
                        .baseUrl(testHarness.anthropicBaseUrl())
                        .apiKey(testHarness.anthropicApiKey())
                        .build();

        var request =
                MessageCreateParams.builder()
                        .model(Model.of(TEST_MODEL))
                        .system("You are a helpful assistant")
                        .addUserMessage("What is the capital of France?")
                        .maxTokens(50)
                        .temperature(0.0)
                        .build();

        anthropicClient.messages().create(request);
        assertAnthropicIdsCaptured(testHarness.awaitExportedSpans().get(0));
    }

    @Test
    @SneakyThrows
    void testCorrelationIdsCapturedStreaming() {
        AnthropicClient anthropicClient =
                AnthropicOkHttpClient.builder()
                        .baseUrl(testHarness.anthropicBaseUrl())
                        .apiKey(testHarness.anthropicApiKey())
                        .build();

        var request =
                MessageCreateParams.builder()
                        .model(Model.of(TEST_MODEL))
                        .system("You are a helpful assistant")
                        .addUserMessage("What is the capital of France?")
                        .maxTokens(50)
                        .temperature(0.0)
                        .build();

        try (var stream = anthropicClient.messages().createStreaming(request)) {
            stream.stream().forEach(event -> {});
        }
        assertAnthropicIdsCaptured(testHarness.awaitExportedSpans().get(0));
    }

    /**
     * Asserts presence only for the header: its value is an opaque vendor string, so pinning its
     * shape would encode an assumption the provider never made.
     */
    private static void assertAnthropicIdsCaptured(io.opentelemetry.sdk.trace.data.SpanData span) {
        var attributes = span.getAttributes();

        String requestId = attributes.get(AttributeKey.stringKey("request-id"));
        assertNotNull(requestId, "request-id header must be captured");
        assertFalse(requestId.isBlank(), "request-id must not be blank");

        String responseId = attributes.get(AttributeKey.stringKey("response_id"));
        assertNotNull(responseId, "response_id must be captured from the response body");
        assertTrue(responseId.startsWith("msg_"), "unexpected response_id: " + responseId);

        assertNull(
                attributes.get(AttributeKey.stringKey("x-request-id")),
                "OpenAI's header name must not appear on an Anthropic span");
    }

    // -------------------------------------------------------------------------
    // Status-code handling
    //
    // Driven against a stub delegate rather than the VCR proxy: a 3xx or a 1xx cannot
    // realistically be recorded from the vendor, and those are exactly the statuses that
    // distinguish "non-2xx" from "4xx and up".
    // -------------------------------------------------------------------------

    /**
     * anthropic-java's ErrorHandler treats success as exactly 200..299 and raises everything else
     * to the caller, so every one of these must mark the span failed. 304 is the realistic case:
     * the http client does not follow it, so it arrives here as a final response.
     */
    @ParameterizedTest(name = "status {0}")
    @ValueSource(ints = {100, 204, 301, 304, 400, 500})
    @SneakyThrows
    void nonSuccessStatusMarksSpanFailed(int statusCode) {
        var client =
                new TracingHttpClient(
                        testHarness.openTelemetry(), new StubHttpClient(statusCode, "{}"));

        try (var response = client.execute(messagesRequest(), RequestOptions.none())) {
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

        try (var response = client.execute(messagesRequest(), RequestOptions.none())) {
            response.body().readAllBytes();
        }

        var span = testHarness.awaitExportedSpans().get(0);
        assertEquals(
                "stubbed-request-id",
                span.getAttributes().get(AttributeKey.stringKey("request-id")),
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
                    try (var response = client.execute(messagesRequest(), RequestOptions.none())) {
                        response.body().readAllBytes();
                    }
                });

        var span = testHarness.awaitExportedSpans().get(0);
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals(
                "stubbed-request-id",
                span.getAttributes().get(AttributeKey.stringKey("request-id")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1/messages", "v1/complete"})
    @SneakyThrows
    void inferenceEndpointsGetLlmSpans(String path) {
        var client =
                new TracingHttpClient(
                        testHarness.openTelemetry(),
                        new StubHttpClient(200, "{\"model\":\"claude-test\"}"));
        var request =
                HttpRequest.builder()
                        .method(HttpMethod.POST)
                        .baseUrl("https://api.anthropic.com")
                        .addPathSegments(path.split("/"))
                        .build();

        try (var response = client.execute(request, RequestOptions.none())) {
            response.body().readAllBytes();
        }

        var span = testHarness.awaitExportedSpans().get(0);
        assertNotEquals("anthropic.http", span.getName());
        JsonNode spanAttributes =
                new ObjectMapper()
                        .readTree(
                                span.getAttributes()
                                        .get(AttributeKey.stringKey("braintrust.span_attributes")));
        assertEquals("llm", spanAttributes.get("type").asText());
    }

    @ParameterizedTest
    @CsvSource({
        "false, messages/batches, POST, 200",
        "true, v1/messages/batches, POST, 200",
        "false, v1/messages/batches, GET, 200",
        "true, messages/batches, GET, 200",
        "false, messages/batches/batch_123, GET, 200",
        "true, v1/messages/batches/batch_123, GET, 200",
        "false, messages/batches/batch_123/results, GET, 200",
        "true, v1/messages/batches/batch_123/results, GET, 200",
        "false, v1/messages/batches/batch_123/cancel, POST, 200",
        "true, messages/batches/batch_123/cancel, POST, 200",
        "false, v1/messages/batches/batch_123, GET, 304",
        "true, messages/batches/batch_123, GET, 429",
        "true, v1/messages/count_tokens, POST, 200",
        "false, v1/models, GET, 200",
        "true, v1/files, POST, 200",
        "false, v1/files/file_123/content, GET, 200",
        "false, messages/batches, POST, 500",
        "true, v1/messages/batches, POST, 502"
    })
    @SneakyThrows
    void nonLlmResponsesBypassLlmTagging(
            boolean async, String path, HttpMethod method, int statusCode) {
        String payload =
                "{\"id\":\"batch_123\",\"model\":\"not-an-llm\","
                        + "\"content\":[{\"type\":\"server_tool_use\",\"name\":\"web_search\","
                        + "\"id\":\"tool_1\",\"input\":{\"query\":\"ignored\"}}]}\n"
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
        assertEquals(request.queryParams(), delegate.sentRequest.queryParams());
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
        var request = unbufferedRequest("v1/messages/batches", HttpMethod.POST);
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
        var request = unbufferedRequest("v1/messages/batches", HttpMethod.POST);
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

    private void assertHttpSpan(Span parent, boolean failed, boolean exception) {
        var spans = testHarness.awaitExportedSpans();
        assertEquals(2, spans.size(), "one parent and one ended http span, no LLM/tool children");
        var span =
                spans.stream()
                        .filter(s -> s.getName().equals("anthropic.http"))
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
        return HttpRequest.builder()
                .method(method)
                .baseUrl("https://api.anthropic.com")
                .addPathSegments(path.split("/"))
                .putQueryParam("beta", "true")
                .body(
                        new HttpRequestBody() {
                            @Override
                            public void writeTo(OutputStream stream) {
                                fail("Instrumentation must not read a non-LLM request body");
                            }

                            @Override
                            public String contentType() {
                                return "application/jsonl";
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

    private static final class PassthroughHttpClient implements com.anthropic.core.http.HttpClient {
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

    private static HttpRequest messagesRequest() {
        return HttpRequest.builder()
                .method(HttpMethod.POST)
                .baseUrl("https://api.openai.com/v1")
                .addPathSegments("v1", "messages")
                .build();
    }

    /** Returns a canned response; never touches the network. */
    private record StubHttpClient(int statusCode, String body)
            implements com.anthropic.core.http.HttpClient {

        @Override
        public HttpResponse execute(HttpRequest request, RequestOptions requestOptions) {
            return new HttpResponse() {
                @Override
                public int statusCode() {
                    return statusCode;
                }

                @Override
                public Headers headers() {
                    return Headers.builder().put("request-id", "stubbed-request-id").build();
                }

                @Override
                public InputStream body() {
                    return new ByteArrayInputStream(body.getBytes());
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
