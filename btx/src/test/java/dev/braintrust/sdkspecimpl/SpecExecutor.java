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
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;

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
                dispatchRequest(spec, request, responsesHistory);
            }
        } finally {
            rootSpan.end();
        }
        return rootSpan.getSpanContext().getTraceId();
    }

    private void dispatchRequest(
            LlmSpanSpec spec, Map<String, Object> request, List<ResponseInputItem> responsesHistory)
            throws Exception {
        String provider = spec.provider();
        String endpoint = spec.endpoint();
        String client = spec.client();
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
                executeSpringAiAnthropicMessages(spec, request);
            } else {
                executeAnthropicMessages(spec, request);
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
            try (var stream = openAIClient.chat().completions().createStreaming(params)) {
                stream.stream().forEach(chunk -> {});
            }
        } else {
            openAIClient.chat().completions().create(params);
        }
    }

    // ---- LangChain4j OpenAI chat/completions ------------------------------------

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

    private void executeLangChainChatCompletion(Map<String, Object> request) throws Exception {
        boolean streaming = Boolean.TRUE.equals(request.get("stream"));

        // Build a model just to get an instrumented client via BraintrustLangchain.wrap().
        dev.langchain4j.model.openai.internal.OpenAiClient langchainClient;
        if (streaming) {
            var modelBuilder =
                    OpenAiStreamingChatModel.builder().baseUrl(openAiBaseUrl).apiKey(openAiApiKey);
            var model = BraintrustLangchain.wrap(otel, modelBuilder);
            langchainClient = getPrivateField(model, "client");
        } else {
            var modelBuilder =
                    OpenAiChatModel.builder().baseUrl(openAiBaseUrl).apiKey(openAiApiKey);
            OpenAiChatModel model = BraintrustLangchain.wrap(otel, modelBuilder);
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

    // ---- Spring AI OpenAI chat/completions --------------------------------------

    private void executeSpringAiOpenAiChatCompletion(Map<String, Object> request) throws Exception {
        // Pass the full base URL (including /v1) and override completionsPath so Spring AI
        // appends just "/chat/completions" rather than the default "/v1/chat/completions".
        var api =
                OpenAiApi.builder()
                        .baseUrl(openAiBaseUrl)
                        .completionsPath("/chat/completions")
                        .apiKey(openAiApiKey)
                        .build();

        // We need to wrap the api's HTTP clients for instrumentation. The easiest way
        // is to go through OpenAiChatModel.builder() + BraintrustSpringAI.wrap(),
        // which instruments the RestClient/WebClient inside the api object in-place.
        var modelBuilder =
                org.springframework.ai.openai.OpenAiChatModel.builder()
                        .openAiApi(api)
                        .defaultOptions(OpenAiChatOptions.builder().build());
        BraintrustSpringAI.wrap(otel, modelBuilder);

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

    // ---- Spring AI Anthropic messages -------------------------------------------

    private void executeSpringAiAnthropicMessages(LlmSpanSpec spec, Map<String, Object> request)
            throws Exception {
        var apiBuilder = AnthropicApi.builder().baseUrl(anthropicBaseUrl).apiKey(anthropicApiKey);
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
        BraintrustSpringAI.wrap(otel, modelBuilder);

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

    // ---- OpenAI responses -------------------------------------------------------

    private void executeResponses(Map<String, Object> request, List<ResponseInputItem> history)
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

        Response response = openAIClient.responses().create(params);

        // Accumulate this turn's input + output into history for the next turn
        history.addAll(thisInput);
        for (ResponseOutputItem out : response.output()) {
            String outJson = ObjectMappers.jsonMapper().writeValueAsString(out);
            history.add(ObjectMappers.jsonMapper().readValue(outJson, ResponseInputItem.class));
        }
    }

    // ---- Anthropic --------------------------------------------------------------

    private void executeAnthropicMessages(LlmSpanSpec spec, Map<String, Object> request)
            throws Exception {
        // Strip the "stream" key before deserializing — it's not part of
        // MessageCreateParams.Body; we handle it ourselves.
        boolean stream = Boolean.TRUE.equals(request.get("stream"));
        Map<String, Object> bodyMap = new java.util.LinkedHashMap<>(request);
        bodyMap.remove("stream");

        String json = MAPPER.writeValueAsString(bodyMap);
        MessageCreateParams.Body body =
                com.anthropic.core.ObjectMappers.jsonMapper()
                        .readValue(json, MessageCreateParams.Body.class);

        var builder = MessageCreateParams.builder().body(body);
        if (spec.headers() != null) {
            spec.headers().forEach(builder::putAdditionalHeader);
        }
        var params = builder.build();

        if (stream) {
            try (var s = anthropicClient.messages().createStreaming(params)) {
                s.stream().forEach(event -> {});
            }
        } else {
            anthropicClient.messages().create(params);
        }
    }

    // ---- AWS Bedrock ------------------------------------------------------------

    /**
     * Unmarshaller that uses the AWS SDK's internal {@link
     * software.amazon.awssdk.protocols.json.internal.unmarshall.JsonProtocolUnmarshaller} (via
     * reflection) to deserialize JSON into SDK model objects (SdkPojo). This is the same machinery
     * the SDK uses to parse API responses.
     */
    private static final Object BEDROCK_UNMARSHALLER;

    private static final software.amazon.awssdk.protocols.jsoncore.JsonNodeParser
            BEDROCK_JSON_PARSER = software.amazon.awssdk.protocols.jsoncore.JsonNodeParser.create();

    static {
        try {
            // JsonProtocolUnmarshaller is @SdkInternalApi, so we construct it reflectively.
            Class<?> unmarshallerClass =
                    Class.forName(
                            "software.amazon.awssdk.protocols.json.internal.unmarshall.JsonProtocolUnmarshaller");
            var builderMethod = unmarshallerClass.getMethod("builder");
            var builderObj = builderMethod.invoke(null);
            var builderClass = builderObj.getClass();

            // Set the parser
            builderClass
                    .getMethod(
                            "parser",
                            software.amazon.awssdk.protocols.jsoncore.JsonNodeParser.class)
                    .invoke(builderObj, BEDROCK_JSON_PARSER);

            // Use default protocol unmarshall dependencies
            var depsMethod = unmarshallerClass.getMethod("defaultProtocolUnmarshallDependencies");
            var deps = depsMethod.invoke(null);
            builderClass
                    .getMethod(
                            "protocolUnmarshallDependencies",
                            Class.forName(
                                    "software.amazon.awssdk.protocols.json.internal.unmarshall.ProtocolUnmarshallDependencies"))
                    .invoke(builderObj, deps);

            BEDROCK_UNMARSHALLER = builderClass.getMethod("build").invoke(builderObj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Bedrock JSON unmarshaller", e);
        }
    }

    /**
     * Deserialize a JSON string into an AWS SDK model object using the SDK's internal unmarshaller.
     * The object must implement {@link software.amazon.awssdk.core.SdkPojo}.
     */
    @SuppressWarnings("unchecked")
    private static <T extends software.amazon.awssdk.core.SdkPojo> T bedrockFromJson(
            String json, software.amazon.awssdk.core.SdkPojo builderInstance) throws Exception {
        software.amazon.awssdk.protocols.jsoncore.JsonNode jsonNode =
                BEDROCK_JSON_PARSER.parse(
                        new java.io.ByteArrayInputStream(
                                json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        // Build a minimal SdkHttpFullResponse — the unmarshaller only uses it for
        // explicit payload members (SdkBytes/String), which normal Converse fields don't have.
        var response =
                software.amazon.awssdk.http.SdkHttpFullResponse.builder().statusCode(200).build();

        // Call unmarshall(SdkPojo, SdkHttpFullResponse, JsonNode) reflectively.
        var method =
                BEDROCK_UNMARSHALLER
                        .getClass()
                        .getMethod(
                                "unmarshall",
                                software.amazon.awssdk.core.SdkPojo.class,
                                software.amazon.awssdk.http.SdkHttpFullResponse.class,
                                software.amazon.awssdk.protocols.jsoncore.JsonNode.class);
        return (T) method.invoke(BEDROCK_UNMARSHALLER, builderInstance, response, jsonNode);
    }

    private void executeBedrockConverse(Map<String, Object> request) throws Exception {
        String json = MAPPER.writeValueAsString(request);
        ConverseRequest converseRequest = bedrockFromJson(json, ConverseRequest.builder());

        var builder = BraintrustAWSBedrock.wrap(otel, bedrockUtils.syncClientBuilder());
        try (var client = builder.build()) {
            client.converse(converseRequest);
        }
    }

    private void executeBedrockConverseStream(Map<String, Object> request) throws Exception {
        String json = MAPPER.writeValueAsString(request);
        ConverseStreamRequest converseStreamRequest =
                bedrockFromJson(json, ConverseStreamRequest.builder());

        var asyncBuilder = BraintrustAWSBedrock.wrap(otel, bedrockUtils.asyncClientBuilder());
        try (var client = asyncBuilder.build()) {
            client.converseStream(
                            converseStreamRequest,
                            ConverseStreamResponseHandler.builder()
                                    .subscriber(
                                            ConverseStreamResponseHandler.Visitor.builder().build())
                                    .build())
                    .get();
        }
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
