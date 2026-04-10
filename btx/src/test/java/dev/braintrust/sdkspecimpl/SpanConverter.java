package dev.braintrust.sdkspecimpl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts in-memory OTel {@link SpanData} spans into brainstore span format.
 *
 * <p>Brainstore spans are the canonical representation used in Braintrust's storage layer and
 * returned by the BTQL API. The {@code expected_brainstore_spans} in the YAML spec files are
 * written against this format.
 *
 * <p>The Braintrust SDK stores span payload in OTel span attributes as JSON strings:
 *
 * <ul>
 *   <li>{@code braintrust.metrics} → brainstore {@code metrics} field
 *   <li>{@code braintrust.metadata} → brainstore {@code metadata} field
 *   <li>{@code braintrust.span_attributes} → brainstore {@code span_attributes} field (with {@code
 *       name} injected from the OTel span name)
 *   <li>{@code braintrust.input_json} → brainstore {@code input} field
 *   <li>{@code braintrust.output_json} → brainstore {@code output} field
 * </ul>
 *
 * <p>Only LLM instrumentation spans (those that have a {@code braintrust.span_attributes}
 * attribute) are converted. The root wrapper span created by {@link SpecExecutor} is excluded.
 */
public class SpanConverter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /**
     * Convert a list of exported OTel spans into brainstore format.
     *
     * <p>Only spans that carry the {@code braintrust.span_attributes} attribute are included (i.e.
     * LLM instrumentation spans). The root wrapper span is dropped. Spans are returned in the order
     * they appear in {@code otelSpans}.
     */
    public static List<Map<String, Object>> toBrainstoreSpans(List<SpanData> otelSpans) {
        return otelSpans.stream()
                .filter(SpanConverter::isLlmInstrumentationSpan)
                .map(SpanConverter::toSingleBrainstoreSpan)
                .toList();
    }

    private static boolean isLlmInstrumentationSpan(SpanData span) {
        return span.getAttributes().get(AttributeKey.stringKey("braintrust.span_attributes"))
                != null;
    }

    private static Map<String, Object> toSingleBrainstoreSpan(SpanData span) {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("name", span.getName());
        result.put("metrics", parseJsonMap(span, "braintrust.metrics"));
        result.put("metadata", parseJsonMap(span, "braintrust.metadata"));
        result.put("input", transformInput(parseJsonValue(span, "braintrust.input_json")));
        result.put("output", parseJsonValue(span, "braintrust.output_json"));

        // span_attributes: merge the JSON object with the OTel span name so that
        // span_attributes.name matches what Braintrust stores after ingestion.
        Map<String, Object> spanAttrs = parseJsonMap(span, "braintrust.span_attributes");
        if (spanAttrs == null) {
            spanAttrs = new LinkedHashMap<>();
        } else {
            spanAttrs = new LinkedHashMap<>(spanAttrs);
        }
        spanAttrs.put("name", span.getName());
        result.put("span_attributes", spanAttrs);

        return result;
    }

    /**
     * Replicates the Braintrust backend's attachment transformation:
     *
     * <ul>
     *   <li>OpenAI: {@code image_url.url: "data:mime;base64,..."} → {@code image_url.url: {type:
     *       braintrust_attachment, content_type, filename, key}}
     *   <li>Google: {@code inline_data: {mime_type, data}} → {@code image_url: {url: {type:
     *       braintrust_attachment, content_type, filename, key}}}
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private static Object transformInput(Object input) {
        if (input instanceof List) {
            // OpenAI: list of message objects
            return ((List<Object>) input).stream().map(SpanConverter::transformInputItem).toList();
        }
        if (input instanceof Map) {
            // Google: {model, contents: [{role, parts: [...]}]}
            Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) input);
            if (map.get("contents") instanceof List) {
                List<Object> contents =
                        ((List<Object>) map.get("contents"))
                                .stream().map(SpanConverter::transformInputItem).toList();
                map.put("contents", contents);
            }
            return map;
        }
        return input;
    }

    @SuppressWarnings("unchecked")
    private static Object transformInputItem(Object item) {
        if (!(item instanceof Map)) return item;
        Map<String, Object> msg = new LinkedHashMap<>((Map<String, Object>) item);

        // OpenAI messages: transform content array parts
        if (msg.get("content") instanceof List) {
            List<Object> parts =
                    ((List<Object>) msg.get("content"))
                            .stream().map(SpanConverter::transformContentPart).toList();
            msg.put("content", parts);
        }

        // Google content items: transform parts array
        if (msg.get("parts") instanceof List) {
            List<Object> parts =
                    ((List<Object>) msg.get("parts"))
                            .stream().map(SpanConverter::transformGooglePart).toList();
            msg.put("parts", parts);
        }

        return msg;
    }

    @SuppressWarnings("unchecked")
    private static Object transformContentPart(Object part) {
        if (!(part instanceof Map)) return part;
        Map<String, Object> p = (Map<String, Object>) part;

        // Anthropic: {type: image, source: {type: base64, media_type, data}}
        if ("image".equals(p.get("type")) && p.get("source") instanceof Map) {
            Map<String, Object> source = (Map<String, Object>) p.get("source");
            if ("base64".equals(source.get("type"))) {
                String mimeType =
                        (String) source.getOrDefault("media_type", "application/octet-stream");
                String data = (String) source.get("data");
                if (data != null) {
                    Map<String, Object> newPart = new LinkedHashMap<>(p);
                    Map<String, Object> attachment =
                            toAttachment("data:" + mimeType + ";base64," + data);
                    newPart.put("source", attachment);
                    return newPart;
                }
            }
            return part;
        }

        if (!"image_url".equals(p.get("type"))) return part;

        Object imageUrlObj = p.get("image_url");
        if (!(imageUrlObj instanceof Map)) return part;
        Map<String, Object> imageUrl = (Map<String, Object>) imageUrlObj;

        Object url = imageUrl.get("url");
        if (!(url instanceof String) || !((String) url).startsWith("data:")) return part;

        String dataUrl = (String) url;
        Map<String, Object> newPart = new LinkedHashMap<>(p);
        Map<String, Object> newImageUrl = new LinkedHashMap<>(imageUrl);
        newImageUrl.put("url", toAttachment(dataUrl));
        newPart.put("image_url", newImageUrl);
        return newPart;
    }

    @SuppressWarnings("unchecked")
    private static Object transformGooglePart(Object part) {
        if (!(part instanceof Map)) return part;
        Map<String, Object> p = (Map<String, Object>) part;
        if (!p.containsKey("inlineData")) return part;

        Map<String, Object> inlineData = (Map<String, Object>) p.get("inlineData");
        String mimeType = (String) inlineData.get("mimeType");
        String data = (String) inlineData.get("data");
        if (data == null) return part;

        // Replace inlineData with image_url containing a braintrust_attachment
        Map<String, Object> newPart = new LinkedHashMap<>(p);
        newPart.remove("inlineData");
        Map<String, Object> attachment = toAttachment("data:" + mimeType + ";base64," + data);
        newPart.put("image_url", Map.of("url", attachment));
        return newPart;
    }

    private static Map<String, Object> toAttachment(String dataUrl) {
        // Parse "data:<mime>;base64,<data>"
        String contentType = "application/octet-stream";
        String data = dataUrl;
        if (dataUrl.startsWith("data:")) {
            int semicolon = dataUrl.indexOf(';');
            int comma = dataUrl.indexOf(',');
            if (semicolon > 0) contentType = dataUrl.substring(5, semicolon);
            if (comma > 0) data = dataUrl.substring(comma + 1);
        }
        // Derive a stable filename and key from the content type and a hash of the data
        String ext =
                contentType.contains("/")
                        ? contentType.substring(contentType.indexOf('/') + 1)
                        : "bin";
        String key = "attachment-" + Integer.toHexString(data.hashCode()) + "." + ext;
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("type", "braintrust_attachment");
        attachment.put("content_type", contentType);
        attachment.put("filename", key);
        attachment.put("key", key);
        return attachment;
    }

    private static Map<String, Object> parseJsonMap(SpanData span, String attrKey) {
        String json = span.getAttributes().get(AttributeKey.stringKey(attrKey));
        if (json == null) return null;
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse " + attrKey + " as JSON map: " + json, e);
        }
    }

    private static Object parseJsonValue(SpanData span, String attrKey) {
        String json = span.getAttributes().get(AttributeKey.stringKey(attrKey));
        if (json == null) return null;
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse " + attrKey + " as JSON: " + json, e);
        }
    }
}
