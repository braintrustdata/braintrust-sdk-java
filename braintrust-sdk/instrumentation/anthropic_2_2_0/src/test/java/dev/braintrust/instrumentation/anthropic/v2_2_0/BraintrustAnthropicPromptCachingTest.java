package dev.braintrust.instrumentation.anthropic.v2_2_0;

import static org.junit.jupiter.api.Assertions.*;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.TestHarness;
import dev.braintrust.instrumentation.Instrumenter;
import io.opentelemetry.api.common.AttributeKey;
import java.util.List;
import java.util.UUID;
import lombok.SneakyThrows;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests that prompt caching metrics (per-TTL breakdown) are correctly extracted from Anthropic API
 * responses and attached to spans.
 *
 * <p>Uses a cache-buster nonce in the system prompt to guarantee cache misses, ensuring {@code
 * cache_creation_input_tokens} is always positive. In VCR replay mode the nonce is a fixed string
 * so cassette matching still works.
 */
public class BraintrustAnthropicPromptCachingTest {
    private static final String TEST_MODEL = "claude-sonnet-4-5-20250929";
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /**
     * Nonce injected into system prompts to bust Anthropic's server-side prompt cache. Random UUID
     * when running live ({@code VCR_MODE=off}), fixed string otherwise so VCR cassettes match.
     */
    private static final String VCR_NONCE;

    static {
        String vcrMode = System.getenv().getOrDefault("VCR_MODE", "replay").toUpperCase();
        VCR_NONCE = "OFF".equals(vcrMode) ? UUID.randomUUID().toString() : "vcr-mode";
    }

    @BeforeAll
    public static void beforeAll() {
        var instrumentation = ByteBuddyAgent.install();
        Instrumenter.install(
                instrumentation, BraintrustAnthropicPromptCachingTest.class.getClassLoader());
    }

    private TestHarness testHarness;

    @BeforeEach
    void beforeEach() {
        testHarness = TestHarness.setup();
    }

    /**
     * Sends a single request with two system content blocks — one cached at 5m TTL and one at 1h
     * TTL — and verifies that the per-TTL prompt cache creation metrics are present on the span.
     */
    @Test
    @SneakyThrows
    void testPromptCachingDualTtl() {
        AnthropicClient client =
                AnthropicOkHttpClient.builder()
                        .baseUrl(testHarness.anthropicBaseUrl())
                        .apiKey(testHarness.anthropicApiKey())
                        .build();

        // 1h TTL block — must come before 5m (Anthropic requires descending TTL order).
        // Requires the extended-cache-ttl beta header.
        // The text must exceed Claude Sonnet's minimum cacheable size (~1024 tokens / ~4000 chars).
        CacheControlEphemeral cacheControl1h =
                CacheControlEphemeral.builder()
                        .putAdditionalProperty("ttl", JsonValue.from("1h"))
                        .build();

        TextBlockParam systemBlock1h =
                TextBlockParam.builder()
                        .text(
                                buildPaddedSystemText(
                                        "1h-block",
                                        "Reference: capitals include Paris, Berlin, Rome, Madrid,"
                                                + " Lisbon."))
                        .cacheControl(cacheControl1h)
                        .build();

        // 5m TTL block — default ephemeral cache.
        CacheControlEphemeral cacheControl5m =
                CacheControlEphemeral.builder()
                        .putAdditionalProperty("ttl", JsonValue.from("5m"))
                        .build();

        TextBlockParam systemBlock5m =
                TextBlockParam.builder()
                        .text(
                                buildPaddedSystemText(
                                        "5m-block",
                                        "You are a helpful geography assistant. Answer in one"
                                                + " sentence."))
                        .cacheControl(cacheControl5m)
                        .build();

        var request =
                MessageCreateParams.builder()
                        .model(TEST_MODEL)
                        .systemOfTextBlockParams(List.of(systemBlock1h, systemBlock5m))
                        .addUserMessage("What is the capital of France?")
                        .maxTokens(128)
                        .temperature(0.0)
                        .putAdditionalHeader("anthropic-beta", "extended-cache-ttl-2025-04-11")
                        .build();

        var response = client.messages().create(request);

        // Basic response sanity
        assertNotNull(response);
        assertNotNull(response.id());
        var contentBlock = response.content().get(0);
        assertTrue(contentBlock.isText());
        assertFalse(contentBlock.asText().text().isEmpty());

        // Verify span metrics
        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var span = spans.get(0);

        String metricsJson = span.getAttributes().get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson, "metrics should be present");
        JsonNode metrics = JSON_MAPPER.readTree(metricsJson);

        // Standard token metrics
        assertPositive(metrics, "prompt_tokens");
        assertPositive(metrics, "completion_tokens");
        assertPositive(metrics, "tokens");

        // Per-TTL breakdown — both should be positive on a cold cache
        assertFalse(
                metrics.has("prompt_cache_creation_tokens"),
                "anthropic cache tokens must be set INSTEAD of the aggregate meteric");
        assertPositive(metrics, "prompt_cache_creation_5m_tokens");
        assertPositive(metrics, "prompt_cache_creation_1h_tokens");
        assertEquals(
                response.usage().cacheCreationInputTokens().get(),
                (metrics.get("prompt_cache_creation_5m_tokens").intValue()
                        + metrics.get("prompt_cache_creation_1h_tokens").intValue()),
                "ttl tokens should sum to total token count");

        // Cache read may be 0 on cold cache, but should be present and non-negative
        assertTrue(metrics.has("prompt_cached_tokens"), "prompt_cached_tokens should be present");
        assertTrue(
                metrics.get("prompt_cached_tokens").asDouble() >= 0,
                "prompt_cached_tokens should be non-negative");
    }

    private static void assertPositive(JsonNode metrics, String field) {
        assertTrue(metrics.has(field), field + " should be present");
        assertTrue(
                metrics.get(field).isNumber(),
                field + " should be a number, got: " + metrics.get(field));
        assertTrue(
                metrics.get(field).asDouble() > 0,
                field + " should be positive, got: " + metrics.get(field).asDouble());
    }

    /**
     * Build a system prompt text padded to exceed the minimum cacheable size (~4000 ASCII chars).
     * Includes the VCR nonce and a block identifier for cache isolation.
     */
    private String buildPaddedSystemText(String blockId, String coreInstruction) {
        StringBuilder sb = new StringBuilder();
        sb.append("[cache-buster: ").append(blockId).append(" ").append(VCR_NONCE).append("]\n");
        sb.append(coreInstruction).append("\n\n");

        // Pad with numbered guidelines to exceed the token minimum.
        for (int i = 1; i <= 80; i++) {
            sb.append(i)
                    .append(". This is guideline number ")
                    .append(i)
                    .append(". It exists to pad the system prompt past the minimum cacheable ")
                    .append("token threshold for Claude Sonnet models. Each guideline adds ")
                    .append("approximately fifty tokens of padding text to the prompt.\n");
        }
        return sb.toString();
    }
}
