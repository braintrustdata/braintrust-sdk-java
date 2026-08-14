package dev.braintrust.sdkspecimpl.clients;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import dev.braintrust.instrumentation.genai.BraintrustGenAI;
import dev.braintrust.sdkspecimpl.LlmSpanSpec;
import dev.braintrust.sdkspecimpl.SpecClient;
import dev.braintrust.sdkspecimpl.SpecClientContext;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Google Gemini (google-genai) client: generateContent (sync + streaming). */
public final class GoogleSpecClient implements SpecClient {

    /**
     * {@code :generateContent} specs that require Google features not yet implemented in the genai
     * instrumentation / this client (thinking, grounding tools, response modalities, and
     * per-modality prompt-token extraction for attachments). They are additionally listed in {@link
     * dev.braintrust.sdkspecimpl.SpecClientRegistry#KNOWN_UNSUPPORTED_SPECS} so they load to a
     * deliberate skip instead of a failing "unsupported" sentinel. Remove an entry here once the
     * corresponding feature lands.
     */
    private static final java.util.Set<String> UNSUPPORTED_SPECS =
            java.util.Set.of(
                    "thinking",
                    "grounding",
                    "streaming",
                    "generated_audio_usage",
                    "generated_image_usage",
                    "attachments");

    private volatile Client geminiClient;

    @Override
    public String id() {
        return "google";
    }

    @Override
    public String provider() {
        return "google";
    }

    @Override
    public boolean supports(LlmSpanSpec spec) {
        return spec.endpoint().contains(":generateContent")
                && !UNSUPPORTED_SPECS.contains(spec.name());
    }

    @Override
    public void executeSpec(LlmSpanSpec spec, SpecClientContext ctx) throws Exception {
        for (Map<String, Object> request : spec.requests()) {
            executeGenerateContent(ctx, request, spec.endpoint());
        }
    }

    private Client client(SpecClientContext ctx) {
        Client result = geminiClient;
        if (result == null) {
            synchronized (this) {
                result = geminiClient;
                if (result == null) {
                    var geminiBuilder =
                            new Client.Builder()
                                    .apiKey(ctx.googleApiKey())
                                    .httpOptions(
                                            HttpOptions.builder()
                                                    .baseUrl(ctx.googleBaseUrl())
                                                    .build());
                    result = BraintrustGenAI.wrap(ctx.otel(), geminiBuilder);
                    geminiClient = result;
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void executeGenerateContent(
            SpecClientContext ctx, Map<String, Object> request, String endpoint) throws Exception {
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
                    client(ctx)
                            .models
                            .generateContentStream(model, content, configBuilder.build())) {}
        } else {
            client(ctx).models.generateContent(model, content, configBuilder.build());
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
