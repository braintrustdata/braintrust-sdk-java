package dev.braintrust.sdkspecimpl.clients;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.instrumentation.anthropic.BraintrustAnthropic;
import dev.braintrust.sdkspecimpl.LlmSpanSpec;
import dev.braintrust.sdkspecimpl.SpecClient;
import dev.braintrust.sdkspecimpl.SpecClientContext;
import java.util.Map;

/** Raw anthropic-java SDK client: messages API (sync + streaming). */
public final class AnthropicSpecClient implements SpecClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private volatile AnthropicClient client;

    @Override
    public String id() {
        return "anthropic";
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public boolean supports(LlmSpanSpec spec) {
        return "/v1/messages".equals(spec.endpoint());
    }

    @Override
    public void executeSpec(LlmSpanSpec spec, SpecClientContext ctx) throws Exception {
        for (Map<String, Object> request : spec.requests()) {
            executeAnthropicMessages(ctx, spec, request);
        }
    }

    private AnthropicClient client(SpecClientContext ctx) {
        AnthropicClient result = client;
        if (result == null) {
            synchronized (this) {
                result = client;
                if (result == null) {
                    result =
                            BraintrustAnthropic.wrap(
                                    ctx.otel(),
                                    AnthropicOkHttpClient.builder()
                                            .baseUrl(ctx.anthropicBaseUrl())
                                            .apiKey(ctx.anthropicApiKey())
                                            .build());
                    client = result;
                }
            }
        }
        return result;
    }

    private void executeAnthropicMessages(
            SpecClientContext ctx, LlmSpanSpec spec, Map<String, Object> request) throws Exception {
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
            try (var s = client(ctx).messages().createStreaming(params)) {
                s.stream().forEach(event -> {});
            }
        } else {
            client(ctx).messages().create(params);
        }
    }
}
