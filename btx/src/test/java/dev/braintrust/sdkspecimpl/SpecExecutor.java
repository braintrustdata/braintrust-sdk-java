package dev.braintrust.sdkspecimpl;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.ObjectMappers;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import dev.braintrust.Bedrock30TestUtils;
import dev.braintrust.TestHarness;
import dev.braintrust.instrumentation.anthropic.BraintrustAnthropic;
import dev.braintrust.instrumentation.awsbedrock.v2_30_0.BraintrustAWSBedrock;
import dev.braintrust.instrumentation.genai.BraintrustGenAI;
import dev.braintrust.instrumentation.langchain.BraintrustLangchain;
import dev.braintrust.instrumentation.openai.BraintrustOpenAI;
import dev.braintrust.instrumentation.springai.v1_0_0.BraintrustSpringAI;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.ImageBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ImageFormat;
import software.amazon.awssdk.services.bedrockruntime.model.ImageSource;
import software.amazon.awssdk.services.bedrockruntime.model.Message;

/**
 * Executes LLM spec tests in-process using the Braintrust Java SDK instrumentation.
 *
 * <p>Each call to {@link #execute(LlmSpanSpec)} makes the real provider API calls (or uses VCR
 * cassettes in replay mode) wrapped in an OTel root span, then returns. The caller can then collect
 * exported spans from {@link TestHarness#awaitExportedSpans(int)}.
 */
public class SpecExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Tracer tracer;
    private final OpenAIClient openAIClient;
    private final AnthropicClient anthropicClient;
    private final Client geminiClient;
    private final String openAiBaseUrl;
    private final String openAiApiKey;
    private final String anthropicBaseUrl;
    private final String anthropicApiKey;
    private final Bedrock30TestUtils bedrockUtils;
    private final io.opentelemetry.api.OpenTelemetry otel;

    public SpecExecutor(TestHarness harness) {
        OpenTelemetrySdk otelSdk = harness.openTelemetry();
        this.otel = otelSdk;
        this.tracer = otelSdk.getTracer("btx");
        this.openAiBaseUrl = harness.openAiBaseUrl();
        this.openAiApiKey = harness.openAiApiKey();
        this.anthropicBaseUrl = harness.anthropicBaseUrl();
        this.anthropicApiKey = harness.anthropicApiKey();
        this.bedrockUtils = new Bedrock30TestUtils(harness);

        this.openAIClient =
                BraintrustOpenAI.wrapOpenAI(
                        otelSdk,
                        OpenAIOkHttpClient.builder()
                                .baseUrl(openAiBaseUrl)
                                .apiKey(openAiApiKey)
                                .build());

        this.anthropicClient =
                BraintrustAnthropic.wrap(
                        otelSdk,
                        AnthropicOkHttpClient.builder()
                                .baseUrl(harness.anthropicBaseUrl())
                                .apiKey(harness.anthropicApiKey())
                                .build());

        String googleApiKey = harness.googleApiKey();
        var geminiBuilder =
                new Client.Builder()
                        .apiKey(googleApiKey)
                        .httpOptions(
                                HttpOptions.builder().baseUrl(harness.googleBaseUrl()).build());
        this.geminiClient = BraintrustGenAI.wrap(otelSdk, geminiBuilder);
    }

    /**
     * Execute all requests defined in the spec, wrapped in a root span named after the spec.
     *
     * @return the OTel trace ID of the root span (hex string, e.g. {@code
     *     "e6f892e37dac9e3ef2f8906d6600d70c"}), which Braintrust stores as {@code root_span_id}
     */
    public String execute(LlmSpanSpec spec) throws Exception {
        Span rootSpan = tracer.spanBuilder(spec.name()).startSpan();
        rootSpan.setAttribute("client", spec.client());
        try (var ignored = rootSpan.makeCurrent()) {
            // History is accumulated across multi-turn requests
            List<ResponseInputItem> responsesHistory = new ArrayList<>();

            for (Map<String, Object> request : spec.requests()) {
                dispatchRequest(
                        spec.provider(), spec.endpoint(), spec.client(), request, responsesHistory);
            }
        } finally {
            rootSpan.end();
        }
        return rootSpan.getSpanContext().getTraceId();
    }

    private void dispatchRequest(
            String provider,
            String endpoint,
            String client,
            Map<String, Object> request,
            List<ResponseInputItem> responsesHistory)
            throws Exception {
        if ("openai".equals(provider) && "/v1/chat/completions".equals(endpoint)) {
            if ("langchain-openai".equals(client)) {
                executeLangChainChatCompletion(request);
            } else if ("springai-openai".equals(client)) {
                executeSpringAiOpenAiChatCompletion(request);
            } else {
                executeChatCompletion(request);
            }
        } else if ("openai".equals(provider) && "/v1/responses".equals(endpoint)) {
            executeResponses(request, responsesHistory);
        } else if ("anthropic".equals(provider) && "/v1/messages".equals(endpoint)) {
            if ("springai-anthropic".equals(client)) {
                executeSpringAiAnthropicMessages(request);
            } else {
                executeAnthropicMessages(request);
            }
        } else if ("bedrock".equals(provider) && endpoint.contains("/converse-stream")) {
            executeBedrockConverseStream(request);
        } else if ("bedrock".equals(provider) && endpoint.contains("/converse")) {
            executeBedrockConverse(request);
        } else if ("google".equals(provider) && endpoint.contains(":generateContent")) {
            executeGeminiGenerateContent(request, endpoint);
        } else {
            throw new UnsupportedOperationException(
                    "Provider "
                            + provider
                            + " endpoint "
                            + endpoint
                            + " client "
                            + client
                            + " not supported");
        }
    }

    // ---- OpenAI chat/completions ------------------------------------------------

    private void executeChatCompletion(Map<String, Object> request) throws Exception {
        boolean streaming = Boolean.TRUE.equals(request.get("stream"));
        // Serialize the whole request map to JSON, then let the SDK's mapper deserialize each
        // field into the correct SDK type — no manual field extraction needed.
        String json = MAPPER.writeValueAsString(request);
        com.fasterxml.jackson.databind.JsonNode node = ObjectMappers.jsonMapper().readTree(json);

        var builder = ChatCompletionCreateParams.builder();
        if (node.has("model"))
            builder.model(com.openai.models.ChatModel.of(node.get("model").asText()));
        if (node.has("messages")) {
            List<com.openai.models.chat.completions.ChatCompletionMessageParam> msgs =
                    ObjectMappers.jsonMapper()
                            .convertValue(
                                    node.get("messages"),
                                    ObjectMappers.jsonMapper()
                                            .getTypeFactory()
                                            .constructCollectionType(
                                                    List.class,
                                                    com.openai.models.chat.completions
                                                            .ChatCompletionMessageParam.class));
            builder.messages(msgs);
        }
        if (node.has("tools")) {
            List<com.openai.models.chat.completions.ChatCompletionTool> tools =
                    ObjectMappers.jsonMapper()
                            .convertValue(
                                    node.get("tools"),
                                    ObjectMappers.jsonMapper()
                                            .getTypeFactory()
                                            .constructCollectionType(
                                                    List.class,
                                                    com.openai.models.chat.completions
                                                            .ChatCompletionTool.class));
            builder.tools(tools);
        }
        if (node.has("temperature")) builder.temperature(node.get("temperature").asDouble());
        if (node.has("max_tokens")) builder.maxCompletionTokens(node.get("max_tokens").asLong());
        if (node.has("stream_options"))
            builder.streamOptions(
                    ObjectMappers.jsonMapper()
                            .convertValue(
                                    node.get("stream_options"),
                                    com.openai.models.chat.completions.ChatCompletionStreamOptions
                                            .class));

        var params = builder.build();
        if (streaming) {
            // Hold a reference to prevent GC-driven PhantomReachable cleanup before the stream
            // is fully consumed, which would close the SSE stream early.
            try (var stream = openAIClient.chat().completions().createStreaming(params)) {
                stream.stream().forEach(chunk -> {});
            }
        } else {
            openAIClient.chat().completions().create(params);
        }
    }

    // ---- LangChain4j OpenAI chat/completions ------------------------------------

    @SuppressWarnings("unchecked")
    private void executeLangChainChatCompletion(Map<String, Object> request) throws Exception {
        var node = MAPPER.valueToTree(request);
        boolean streaming = node.has("stream") && node.get("stream").asBoolean();

        List<ChatMessage> messages = new ArrayList<>();
        for (Map<String, Object> msg : (List<Map<String, Object>>) request.get("messages")) {
            String role = (String) msg.get("role");
            Object rawContent = msg.get("content");
            switch (role) {
                case "system" ->
                        messages.add(
                                SystemMessage.from(
                                        rawContent != null ? rawContent.toString() : ""));
                case "user" -> {
                    if (rawContent instanceof List) {
                        messages.add(
                                new UserMessage(
                                        buildLangChainContents(
                                                (List<Map<String, Object>>) rawContent)));
                    } else {
                        messages.add(
                                UserMessage.from(rawContent != null ? rawContent.toString() : ""));
                    }
                }
                default ->
                        throw new UnsupportedOperationException(
                                "langchain-openai: unsupported role: " + role);
            }
        }

        if (streaming) {
            var modelBuilder =
                    OpenAiStreamingChatModel.builder().baseUrl(openAiBaseUrl).apiKey(openAiApiKey);
            if (node.has("model")) modelBuilder.modelName(node.get("model").asText());
            if (node.has("temperature"))
                modelBuilder.temperature(node.get("temperature").asDouble());
            if (node.has("max_tokens")) modelBuilder.maxTokens(node.get("max_tokens").asInt());
            var model = BraintrustLangchain.wrap(otel, modelBuilder);
            var done = new CompletableFuture<Void>();
            model.chat(
                    messages,
                    new StreamingChatResponseHandler() {
                        @Override
                        public void onPartialResponse(String s) {}

                        @Override
                        public void onCompleteResponse(
                                dev.langchain4j.model.chat.response.ChatResponse r) {
                            done.complete(null);
                        }

                        @Override
                        public void onError(Throwable t) {
                            done.completeExceptionally(t);
                        }
                    });
            done.get();
        } else {
            var modelBuilder =
                    OpenAiChatModel.builder().baseUrl(openAiBaseUrl).apiKey(openAiApiKey);
            if (node.has("model")) modelBuilder.modelName(node.get("model").asText());
            if (node.has("temperature"))
                modelBuilder.temperature(node.get("temperature").asDouble());
            if (node.has("max_tokens")) modelBuilder.maxTokens(node.get("max_tokens").asInt());
            ChatModel model = BraintrustLangchain.wrap(otel, modelBuilder);
            var reqBuilder = ChatRequest.builder().messages(messages);
            if (node.has("tools")) {
                reqBuilder.toolSpecifications(buildLangChainToolSpecs(node.get("tools")));
            }
            model.chat(reqBuilder.build());
        }
    }

    /** Build LangChain4j {@link Content} list from a multi-part YAML content array. */
    @SuppressWarnings("unchecked")
    private static List<Content> buildLangChainContents(List<Map<String, Object>> parts) {
        List<Content> contents = new ArrayList<>();
        for (Map<String, Object> part : parts) {
            String type = (String) part.get("type");
            if ("text".equals(type)) {
                contents.add(new TextContent((String) part.get("text")));
            } else if ("image_url".equals(type)) {
                Map<String, Object> imageUrl = (Map<String, Object>) part.get("image_url");
                String url = (String) imageUrl.get("url");
                if (url != null && url.startsWith("data:")) {
                    // data:<mimeType>;base64,<data>
                    int semi = url.indexOf(';');
                    int comma = url.indexOf(',');
                    String mimeType = semi > 0 ? url.substring(5, semi) : "image/png";
                    String base64 = comma > 0 ? url.substring(comma + 1) : "";
                    contents.add(new ImageContent(base64, mimeType));
                } else {
                    contents.add(new ImageContent(url));
                }
            }
        }
        return contents;
    }

    /** Build LangChain4j {@link ToolSpecification}s from the YAML {@code tools} array. */
    private static List<ToolSpecification> buildLangChainToolSpecs(
            com.fasterxml.jackson.databind.JsonNode toolsNode) {
        List<ToolSpecification> specs = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode toolNode : toolsNode) {
            com.fasterxml.jackson.databind.JsonNode fn = toolNode.get("function");
            if (fn == null) continue;
            var schemaBuilder = JsonObjectSchema.builder();
            com.fasterxml.jackson.databind.JsonNode params = fn.get("parameters");
            if (params != null && params.has("properties")) {
                List<String> required = new ArrayList<>();
                if (params.has("required")) {
                    params.get("required").forEach(r -> required.add(r.asText()));
                }
                params.get("properties")
                        .fields()
                        .forEachRemaining(
                                entry -> {
                                    var prop = entry.getValue();
                                    String name = entry.getKey();
                                    String desc =
                                            prop.has("description")
                                                    ? prop.get("description").asText()
                                                    : null;
                                    if (prop.has("enum")) {
                                        List<String> vals = new ArrayList<>();
                                        prop.get("enum").forEach(e -> vals.add(e.asText()));
                                        schemaBuilder.addEnumProperty(name, vals);
                                    } else {
                                        schemaBuilder.addStringProperty(name, desc);
                                    }
                                });
                schemaBuilder.required(required);
            }
            specs.add(
                    ToolSpecification.builder()
                            .name(fn.get("name").asText())
                            .description(
                                    fn.has("description") ? fn.get("description").asText() : null)
                            .parameters(schemaBuilder.build())
                            .build());
        }
        return specs;
    }

    // ---- Spring AI OpenAI chat/completions --------------------------------------

    private void executeSpringAiOpenAiChatCompletion(Map<String, Object> request) throws Exception {
        var node = MAPPER.valueToTree(request);
        // Pass the full base URL (including /v1) and override completionsPath so Spring AI
        // appends just "/chat/completions" rather than the default "/v1/chat/completions".
        var api =
                OpenAiApi.builder()
                        .baseUrl(openAiBaseUrl)
                        .completionsPath("/chat/completions")
                        .apiKey(openAiApiKey)
                        .build();
        var optionsBuilder = OpenAiChatOptions.builder();
        if (node.has("model")) optionsBuilder.model(node.get("model").asText());
        if (node.has("temperature")) optionsBuilder.temperature(node.get("temperature").asDouble());
        if (node.has("max_tokens")) optionsBuilder.maxTokens(node.get("max_tokens").asInt());
        if (node.has("stream") && node.get("stream").asBoolean()) {
            optionsBuilder.streamUsage(true);
        }
        if (node.has("tools")) {
            optionsBuilder.toolCallbacks(buildSpringAiToolCallbacks(node.get("tools")));
            // Disable internal execution so tool_calls surface in the response output
            optionsBuilder.internalToolExecutionEnabled(false);
        }
        var modelBuilder =
                org.springframework.ai.openai.OpenAiChatModel.builder()
                        .openAiApi(api)
                        .defaultOptions(optionsBuilder.build());
        BraintrustSpringAI.wrap(otel, modelBuilder);
        var model = modelBuilder.build();
        var prompt = buildSpringAiPrompt(request);
        if (node.has("stream") && node.get("stream").asBoolean()) {
            model.stream(prompt).blockLast();
        } else {
            model.call(prompt);
        }
    }

    /** Build Spring AI {@link ToolCallback}s from the YAML {@code tools} array. */
    private static List<ToolCallback> buildSpringAiToolCallbacks(
            com.fasterxml.jackson.databind.JsonNode toolsNode) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode toolNode : toolsNode) {
            com.fasterxml.jackson.databind.JsonNode fn = toolNode.get("function");
            if (fn == null) continue;
            String paramsJson = fn.has("parameters") ? fn.get("parameters").toString() : "{}";
            callbacks.add(
                    FunctionToolCallback.builder(
                                    fn.get("name").asText(), (String input) -> "not implemented")
                            .description(
                                    fn.has("description") ? fn.get("description").asText() : "")
                            .inputSchema(paramsJson)
                            .inputType(String.class)
                            .build());
        }
        return callbacks;
    }

    // ---- Spring AI Anthropic messages -------------------------------------------

    private void executeSpringAiAnthropicMessages(Map<String, Object> request) throws Exception {
        var node = MAPPER.valueToTree(request);
        var api = AnthropicApi.builder().baseUrl(anthropicBaseUrl).apiKey(anthropicApiKey).build();
        var optionsBuilder = AnthropicChatOptions.builder();
        if (node.has("model")) optionsBuilder.model(node.get("model").asText());
        if (node.has("temperature")) optionsBuilder.temperature(node.get("temperature").asDouble());
        if (node.has("max_tokens")) optionsBuilder.maxTokens(node.get("max_tokens").asInt());
        var modelBuilder =
                AnthropicChatModel.builder()
                        .anthropicApi(api)
                        .defaultOptions(optionsBuilder.build());
        BraintrustSpringAI.wrap(otel, modelBuilder);
        var model = modelBuilder.build();
        var prompt = buildSpringAiPrompt(request);
        if (node.has("stream") && node.get("stream").asBoolean()) {
            model.stream(prompt).blockLast();
        } else {
            model.call(prompt);
        }
    }

    /**
     * Build a Spring AI {@link Prompt} from the YAML request's {@code messages} list.
     *
     * <p>Also handles top-level {@code system:} fields (used by Anthropic-style YAML) by prepending
     * a {@link org.springframework.ai.chat.messages.SystemMessage}.
     */
    @SuppressWarnings("unchecked")
    private static Prompt buildSpringAiPrompt(Map<String, Object> request) throws Exception {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        for (Map<String, Object> msg : (List<Map<String, Object>>) request.get("messages")) {
            String role = (String) msg.get("role");
            Object rawContent = msg.get("content");
            if ("user".equals(role) && rawContent instanceof List) {
                messages.add(buildSpringAiUserMessage((List<Map<String, Object>>) rawContent));
            } else {
                String content = rawContent != null ? rawContent.toString() : "";
                messages.add(
                        switch (role) {
                            case "system" ->
                                    new org.springframework.ai.chat.messages.SystemMessage(content);
                            case "user" ->
                                    new org.springframework.ai.chat.messages.UserMessage(content);
                            case "assistant" -> new AssistantMessage(content);
                            default ->
                                    throw new UnsupportedOperationException(
                                            "unsupported role: " + role);
                        });
            }
        }
        // Append a system message for top-level "system" field (Anthropic-style YAML)
        if (request.containsKey("system")) {
            messages.add(
                    new org.springframework.ai.chat.messages.SystemMessage(
                            request.get("system").toString()));
        }
        return new Prompt(messages);
    }

    /**
     * Build a Spring AI {@link org.springframework.ai.chat.messages.UserMessage} with text and
     * optional media parts.
     */
    @SuppressWarnings("unchecked")
    private static org.springframework.ai.chat.messages.UserMessage buildSpringAiUserMessage(
            List<Map<String, Object>> parts) throws Exception {
        String text = "";
        List<org.springframework.ai.content.Media> mediaList = new ArrayList<>();
        for (Map<String, Object> part : parts) {
            String type = (String) part.get("type");
            if ("text".equals(type)) {
                text = (String) part.getOrDefault("text", "");
            } else if ("image_url".equals(type)) {
                // OpenAI format: {type: image_url, image_url: {url: data:mime;base64,...}}
                Map<String, Object> imageUrl = (Map<String, Object>) part.get("image_url");
                String url = (String) imageUrl.get("url");
                if (url != null && url.startsWith("data:")) {
                    int semi = url.indexOf(';'), comma = url.indexOf(',');
                    String mimeType = semi > 0 ? url.substring(5, semi) : "image/png";
                    byte[] bytes = java.util.Base64.getDecoder().decode(url.substring(comma + 1));
                    mediaList.add(
                            new org.springframework.ai.content.Media(
                                    org.springframework.util.MimeTypeUtils.parseMimeType(mimeType),
                                    new org.springframework.core.io.ByteArrayResource(bytes)));
                }
            } else if ("image".equals(type)) {
                // Anthropic format: {type: image, source: {type: base64, media_type, data}}
                Map<String, Object> source = (Map<String, Object>) part.get("source");
                if ("base64".equals(source.get("type"))) {
                    String mimeType = (String) source.getOrDefault("media_type", "image/png");
                    byte[] bytes =
                            java.util.Base64.getDecoder().decode((String) source.get("data"));
                    mediaList.add(
                            new org.springframework.ai.content.Media(
                                    org.springframework.util.MimeTypeUtils.parseMimeType(mimeType),
                                    new org.springframework.core.io.ByteArrayResource(bytes)));
                }
            }
        }
        var builder = org.springframework.ai.chat.messages.UserMessage.builder().text(text);
        if (!mediaList.isEmpty()) builder.media(mediaList);
        return builder.build();
    }

    // ---- OpenAI responses -------------------------------------------------------

    private void executeResponses(Map<String, Object> request, List<ResponseInputItem> history)
            throws Exception {
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

        var builder = ResponseCreateParams.builder().inputOfResponse(fullInput);
        if (node.has("model")) builder.model(node.get("model").asText());
        if (node.has("reasoning"))
            builder.reasoning(
                    ObjectMappers.jsonMapper()
                            .convertValue(
                                    node.get("reasoning"), com.openai.models.Reasoning.class));

        Response response = openAIClient.responses().create(builder.build());

        // Accumulate this turn's input + output into history for the next turn
        history.addAll(thisInput);
        for (ResponseOutputItem out : response.output()) {
            String outJson = ObjectMappers.jsonMapper().writeValueAsString(out);
            history.add(ObjectMappers.jsonMapper().readValue(outJson, ResponseInputItem.class));
        }
    }

    // ---- Anthropic --------------------------------------------------------------

    private void executeAnthropicMessages(Map<String, Object> request) throws Exception {
        String json = MAPPER.writeValueAsString(request);
        com.fasterxml.jackson.databind.JsonNode node =
                com.anthropic.core.ObjectMappers.jsonMapper().readTree(json);

        var builder = MessageCreateParams.builder();
        if (node.has("model")) builder.model(node.get("model").asText());
        if (node.has("max_tokens")) builder.maxTokens(node.get("max_tokens").asLong());
        if (node.has("temperature")) builder.temperature(node.get("temperature").asDouble());
        if (node.has("system")) builder.system(node.get("system").asText());
        if (node.has("messages")) {
            List<com.anthropic.models.messages.MessageParam> msgs =
                    com.anthropic.core.ObjectMappers.jsonMapper()
                            .convertValue(
                                    node.get("messages"),
                                    com.anthropic.core.ObjectMappers.jsonMapper()
                                            .getTypeFactory()
                                            .constructCollectionType(
                                                    List.class,
                                                    com.anthropic.models.messages.MessageParam
                                                            .class));
            builder.messages(msgs);
        }

        var params = builder.build();
        if (node.has("stream") && node.get("stream").asBoolean()) {
            try (var stream = anthropicClient.messages().createStreaming(params)) {
                stream.stream().forEach(event -> {});
            }
        } else {
            anthropicClient.messages().create(params);
        }
    }

    // ---- AWS Bedrock ------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void executeBedrockConverse(Map<String, Object> request) {
        String modelId = (String) request.get("modelId");

        // Build messages from the spec YAML format: [{role, content: [{text: ...} | {image: ...}]}]
        List<Message> messages = new ArrayList<>();
        for (Map<String, Object> msg : (List<Map<String, Object>>) request.get("messages")) {
            String role = (String) msg.get("role");
            List<ContentBlock> contentBlocks = new ArrayList<>();
            for (Map<String, Object> part : (List<Map<String, Object>>) msg.get("content")) {
                if (part.containsKey("text")) {
                    contentBlocks.add(ContentBlock.fromText((String) part.get("text")));
                } else if (part.containsKey("image")) {
                    contentBlocks.add(
                            buildBedrockImageBlock((Map<String, Object>) part.get("image")));
                }
            }
            messages.add(
                    Message.builder()
                            .role(ConversationRole.fromValue(role))
                            .content(contentBlocks)
                            .build());
        }

        var builder = BraintrustAWSBedrock.wrap(otel, bedrockUtils.syncClientBuilder());
        try (var client = builder.build()) {
            client.converse(ConverseRequest.builder().modelId(modelId).messages(messages).build());
        }
    }

    @SuppressWarnings("unchecked")
    private void executeBedrockConverseStream(Map<String, Object> request) throws Exception {
        String modelId = (String) request.get("modelId");

        List<Message> messages = new ArrayList<>();
        for (Map<String, Object> msg : (List<Map<String, Object>>) request.get("messages")) {
            String role = (String) msg.get("role");
            List<ContentBlock> contentBlocks = new ArrayList<>();
            for (Map<String, Object> part : (List<Map<String, Object>>) msg.get("content")) {
                if (part.containsKey("text")) {
                    contentBlocks.add(ContentBlock.fromText((String) part.get("text")));
                }
            }
            messages.add(
                    Message.builder()
                            .role(ConversationRole.fromValue(role))
                            .content(contentBlocks)
                            .build());
        }

        var asyncBuilder = BraintrustAWSBedrock.wrap(otel, bedrockUtils.asyncClientBuilder());
        try (var client = asyncBuilder.build()) {
            client.converseStream(
                            ConverseStreamRequest.builder()
                                    .modelId(modelId)
                                    .messages(messages)
                                    .build(),
                            ConverseStreamResponseHandler.builder()
                                    .subscriber(
                                            ConverseStreamResponseHandler.Visitor.builder().build())
                                    .build())
                    .get();
        }
    }

    /** Builds a Bedrock {@link ContentBlock} image from the YAML {@code image:} map. */
    @SuppressWarnings("unchecked")
    private static ContentBlock buildBedrockImageBlock(Map<String, Object> imageMap) {
        String format = (String) imageMap.getOrDefault("format", "png");
        Map<String, Object> sourceMap = (Map<String, Object>) imageMap.get("source");
        String base64 = (String) sourceMap.get("bytes");
        byte[] imageBytes = java.util.Base64.getDecoder().decode(base64);
        return ContentBlock.fromImage(
                ImageBlock.builder()
                        .format(ImageFormat.fromValue(format))
                        .source(ImageSource.fromBytes(SdkBytes.fromByteArray(imageBytes)))
                        .build());
    }

    // ---- Google Gemini ----------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void executeGeminiGenerateContent(Map<String, Object> request, String endpoint)
            throws Exception {
        String model = extractModelFromEndpoint(endpoint);

        List<Part> parts = new ArrayList<>();
        if (request.containsKey("contents")) {
            for (Map<String, Object> content :
                    (List<Map<String, Object>>) request.get("contents")) {
                for (Map<String, Object> part : (List<Map<String, Object>>) content.get("parts")) {
                    if (part.containsKey("text")) {
                        parts.add(Part.fromText((String) part.get("text")));
                    } else if (part.containsKey("inline_data")) {
                        Map<String, Object> inline = (Map<String, Object>) part.get("inline_data");
                        String mime = (String) inline.get("mime_type");
                        byte[] bytes = Base64.getDecoder().decode((String) inline.get("data"));
                        parts.add(Part.fromBytes(bytes, mime));
                    }
                }
            }
        }

        com.google.genai.types.Content content =
                com.google.genai.types.Content.fromParts(parts.toArray(new Part[0]));

        var configBuilder = GenerateContentConfig.builder();
        if (request.containsKey("generationConfig")) {
            Map<String, Object> gc = (Map<String, Object>) request.get("generationConfig");
            if (gc.containsKey("temperature")) {
                configBuilder.temperature(((Number) gc.get("temperature")).floatValue());
            }
            if (gc.containsKey("maxOutputTokens")) {
                configBuilder.maxOutputTokens(((Number) gc.get("maxOutputTokens")).intValue());
            }
        }

        boolean streaming =
                request.containsKey("stream") && Boolean.TRUE.equals(request.get("stream"));
        if (streaming) {
            for (GenerateContentResponse ignored :
                    geminiClient.models.generateContentStream(
                            model, content, configBuilder.build())) {}
        } else {
            geminiClient.models.generateContent(model, content, configBuilder.build());
        }
    }

    private static String extractModelFromEndpoint(String endpoint) {
        int modelsIndex = endpoint.indexOf("/models/");
        int colonIndex = endpoint.indexOf(":", modelsIndex);
        if (modelsIndex == -1 || colonIndex == -1) {
            throw new IllegalArgumentException("Invalid Gemini endpoint: " + endpoint);
        }
        return endpoint.substring(modelsIndex + "/models/".length(), colonIndex);
    }
}
