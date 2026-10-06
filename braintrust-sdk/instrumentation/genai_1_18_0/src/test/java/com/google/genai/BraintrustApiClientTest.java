package com.google.genai;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.HttpOptions;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Transport-level coverage without credentials, network access, or recorded batch jobs. */
class BraintrustApiClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INPUT =
            "{\"batch\":{\"displayName\":\"café\",\"inputConfig\":{\"fileName\":\"files/input\"}},"
                    + "\"tools\":[{\"functionDeclarations\":[{\"name\":\"lookup\"}]}]}";
    private static final String OUTPUT =
            "{\"name\":\"batches/job\",\"usageMetadata\":{\"totalTokenCount\":123},"
                    + "\"inlinedResponses\":[{\"candidates\":[]}]}";
    private final List<SpanData> spans = new ArrayList<>();
    private SdkTracerProvider provider;
    private FakeApiClient delegate;
    private BraintrustApiClient client;

    @BeforeEach
    void setUp() {
        SpanExporter exporter =
                new SpanExporter() {
                    @Override
                    public CompletableResultCode export(Collection<SpanData> batch) {
                        spans.addAll(batch);
                        return CompletableResultCode.ofSuccess();
                    }

                    @Override
                    public CompletableResultCode flush() {
                        return CompletableResultCode.ofSuccess();
                    }

                    @Override
                    public CompletableResultCode shutdown() {
                        return CompletableResultCode.ofSuccess();
                    }
                };
        provider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        delegate = new FakeApiClient();
        client =
                new BraintrustApiClient(
                        delegate, OpenTelemetrySdk.builder().setTracerProvider(provider).build());
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "models/gemini:batchGenerateContent",
                "models/embedding:asyncBatchEmbedContent",
                "batches",
                "batches?pageSize=2&pageToken=next",
                "batches/job",
                "batches/job:cancel",
                "batchPredictionJobs",
                "batchPredictionJobs/job",
                "batchPredictionJobs/job:cancel",
                "projects/project/locations/us-central1/batchPredictionJobs",
                "projects/project/locations/us-central1/batchPredictionJobs/job:cancel",
                "/v1beta/batches/job",
                "/v1/models/gemini:batchGenerateContent",
                "v1beta/models/embedding:asyncBatchEmbedContent",
                "v1beta1/projects/project/locations/us-central1/batchPredictionJobs",
                "/v1alpha/batches/job:cancel?key=test",
                "https://generativelanguage.googleapis.com/v1beta/batches/job",
                "https://us-central1-aiplatform.googleapis.com/v1/projects/p/locations/l/batchPredictionJobs/job",
                "tunedModels/my-model:batchGenerateContent",
                "models/gemini:countTokens",
                "models/gemini",
                "files",
                "files/abc:download",
                "cachedContents/abc",
                "models/gemini:batchGenerateContentExtra",
                "models/gemini:asyncBatchEmbedContents",
                "models/gemini:generateContentExtra",
                "batchesExtra",
                "batches/job/results",
                "batches/job#fragment",
                "files/batches/job"
            })
    void nonLlmEndpointsLeavePayloadsUntouchedAcrossOverloads(String endpoint) throws Exception {
        for (boolean async : List.of(false, true)) {
            for (boolean bytes : List.of(false, true)) {
                spans.clear();
                delegate.responseClosed = false;
                try (ApiResponse response = invoke("post", endpoint, INPUT, async, bytes)) {
                    assertHttpSpan(StatusCode.OK);
                    assertFalse(delegate.responseClosed);
                    assertEquals(OUTPUT, response.getBody().string());
                }
                assertTrue(delegate.responseClosed);
            }
        }
    }

    @Test
    void nonLlmLifecyclePreservesRoutingAndBodylessRequests() throws Exception {
        for (String resource : List.of("batches", "batchPredictionJobs")) {
            for (String method : List.of("post", "get", "delete")) {
                String endpoint = method.equals("get") ? resource : resource + "/job";
                delegate.response = method.equals("delete") ? "" : "{\"batchJobs\":[]}";
                for (boolean async : List.of(false, true)) {
                    for (boolean bytes : List.of(false, true)) {
                        spans.clear();
                        try (ApiResponse response = invoke(method, endpoint, null, async, bytes)) {
                            assertHttpSpan(StatusCode.OK);
                            assertEquals(delegate.response, response.getBody().string());
                        }
                    }
                }
            }
        }
    }

    @Test
    void transportErrorsRetainPlainSpansAcrossOverloads() throws Exception {
        for (boolean async : List.of(false, true)) {
            for (boolean bytes : List.of(false, true)) {
                for (boolean immediate : async ? List.of(false, true) : List.of(true)) {
                    spans.clear();
                    delegate.fail = true;
                    delegate.throwImmediately = immediate;
                    assertThrows(
                            RuntimeException.class,
                            () -> invoke("post", "batches/job:cancel", INPUT, async, bytes));
                    assertHttpSpan(StatusCode.ERROR);
                    assertEquals(1, spans.get(0).getEvents().size());
                    assertTrue(
                            spans.get(0)
                                    .getStatus()
                                    .getDescription()
                                    .contains("batch transport failed"));
                }
            }
        }
    }

    @Test
    void nonLlmHttpErrorsAreRecordedBeforeReturningTheResponse() {
        delegate.statusCode = 404;
        for (boolean async : List.of(false, true)) {
            for (boolean bytes : List.of(false, true)) {
                spans.clear();
                assertThrows(
                        RuntimeException.class,
                        () -> invoke("get", "batches/missing", null, async, bytes));
                assertHttpSpan(StatusCode.ERROR);
                assertEquals("exception", spans.get(0).getEvents().get(0).getName());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "models/gemini:generateContent",
                "models/gemini:streamGenerateContent",
                "models/embedding:batchEmbedContents",
                "models/embedding:embedContent",
                "models/gemini:generateContent?next=batches/job",
                "tunedModels/my-model:generateContent",
                "projects/p/locations/l/publishers/google/models/gemini:generateContent",
                "/v1beta/models/embedding:batchEmbedContents"
            })
    void llmEndpointsAreTagged(String endpoint) throws Exception {
        String input = "{\"contents\":[{\"parts\":[{\"text\":\"hello\"}]}]}";
        for (boolean async : List.of(false, true)) {
            for (boolean bytes : List.of(false, true)) {
                spans.clear();
                try (ApiResponse response = invoke("post", endpoint, input, async, bytes)) {
                    assertEquals(OUTPUT, response.getBody().string());
                    assertEquals(1, spans.size());
                    assertEquals(
                            "llm",
                            JSON.readTree(attribute("braintrust.span_attributes"))
                                    .path("type")
                                    .asText());
                    assertEquals(
                            123,
                            JSON.readTree(attribute("braintrust.metrics")).path("tokens").asInt());
                    assertTrue(JSON.readTree(attribute("braintrust.input_json")).has("contents"));
                    assertNotEquals("google.http", spans.get(0).getName());
                    if (endpoint.equals("models/embedding:batchEmbedContents")) {
                        assertEquals("batch_embed_contents", spans.get(0).getName());
                    }
                    if (endpoint.equals("models/gemini:generateContent")) {
                        assertEquals("generate_content", spans.get(0).getName());
                    }
                }
            }
        }
    }

    @Test
    void asynchronousNonLlmSpansRetainParentAndEndOnlyWhenCompleted() throws Exception {
        for (boolean bytes : List.of(false, true)) {
            for (boolean fail : List.of(false, true)) {
                spans.clear();
                delegate.pendingResponse = new CompletableFuture<>();
                var parent = provider.get("test").spanBuilder("parent").startSpan();
                try {
                    CompletableFuture<ApiResponse> result;
                    try (Scope scope = parent.makeCurrent()) {
                        result =
                                bytes
                                        ? client.asyncRequest(
                                                "post",
                                                "models/gemini:batchGenerateContent",
                                                INPUT.getBytes(StandardCharsets.UTF_8),
                                                Optional.empty())
                                        : client.asyncRequest(
                                                "post",
                                                "models/gemini:batchGenerateContent",
                                                INPUT,
                                                Optional.empty());
                    }
                    assertFalse(result.isDone());
                    assertTrue(spans.isEmpty());
                    if (fail) {
                        delegate.pendingResponse.completeExceptionally(
                                new IllegalStateException("batch transport failed"));
                        assertThrows(RuntimeException.class, result::join);
                        assertHttpSpan(StatusCode.ERROR);
                        assertEquals(1, spans.get(0).getEvents().size());
                    } else {
                        delegate.pendingResponse.complete(
                                delegate.request("post", "batches", INPUT, Optional.empty()));
                        try (ApiResponse response = result.join()) {
                            assertHttpSpan(StatusCode.OK);
                            assertEquals(OUTPUT, response.getBody().string());
                        }
                    }
                    assertEquals(
                            parent.getSpanContext().getSpanId(), spans.get(0).getParentSpanId());
                    assertEquals(parent.getSpanContext().getTraceId(), spans.get(0).getTraceId());
                } finally {
                    parent.end();
                }
            }
        }
    }

    private ApiResponse invoke(
            String method, String endpoint, String body, boolean async, boolean bytes) {
        if (bytes) {
            byte[] encoded = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
            return async
                    ? client.asyncRequest(method, endpoint, encoded, Optional.empty()).join()
                    : client.request(method, endpoint, encoded, Optional.empty());
        }
        return async
                ? client.asyncRequest(method, endpoint, body, Optional.empty()).join()
                : client.request(method, endpoint, body, Optional.empty());
    }

    private void assertHttpSpan(StatusCode status) {
        assertEquals(1, spans.size());
        var span = spans.get(0);
        assertEquals("google.http", span.getName());
        assertEquals(status, span.getStatus().getStatusCode());
        assertTrue(span.getAttributes().isEmpty());
    }

    private String attribute(String key) {
        return spans.get(0).getAttributes().get(AttributeKey.stringKey(key));
    }

    private static class FakeApiClient extends ApiClient {
        String response = OUTPUT;
        boolean fail;
        boolean throwImmediately;
        int statusCode = 200;
        boolean responseClosed;
        CompletableFuture<ApiResponse> pendingResponse;

        FakeApiClient() {
            super(Optional.of("test-key"), Optional.empty(), Optional.empty());
        }

        @Override
        public ApiResponse request(
                String method, String path, String body, Optional<HttpOptions> options) {
            if (fail) {
                throw new IllegalStateException("batch transport failed");
            }
            if (statusCode != 200) {
                return new HttpApiResponse(
                        new okhttp3.Response.Builder()
                                .request(
                                        new okhttp3.Request.Builder()
                                                .url("https://example.test")
                                                .build())
                                .protocol(okhttp3.Protocol.HTTP_1_1)
                                .code(statusCode)
                                .message("Not Found")
                                .body(
                                        ResponseBody.create(
                                                "{\"error\":{\"code\":404,\"message\":\"Batch not"
                                                        + " found\",\"status\":\"NOT_FOUND\"}}",
                                                MediaType.get("application/json")))
                                .build());
            }
            return new ApiResponse() {
                private final ResponseBody body =
                        ResponseBody.create(response, MediaType.get("application/json"));

                @Override
                public ResponseBody getBody() {
                    return body;
                }

                @Override
                public Headers getHeaders() {
                    return new Headers.Builder().build();
                }

                @Override
                public void close() {
                    responseClosed = true;
                    body.close();
                }
            };
        }

        @Override
        public ApiResponse request(
                String method, String path, byte[] body, Optional<HttpOptions> options) {
            return request(
                    method,
                    path,
                    body == null ? null : new String(body, StandardCharsets.UTF_8),
                    options);
        }

        @Override
        public CompletableFuture<ApiResponse> asyncRequest(
                String method, String path, String body, Optional<HttpOptions> options) {
            if (pendingResponse != null) {
                return pendingResponse;
            }
            if (fail && !throwImmediately) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("batch transport failed"));
            }
            return CompletableFuture.completedFuture(request(method, path, body, options));
        }

        @Override
        public CompletableFuture<ApiResponse> asyncRequest(
                String method, String path, byte[] body, Optional<HttpOptions> options) {
            return asyncRequest(
                    method,
                    path,
                    body == null ? null : new String(body, StandardCharsets.UTF_8),
                    options);
        }
    }
}
