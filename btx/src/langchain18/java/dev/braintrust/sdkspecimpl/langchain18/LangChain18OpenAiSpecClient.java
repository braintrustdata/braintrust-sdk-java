package dev.braintrust.sdkspecimpl.langchain18;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.instrumentation.langchain.v1_8_0.BraintrustLangchain;
import dev.braintrust.sdkspecimpl.LlmSpanSpec;
import dev.braintrust.sdkspecimpl.SpecClient;
import dev.braintrust.sdkspecimpl.SpecClientContext;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * LangChain4j OpenAI client for langchain4j 1.8.0–1.13.x, exercising the {@code langchain_1_8_0}
 * instrumentation module: chat completions (sync + streaming) only.
 *
 * <p>That module predates the OpenAI Responses API, so there is no {@code /v1/responses} coverage
 * here — {@code LangChain114OpenAiSpecClient} owns that. Runs inside the isolated {@code
 * langchain18} classloader (see {@code SpecClient.isolation()}) because langchain4j &lt; 1.14.0 and
 * >= 1.14.0 share Maven coordinates and package names and so cannot coexist on one classpath. Spec
 * filtering lives on the registry-side {@code IsolatedClientStub}, not here.
 */
public final class LangChain18OpenAiSpecClient implements SpecClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String id() {
        return "langchain1.8-openai";
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public void executeSpec(LlmSpanSpec spec, SpecClientContext ctx) throws Exception {
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
}
