package dev.braintrust.sdkspecimpl.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.ObjectMappers;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import dev.braintrust.instrumentation.openai.BraintrustOpenAI;
import dev.braintrust.sdkspecimpl.LlmSpanSpec;
import dev.braintrust.sdkspecimpl.SpecClient;
import dev.braintrust.sdkspecimpl.SpecClientContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Raw openai-java SDK client: chat completions (sync + streaming) and the responses API. */
public final class OpenAiSpecClient implements SpecClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private volatile OpenAIClient client;

    @Override
    public String id() {
        return "openai";
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public boolean supports(LlmSpanSpec spec) {
        return "/v1/chat/completions".equals(spec.endpoint())
                || "/v1/responses".equals(spec.endpoint());
    }

    @Override
    public void executeSpec(LlmSpanSpec spec, SpecClientContext ctx) throws Exception {
        // History is accumulated across multi-turn responses-API requests
        List<ResponseInputItem> responsesHistory = new ArrayList<>();
        for (Map<String, Object> request : spec.requests()) {
            if ("/v1/responses".equals(spec.endpoint())) {
                executeResponses(ctx, request, responsesHistory);
            } else {
                executeChatCompletion(ctx, request);
            }
        }
    }

    private OpenAIClient client(SpecClientContext ctx) {
        OpenAIClient result = client;
        if (result == null) {
            synchronized (this) {
                result = client;
                if (result == null) {
                    result =
                            BraintrustOpenAI.wrapOpenAI(
                                    ctx.otel(),
                                    OpenAIOkHttpClient.builder()
                                            .baseUrl(ctx.openAiBaseUrl())
                                            .apiKey(ctx.openAiApiKey())
                                            .build());
                    client = result;
                }
            }
        }
        return result;
    }

    private void executeChatCompletion(SpecClientContext ctx, Map<String, Object> request)
            throws Exception {
        boolean streaming = Boolean.TRUE.equals(request.get("stream"));
        // Ensure "stream" is always present in the body — the OpenAI API expects it
        // and VCR cassettes were recorded with it.
        Map<String, Object> bodyMap = new java.util.LinkedHashMap<>(request);
        bodyMap.putIfAbsent("stream", false);
        String json = MAPPER.writeValueAsString(bodyMap);
        ChatCompletionCreateParams.Body body =
                ObjectMappers.jsonMapper().readValue(json, ChatCompletionCreateParams.Body.class);
        var params = ChatCompletionCreateParams.builder().body(body).build();

        if (streaming) {
            // Hold a reference to prevent GC-driven PhantomReachable cleanup before the stream
            // is fully consumed, which would close the SSE stream early.
            try (var stream = client(ctx).chat().completions().createStreaming(params)) {
                stream.stream().forEach(chunk -> {});
            }
        } else {
            client(ctx).chat().completions().create(params);
        }
    }

    private void executeResponses(
            SpecClientContext ctx, Map<String, Object> request, List<ResponseInputItem> history)
            throws Exception {
        // The responses API has multi-turn history: each turn's input items are
        // prepended with outputs from prior turns. We deserialize the "input" field
        // separately to accumulate history, then deserialize the rest of the body
        // generically.
        String json = MAPPER.writeValueAsString(request);
        com.fasterxml.jackson.databind.JsonNode node = ObjectMappers.jsonMapper().readTree(json);

        // Deserialize this turn's input items
        List<ResponseInputItem> thisInput =
                ObjectMappers.jsonMapper()
                        .convertValue(
                                node.get("input"),
                                ObjectMappers.jsonMapper()
                                        .getTypeFactory()
                                        .constructCollectionType(
                                                List.class, ResponseInputItem.class));

        // Prepend accumulated history from previous turns
        List<ResponseInputItem> fullInput = new ArrayList<>(history);
        fullInput.addAll(thisInput);

        // Deserialize the full body, then override input with the accumulated history.
        ResponseCreateParams.Body body =
                ObjectMappers.jsonMapper().readValue(json, ResponseCreateParams.Body.class);
        var params = ResponseCreateParams.builder().body(body).inputOfResponse(fullInput).build();

        Response response = client(ctx).responses().create(params);

        // Accumulate this turn's input + output into history for the next turn
        history.addAll(thisInput);
        for (ResponseOutputItem out : response.output()) {
            String outJson = ObjectMappers.jsonMapper().writeValueAsString(out);
            history.add(ObjectMappers.jsonMapper().readValue(outJson, ResponseInputItem.class));
        }
    }
}
