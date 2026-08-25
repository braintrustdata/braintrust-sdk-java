package dev.braintrust.sdkspecimpl.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.instrumentation.langchain.v1_14_0.BraintrustLangchain;
import dev.braintrust.sdkspecimpl.LlmSpanSpec;
import dev.braintrust.sdkspecimpl.SpecClient;
import dev.braintrust.sdkspecimpl.SpecClientContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * LangChain4j OpenAI client for langchain4j >= 1.14.0, exercising the {@code langchain_1_14_0}
 * instrumentation module. Covers both OpenAI endpoints the module instruments: chat completions
 * (sync + streaming, via the internal {@code OpenAiClient}) and the Responses API (via {@link
 * OpenAiResponsesChatModel}).
 *
 * <p>Clients are split by instrumentation module version, not by endpoint: {@code
 * LangChain18OpenAiSpecClient} covers the older {@code langchain_1_8_0} module, which has no
 * Responses API and is therefore chat-completions only.
 */
public final class LangChain114OpenAiSpecClient implements SpecClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String id() {
        return "langchain1.14-openai";
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
        if ("/v1/responses".equals(spec.endpoint())) {
            executeResponses(spec, ctx);
            return;
        }
        for (Map<String, Object> request : spec.requests()) {
            executeLangChainChatCompletion(ctx, request);
        }
    }

    /**
     * Jackson ObjectMapper for deserializing spec JSON into LangChain4j's internal {@link
     * dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest}.
     *
     * <p>LangChain4j's {@code Message} interface has no {@code @JsonTypeInfo}, so we register a
     * custom deserializer that dispatches on the {@code role} field.
     */
    private static final ObjectMapper LANGCHAIN_MAPPER = createLangChainMapper();

    private static ObjectMapper createLangChainMapper() {
        var module = new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addDeserializer(
                dev.langchain4j.model.openai.internal.chat.Message.class,
                new com.fasterxml.jackson.databind.JsonDeserializer<
                        dev.langchain4j.model.openai.internal.chat.Message>() {
                    @Override
                    public dev.langchain4j.model.openai.internal.chat.Message deserialize(
                            com.fasterxml.jackson.core.JsonParser p,
                            com.fasterxml.jackson.databind.DeserializationContext ctx)
                            throws java.io.IOException {
                        com.fasterxml.jackson.databind.JsonNode node = p.getCodec().readTree(p);
                        String role = node.has("role") ? node.get("role").asText() : "";
                        com.fasterxml.jackson.databind.ObjectMapper codec =
                                (com.fasterxml.jackson.databind.ObjectMapper) p.getCodec();
                        return switch (role) {
                            case "system" ->
                                    codec.treeToValue(
                                            node,
                                            dev.langchain4j.model.openai.internal.chat.SystemMessage
                                                    .class);
                            case "user" -> deserializeUserMessage(codec, node);
                            case "assistant" ->
                                    codec.treeToValue(
                                            node,
                                            dev.langchain4j.model.openai.internal.chat
                                                    .AssistantMessage.class);
                            case "tool" ->
                                    codec.treeToValue(
                                            node,
                                            dev.langchain4j.model.openai.internal.chat.ToolMessage
                                                    .class);
                            default ->
                                    throw new java.io.IOException(
                                            "Unsupported langchain message role: " + role);
                        };
                    }
                });
        return new ObjectMapper()
                .disable(
                        com.fasterxml.jackson.databind.DeserializationFeature
                                .FAIL_ON_IGNORED_PROPERTIES)
                .disable(
                        com.fasterxml.jackson.databind.DeserializationFeature
                                .FAIL_ON_UNKNOWN_PROPERTIES)
                .registerModule(module);
    }

    /**
     * Deserialize a LangChain4j UserMessage from a JSON node, handling the polymorphic {@code
     * content} field (string vs array of Content blocks) that the Builder can't dispatch
     * automatically.
     */
    private static dev.langchain4j.model.openai.internal.chat.UserMessage deserializeUserMessage(
            ObjectMapper mapper, com.fasterxml.jackson.databind.JsonNode node)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        var builder = dev.langchain4j.model.openai.internal.chat.UserMessage.builder();
        if (node.has("content")) {
            var content = node.get("content");
            if (content.isTextual()) {
                builder.content(content.asText());
            } else if (content.isArray()) {
                List<dev.langchain4j.model.openai.internal.chat.Content> list =
                        mapper.convertValue(
                                content,
                                mapper.getTypeFactory()
                                        .constructCollectionType(
                                                List.class,
                                                dev.langchain4j.model.openai.internal.chat.Content
                                                        .class));
                builder.content(list);
            }
        }
        if (node.has("name")) {
            builder.name(node.get("name").asText());
        }
        return builder.build();
    }

    private void executeLangChainChatCompletion(SpecClientContext ctx, Map<String, Object> request)
            throws Exception {
        boolean streaming = Boolean.TRUE.equals(request.get("stream"));

        // Build a model just to get an instrumented client via BraintrustLangchain.wrap().
        dev.langchain4j.model.openai.internal.OpenAiClient langchainClient;
        if (streaming) {
            var modelBuilder =
                    OpenAiStreamingChatModel.builder()
                            .baseUrl(ctx.openAiBaseUrl())
                            .apiKey(ctx.openAiApiKey());
            var model = BraintrustLangchain.wrap(ctx.otel(), modelBuilder);
            langchainClient = getPrivateField(model, "client");
        } else {
            var modelBuilder =
                    OpenAiChatModel.builder()
                            .baseUrl(ctx.openAiBaseUrl())
                            .apiKey(ctx.openAiApiKey());
            OpenAiChatModel model = BraintrustLangchain.wrap(ctx.otel(), modelBuilder);
            langchainClient = getPrivateField(model, "client");
        }

        // Deserialize the spec JSON directly into LangChain4j's ChatCompletionRequest.
        // The LANGCHAIN_MAPPER has custom deserializers for Message (role-based dispatch)
        // and UserMessage (polymorphic string/array content handling).
        String json = MAPPER.writeValueAsString(request);
        var chatRequest =
                LANGCHAIN_MAPPER.readValue(
                        json,
                        dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest.class);

        if (streaming) {
            var done = new CompletableFuture<Void>();
            langchainClient
                    .chatCompletion(chatRequest)
                    .onPartialResponse(response -> {})
                    .onComplete(() -> done.complete(null))
                    .onError(done::completeExceptionally)
                    .execute();
            done.get();
        } else {
            langchainClient.chatCompletion(chatRequest).execute();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T getPrivateField(Object obj, String fieldName) throws Exception {
        var field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(obj);
    }

    // ---- Responses API (/v1/responses) ---------------------------------------------------------

    /** Drives the Responses API through {@link OpenAiResponsesChatModel}. */
    private void executeResponses(LlmSpanSpec spec, SpecClientContext ctx) throws Exception {
        // The builder requires a modelName even though each request overrides it via parameters;
        // seed it from the first request.
        String defaultModel =
                spec.requests().isEmpty()
                        ? "o4-mini"
                        : String.valueOf(spec.requests().get(0).get("model"));
        OpenAiResponsesChatModel model =
                BraintrustLangchain.wrap(
                        ctx.otel(),
                        OpenAiResponsesChatModel.builder()
                                .baseUrl(ctx.openAiBaseUrl())
                                .apiKey(ctx.openAiApiKey())
                                .modelName(defaultModel)
                                .build());

        // Running conversation accumulated across turns. Prior assistant turns (with their
        // reasoning) live here as AiMessages and get re-serialized into each request's input.
        List<ChatMessage> conversation = new ArrayList<>();
        for (Map<String, Object> request : spec.requests()) {
            appendInputMessages(conversation, request.get("input"));

            ChatRequest chatRequest =
                    ChatRequest.builder()
                            .messages(conversation)
                            .parameters(buildParameters(request))
                            .build();
            ChatResponse response = model.chat(chatRequest);
            conversation.add(response.aiMessage());
        }
    }

    /** Translates the spec request's reasoning and hosted-tool options into request parameters. */
    private static OpenAiResponsesChatRequestParameters buildParameters(
            Map<String, Object> request) {
        var params = OpenAiResponsesChatRequestParameters.builder();
        params.modelName((String) request.get("model"));

        if (request.get("reasoning") instanceof Map<?, ?> reasoning) {
            if (reasoning.get("effort") instanceof String effort) {
                params.reasoningEffort(effort);
            }
            if (reasoning.get("summary") instanceof String summary) {
                params.reasoningSummary(summary);
            }
            // Ask for encrypted reasoning content so prior reasoning items can be replayed in the
            // next turn's input. Only meaningful for reasoning models, so keep it scoped to
            // requests that actually ask for reasoning.
            params.include(List.of("reasoning.encrypted_content"));
        }

        List<Map<String, Object>> serverTools = hostedTools(request.get("tools"));
        if (!serverTools.isEmpty()) {
            // langchain4j has no typed API for the responses API's server-side tools, so the raw
            // tool objects are passed through; OpenAiResponsesClient appends them to the request's
            // `tools` array. NOTE: the spec's `tool_choice: {type: web_search_preview}` cannot be
            // expressed — langchain4j types toolChoice as the ToolChoice enum (AUTO/REQUIRED/NONE),
            // so a specific hosted tool cannot be forced. The search is therefore model-elected;
            // it is reliable on gpt-4o (gpt-4o-mini accepts the tool but never searches), and the
            // recorded cassette pins the exact response for replay.
            params.serverTools(serverTools);
        }

        // Stateless multi-turn: don't persist responses server-side.
        params.store(false);
        return params.build();
    }

    /**
     * Extracts the spec request's hosted (server-side) tools — entries typed only by {@code type},
     * e.g. {@code {type: web_search_preview, search_context_size: low}} — from its {@code tools}
     * list. Function tools, which carry a {@code name}, are not hosted tools and are excluded.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> hostedTools(Object tools) {
        if (!(tools instanceof List<?> items)) {
            return List.of();
        }
        List<Map<String, Object>> hosted = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map
                    && map.get("type") != null
                    && !map.containsKey("name")) {
                hosted.add((Map<String, Object>) map);
            }
        }
        return hosted;
    }

    /** Appends this turn's role-tagged input items (user/system/assistant) to the conversation. */
    private static void appendInputMessages(List<ChatMessage> conversation, Object input) {
        if (!(input instanceof List<?> items)) {
            return;
        }
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Object role = map.get("role");
            String text = inputText(map.get("content"));
            if ("user".equals(role)) {
                conversation.add(UserMessage.from(text));
            } else if ("system".equals(role)) {
                conversation.add(SystemMessage.from(text));
            } else if ("assistant".equals(role)) {
                conversation.add(AiMessage.from(text));
            }
        }
    }

    /**
     * Reads a Responses API input item's {@code content} as text. The spec may express it either as
     * a plain string or as the content-part array form ({@code [{type: input_text, text: "..."}]});
     * langchain4j's {@link ChatMessage} types only carry text, so parts are concatenated. Falls
     * back to {@code toString()} only for shapes with no text parts, which would otherwise be
     * dropped silently.
     *
     * <p>Package-private for unit testing: no current spec uses the content-part form for a {@code
     * /v1/responses} input, so only a unit test exercises that branch.
     */
    static String inputText(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> parts) {
            StringBuilder text = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> partMap
                        && partMap.get("text") instanceof String partText) {
                    text.append(partText);
                } else if (part instanceof String partText) {
                    text.append(partText);
                }
            }
            if (!text.isEmpty()) {
                return text.toString();
            }
        }
        return content.toString();
    }
}
