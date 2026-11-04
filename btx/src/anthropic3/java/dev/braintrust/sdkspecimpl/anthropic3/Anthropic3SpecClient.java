package dev.braintrust.sdkspecimpl.anthropic3;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.instrumentation.anthropic.v3_0_0.BraintrustAnthropic;
import dev.braintrust.sdkspecimpl.LlmSpanSpec;
import dev.braintrust.sdkspecimpl.SpecClient;
import dev.braintrust.sdkspecimpl.SpecClientContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Raw anthropic-java 3.x SDK client (messages API, sync + streaming), driving the {@code
 * anthropic_3_0_0} instrumentation module. Runs inside the isolated {@code anthropic3} classloader
 * (see {@code SpecClient.isolation()}) because anthropic-java 2.x and 3.x share coordinates and
 * packages.
 */
public final class Anthropic3SpecClient implements SpecClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> REMOVED_IN_3_X = List.of("temperature", "top_p", "top_k");

    private volatile AnthropicClient client;

    @Override
    public String id() {
        return "anthropic3";
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public void executeSpec(LlmSpanSpec spec, SpecClientContext ctx) throws Exception {
        for (Map<String, Object> request : spec.requests()) {
            executeMessages(ctx, spec, request);
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

    private void executeMessages(
            SpecClientContext ctx, LlmSpanSpec spec, Map<String, Object> request) throws Exception {
        // "stream" is not part of MessageCreateParams.Body; it selects create vs createStreaming.
        boolean stream = Boolean.TRUE.equals(request.get("stream"));
        Map<String, Object> bodyMap = new LinkedHashMap<>(request);
        bodyMap.remove("stream");
        // anthropic-java 3.x targets API version 2026-10-21, which removed these sampling
        // parameters and rejects them ("Extra inputs are not permitted"). The specs set them for
        // determinism only; no assertion depends on them.
        REMOVED_IN_3_X.forEach(bodyMap::remove);

        MessageCreateParams.Body body =
                ObjectMappers.jsonMapper()
                        .readValue(
                                MAPPER.writeValueAsString(bodyMap), MessageCreateParams.Body.class);

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
