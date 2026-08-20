package dev.braintrust.sdkspecimpl.springai2;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.instrumentation.springai.v2_0_0.BraintrustSpringAI;
import dev.braintrust.sdkspecimpl.LlmSpanSpec;
import dev.braintrust.sdkspecimpl.SpecClient;
import dev.braintrust.sdkspecimpl.SpecClientContext;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;

/**
 * Spring AI 2.x OpenAI client. Runs inside the isolated {@code springai2} classloader (see {@code
 * SpecClient.isolation()}); drives the real user path — {@code OpenAiChatModel.call/stream} — with
 * spec requests translated into {@link Prompt} + {@link OpenAiChatOptions}.
 */
public final class SpringAi2OpenAiSpecClient implements SpecClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String id() {
        return "springai2-openai";
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public void executeSpec(LlmSpanSpec spec, SpecClientContext ctx) throws Exception {
        for (Map<String, Object> request : spec.requests()) {
            executeChatCompletion(ctx, request);
        }
    }

    private void executeChatCompletion(SpecClientContext ctx, Map<String, Object> request)
            throws Exception {
        boolean stream = Boolean.TRUE.equals(request.get("stream"));

        // In Spring AI 2.0, connection details (apiKey/baseUrl) live on the chat options and
        // the model builder constructs the official openai-java client internally.
        var options = OpenAiChatOptions.builder();
        options.apiKey(ctx.openAiApiKey());
        options.baseUrl(ctx.openAiBaseUrl());
        options.model((String) request.get("model"));
        if (stream) {
            // Ask for the final usage chunk so token metrics are captured for streaming.
            options.streamUsage(true);
        }
        if (request.get("tools") instanceof List<?> tools) {
            options.toolCallbacks(mapTools(tools));
        }
        // Everything else the spec sets rides through as-is; see STRUCTURAL_REQUEST_KEYS.
        options.extraBody(passthroughBody(request));

        var model = OpenAiChatModel.builder().options(options.build()).build();
        BraintrustSpringAI.wrap(ctx.otel(), model);

        Prompt prompt = new Prompt(mapMessages(request.get("messages")));
        if (stream) {
            model.stream(prompt).blockLast();
        } else {
            model.call(prompt);
        }
    }

    /**
     * Spec request keys this client must express through Spring AI's own typed API rather than pass
     * through as raw body properties, because the framework needs to understand them: {@code model}
     * and {@code messages} drive the call itself, {@code stream} selects {@code stream()} vs {@code
     * call()}, {@code stream_options} is owned by {@code streamUsage(true)}, and {@code tools} must
     * become typed {@link ToolCallback}s for Spring AI to parse tool calls out of the response.
     */
    private static final Set<String> STRUCTURAL_REQUEST_KEYS =
            Set.of("model", "messages", "stream", "stream_options", "tools");

    /**
     * Every other spec request field, forwarded verbatim as OpenAI request body properties.
     *
     * <p>Spring AI 1.x and the raw-SDK clients deserialize the spec's JSON straight into a request
     * DTO ({@code OpenAiApi.ChatCompletionRequest}, {@code ChatCompletionCreateParams.Body}, ...),
     * so a spec field they don't know about still reaches the wire. Spring AI 2.0 removed {@code
     * OpenAiApi} entirely — it wraps the official {@code openai-java} client and assembles the
     * request internally from {@link OpenAiChatOptions} — so there is no DTO to deserialize into
     * and no seam to hand a raw request through.
     *
     * <p>Mapping fields one at a time is what made this client silently drop {@code n} (caught by
     * {@code streaming_multiple_choices}) and {@code reasoning_effort} (not caught: o4-mini emits
     * reasoning tokens either way, so the spec passed while the request was wrong). {@code
     * extraBody} reaches openai-java's {@code additionalBodyProperties}, which is the closest thing
     * to the passthrough the other clients get for free — so new spec fields now flow without a
     * code change, and the maintenance burden is the small, stable exclusion list above rather than
     * an open-ended inclusion list.
     */
    private static Map<String, Object> passthroughBody(Map<String, Object> request) {
        Map<String, Object> body = new LinkedHashMap<>();
        request.forEach(
                (key, value) -> {
                    if (!STRUCTURAL_REQUEST_KEYS.contains(key) && value != null) {
                        body.put(key, value);
                    }
                });
        return body;
    }

    /**
     * Maps raw OpenAI wire-format tool definitions to Spring {@link ToolCallback}s. The JSON schema
     * passes through untouched ({@link ToolDefinition#inputSchema()} is a raw JSON string). The
     * callbacks are definition-only: bare {@code ChatModel.call()} returns tool calls without
     * executing them (the tool-execution loop lives in ChatClient's ToolCallingAdvisor in 2.0).
     */
    private static List<ToolCallback> mapTools(List<?> tools) throws Exception {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Object t : tools) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fn =
                    (Map<String, Object>) ((Map<String, Object>) t).get("function");
            ToolDefinition definition =
                    ToolDefinition.builder()
                            .name((String) fn.get("name"))
                            .description((String) fn.getOrDefault("description", ""))
                            .inputSchema(MAPPER.writeValueAsString(fn.get("parameters")))
                            .build();
            callbacks.add(
                    new ToolCallback() {
                        @Override
                        public ToolDefinition getToolDefinition() {
                            return definition;
                        }

                        @Override
                        public String call(String toolInput) {
                            throw new UnsupportedOperationException(
                                    "spec runner never executes tools");
                        }
                    });
        }
        return callbacks;
    }

    private static List<Message> mapMessages(Object messagesObj) {
        List<Message> out = new ArrayList<>();
        for (Object m : (List<?>) messagesObj) {
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = (Map<String, Object>) m;
            String role = (String) msg.get("role");
            Object content = msg.get("content");
            switch (role) {
                case "system" -> out.add(new SystemMessage((String) content));
                case "user" -> out.add(mapUserMessage(content));
                case "assistant" -> out.add(new AssistantMessage((String) content));
                default ->
                        throw new UnsupportedOperationException(
                                "Unsupported message role for springai2: " + role);
            }
        }
        return out;
    }

    private static UserMessage mapUserMessage(Object content) {
        if (content instanceof String text) {
            return new UserMessage(text);
        }
        StringBuilder text = new StringBuilder();
        List<Media> media = new ArrayList<>();
        for (Object p : (List<?>) content) {
            @SuppressWarnings("unchecked")
            Map<String, Object> part = (Map<String, Object>) p;
            String type = (String) part.get("type");
            switch (type) {
                case "text" -> text.append(part.get("text"));
                case "image_url" -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> imageUrl = (Map<String, Object>) part.get("image_url");
                    media.add(mediaFromDataUrl((String) imageUrl.get("url")));
                }
                default ->
                        throw new UnsupportedOperationException(
                                "Unsupported content part for springai2: " + type);
            }
        }
        return UserMessage.builder().text(text.toString()).media(media).build();
    }

    /** Parses a {@code data:<mime>;base64,<data>} URL into a {@link Media}. */
    private static Media mediaFromDataUrl(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        String mime = dataUrl.substring("data:".length(), comma).split(";")[0];
        byte[] bytes = Base64.getDecoder().decode(dataUrl.substring(comma + 1));
        return new Media(MimeType.valueOf(mime), new ByteArrayResource(bytes));
    }
}
