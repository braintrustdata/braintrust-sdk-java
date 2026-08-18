package dev.braintrust.sdkspecimpl.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.instrumentation.springai.v1_0_0.BraintrustSpringAI;
import dev.braintrust.sdkspecimpl.LlmSpanSpec;
import dev.braintrust.sdkspecimpl.SpecClient;
import dev.braintrust.sdkspecimpl.SpecClientContext;
import java.util.Map;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;

/** Spring AI 1.x Anthropic client: messages API via the low-level {@link AnthropicApi}. */
public final class SpringAi1AnthropicSpecClient implements SpecClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String id() {
        return "springai-anthropic";
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    /**
     * Specs this client cannot express, keyed by spec {@code name}. The limitation is in the Spring
     * AI framework, not our instrumentation: Spring AI 1.x's Anthropic response model ({@code
     * AnthropicApi.ContentBlock.Type}) has no {@code web_search_tool_result} value, so the
     * framework throws {@code HttpMessageNotReadableException} deserializing a web-search response
     * — after our HTTP-layer instrumentation has already captured the spans correctly. (Spring AI
     * 2.x fails differently: it silently drops the native tool from the request entirely.) Web
     * search is therefore only exercised through the raw {@code anthropic} client.
     */
    private static final java.util.Set<String> UNSUPPORTED_SPECS = java.util.Set.of("web_search");

    @Override
    public boolean supports(LlmSpanSpec spec) {
        return "/v1/messages".equals(spec.endpoint()) && !UNSUPPORTED_SPECS.contains(spec.name());
    }

    @Override
    public void executeSpec(LlmSpanSpec spec, SpecClientContext ctx) throws Exception {
        for (Map<String, Object> request : spec.requests()) {
            executeMessages(ctx, spec, request);
        }
    }

    private void executeMessages(
            SpecClientContext ctx, LlmSpanSpec spec, Map<String, Object> request) throws Exception {
        var apiBuilder =
                AnthropicApi.builder()
                        .baseUrl(ctx.anthropicBaseUrl())
                        .apiKey(ctx.anthropicApiKey());
        if (spec.headers() != null && spec.headers().containsKey("anthropic-beta")) {
            apiBuilder.anthropicBetaFeatures(spec.headers().get("anthropic-beta"));
        }
        var api = apiBuilder.build();

        // We need to wrap the api's HTTP clients for instrumentation. The easiest way
        // is to go through AnthropicChatModel.builder() + BraintrustSpringAI.wrap(),
        // which instruments the RestClient/WebClient inside the api object in-place.
        var modelBuilder =
                AnthropicChatModel.builder()
                        .anthropicApi(api)
                        .defaultOptions(AnthropicChatOptions.builder().build());
        BraintrustSpringAI.wrap(ctx.otel(), modelBuilder);

        // Normalize the spec JSON so it deserializes into Spring AI's
        // ChatCompletionRequest: message "content" strings must become
        // [{type:"text", text:"..."}] lists since AnthropicMessage expects
        // List<ContentBlock>, and "stream" must be explicitly present since
        // AnthropicApi unboxes the Boolean without a null check.
        var node = MAPPER.valueToTree(request);
        normalizeAnthropicMessages(node);
        if (!node.has("stream")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("stream", false);
        }

        boolean stream = node.get("stream").asBoolean();
        var chatRequest = MAPPER.treeToValue(node, AnthropicApi.ChatCompletionRequest.class);
        if (stream) {
            api.chatCompletionStream(chatRequest).blockLast();
        } else {
            api.chatCompletionEntity(chatRequest);
        }
    }

    /**
     * Normalize Anthropic message content for Spring AI deserialization. The Anthropic API accepts
     * both {@code "content": "text"} and {@code "content": [{...}]}, but Spring AI's {@link
     * AnthropicApi.AnthropicMessage} only models the list form. This converts any string content
     * into {@code [{type:"text", text:"..."}]}.
     */
    private static void normalizeAnthropicMessages(com.fasterxml.jackson.databind.JsonNode root) {
        var messages = root.get("messages");
        if (messages == null || !messages.isArray()) return;
        for (var msg : messages) {
            var content = msg.get("content");
            if (content != null && content.isTextual()) {
                var arr = MAPPER.createArrayNode();
                var block = MAPPER.createObjectNode();
                block.put("type", "text");
                block.put("text", content.asText());
                arr.add(block);
                ((com.fasterxml.jackson.databind.node.ObjectNode) msg).set("content", arr);
            }
        }
    }
}
