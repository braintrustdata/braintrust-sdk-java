package dev.braintrust.sdkspecimpl.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.instrumentation.springai.v1_0_0.BraintrustSpringAI;
import dev.braintrust.sdkspecimpl.LlmSpanSpec;
import dev.braintrust.sdkspecimpl.SpecClient;
import dev.braintrust.sdkspecimpl.SpecClientContext;
import java.util.Map;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/** Spring AI 1.x OpenAI client: chat completions via the low-level {@link OpenAiApi}. */
public final class SpringAi1OpenAiSpecClient implements SpecClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String id() {
        return "springai-openai";
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public boolean supports(LlmSpanSpec spec) {
        // Chat completions only: spring-ai-openai 1.1.x has no OpenAI Responses API
        // (/v1/responses) — the api package covers chat/audio/embedding/file/image/moderation.
        // Responses specs are covered by the raw OpenAiSpecClient.
        return "/v1/chat/completions".equals(spec.endpoint());
    }

    @Override
    public void executeSpec(LlmSpanSpec spec, SpecClientContext ctx) throws Exception {
        for (Map<String, Object> request : spec.requests()) {
            executeChatCompletion(ctx, request);
        }
    }

    private void executeChatCompletion(SpecClientContext ctx, Map<String, Object> request)
            throws Exception {
        // Pass the full base URL (including /v1) and override completionsPath so Spring AI
        // appends just "/chat/completions" rather than the default "/v1/chat/completions".
        var api =
                OpenAiApi.builder()
                        .baseUrl(ctx.openAiBaseUrl())
                        .completionsPath("/chat/completions")
                        .apiKey(ctx.openAiApiKey())
                        .build();

        // We need to wrap the api's HTTP clients for instrumentation. The easiest way
        // is to go through OpenAiChatModel.builder() + BraintrustSpringAI.wrap(),
        // which instruments the RestClient/WebClient inside the api object in-place.
        var modelBuilder =
                org.springframework.ai.openai.OpenAiChatModel.builder()
                        .openAiApi(api)
                        .defaultOptions(OpenAiChatOptions.builder().build());
        BraintrustSpringAI.wrap(ctx.otel(), modelBuilder);

        // Deserialize the spec JSON directly into Spring AI's ChatCompletionRequest.
        // Default "stream" to false since Spring AI's OpenAiApi unboxes it.
        var node = MAPPER.valueToTree(request);
        if (!node.has("stream")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("stream", false);
        }
        boolean stream = node.get("stream").asBoolean();
        // Add stream_options for streaming so usage stats are returned.
        if (stream && !node.has("stream_options")) {
            var streamOpts = MAPPER.createObjectNode();
            streamOpts.put("include_usage", true);
            ((com.fasterxml.jackson.databind.node.ObjectNode) node)
                    .set("stream_options", streamOpts);
        }
        var chatRequest = MAPPER.treeToValue(node, OpenAiApi.ChatCompletionRequest.class);
        if (stream) {
            api.chatCompletionStream(chatRequest).blockLast();
        } else {
            api.chatCompletionEntity(chatRequest);
        }
    }
}
