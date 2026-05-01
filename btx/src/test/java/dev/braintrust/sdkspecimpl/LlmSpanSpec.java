package dev.braintrust.sdkspecimpl;

import java.util.List;
import java.util.Map;

/**
 * Parsed representation of a single YAML file under btx/spec/llm_span/.
 *
 * <p>Example YAML:
 *
 * <pre>
 * name: completions
 * type: llm_span_test
 * provider: openai
 * endpoint: /v1/chat/completions
 * requests:
 *   - model: gpt-4o-mini
 *     messages: [...]
 * expected_brainstore_spans:
 *   - metrics: {...}
 *     metadata: {...}
 *     span_attributes: {...}
 *     input: [...]
 *     output: [...]
 * </pre>
 */
public record LlmSpanSpec(
        String name,
        String type,
        String provider,
        String endpoint,
        String client,
        Map<String, String> headers,
        List<Map<String, Object>> requests,
        List<Map<String, Object>> expectedBrainstoreSpans,
        String sourcePath) {

    @Override
    public String toString() {
        return displayName();
    }

    /**
     * Test display name used by JUnit.
     *
     * <p>Includes the client name when a provider has more than one client, to distinguish e.g.
     * {@code "openai/completions [openai]"} from {@code "openai/completions [langchain-openai]"}.
     */
    public String displayName() {
        String[] parts = sourcePath.split("[/\\\\]");
        String base = parts.length >= 2 ? parts[parts.length - 2] + "/" + name : name;
        List<String> allClients = SpecLoader.clientsForProvider(provider);
        return allClients.size() > 1 ? base + " [" + client + "]" : base;
    }

    @SuppressWarnings("unchecked")
    static LlmSpanSpec fromMap(Map<String, Object> raw, String sourcePath, String client) {
        String name = (String) raw.get("name");
        String type = (String) raw.getOrDefault("type", "llm_span_test");
        String provider = (String) raw.get("provider");
        String endpoint = (String) raw.get("endpoint");

        Map<String, String> headers = null;
        if (raw.containsKey("headers")) {
            Map<String, Object> rawHeaders = (Map<String, Object>) raw.get("headers");
            headers = new java.util.LinkedHashMap<>();
            for (var entry : rawHeaders.entrySet()) {
                headers.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }

        List<Map<String, Object>> requests = (List<Map<String, Object>>) raw.get("requests");
        List<Map<String, Object>> expectedSpans =
                (List<Map<String, Object>>) raw.get("expected_brainstore_spans");

        return new LlmSpanSpec(
                name,
                type,
                provider,
                endpoint,
                client,
                headers,
                requests,
                expectedSpans,
                sourcePath);
    }
}
