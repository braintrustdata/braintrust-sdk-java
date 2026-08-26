package dev.braintrust.instrumentation.anthropic.v2_2_0;

import static org.junit.jupiter.api.Assertions.*;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.Headers;
import com.anthropic.core.http.HttpMethod;
import com.anthropic.core.http.HttpRequest;
import com.anthropic.core.http.HttpResponse;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.TestHarness;
import dev.braintrust.instrumentation.Instrumenter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
