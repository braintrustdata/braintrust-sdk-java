package dev.braintrust.instrumentation.anthropic.v2_2_0;

import static org.junit.jupiter.api.Assertions.*;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.WebSearchTool20250305;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.TestHarness;
import dev.braintrust.instrumentation.Instrumenter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import lombok.SneakyThrows;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that Anthropic server-side tool calls are captured as both a cost metric on the LLM span
 * and a child {@code type:"tool"} span parented to it, giving each call its own cost/latency
 * visibility on the trace timeline. Web search ({@code server_tool_use_web_search_requests}) is the
 * case exercised here.
 */
public class BraintrustAnthropicServerSideSpansTest {
    private static final String TEST_MODEL = "claude-sonnet-4-5-20250929";
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final AttributeKey<String> SPAN_ATTRIBUTES =
            AttributeKey.stringKey("braintrust.span_attributes");
    private static final AttributeKey<String> METADATA =
            AttributeKey.stringKey("braintrust.metadata");
    private static final AttributeKey<String> METRICS =
            AttributeKey.stringKey("braintrust.metrics");

    @BeforeAll
    public static void beforeAll() {
        var instrumentation = ByteBuddyAgent.install();
        Instrumenter.install(
                instrumentation, BraintrustAnthropicServerSideSpansTest.class.getClassLoader());
    }

    private TestHarness testHarness;

    @BeforeEach
    void beforeEach() {
        testHarness = TestHarness.setup();
    }

    private static MessageCreateParams webSearchRequest() {
        return MessageCreateParams.builder()
                .model(Model.of(TEST_MODEL))
                .maxTokens(1024)
                .addUserMessage(
                        "Search the web for one recent AI news headline and answer in one"
                                + " sentence.")
                .addTool(WebSearchTool20250305.builder().maxUses(3).build())
                .build();
    }

    @Test
    @SneakyThrows
    void testWebSearch() {
        AnthropicClient client =
                AnthropicOkHttpClient.builder()
                        .baseUrl(testHarness.anthropicBaseUrl())
                        .apiKey(testHarness.anthropicApiKey())
                        .build();

        var response = client.messages().create(webSearchRequest());
        assertNotNull(response);

        assertWebSearch(testHarness.awaitExportedSpans(2));
    }

    @Test
    @SneakyThrows
    void testWebSearchStreaming() {
        AnthropicClient client =
                AnthropicOkHttpClient.builder()
                        .baseUrl(testHarness.anthropicBaseUrl())
                        .apiKey(testHarness.anthropicApiKey())
                        .build();

        try (var stream = client.messages().createStreaming(webSearchRequest())) {
            stream.stream().forEach(event -> {});
        }

        assertWebSearch(testHarness.awaitExportedSpans(2));
    }

    @SneakyThrows
    private static void assertWebSearch(List<SpanData> spans) {
        var llmSpans = spans.stream().filter(s -> isType(s, "llm")).toList();
        assertEquals(1, llmSpans.size(), "expected a single LLM span");
        var llm = llmSpans.get(0);

        // Cost metric on the LLM span.
        JsonNode metrics = JSON_MAPPER.readTree(llm.getAttributes().get(METRICS));
        assertTrue(
                metrics.has("server_tool_use_web_search_requests"),
                "expected server_tool_use_web_search_requests metric, got: " + metrics);
        assertTrue(metrics.get("server_tool_use_web_search_requests").asDouble() >= 1.0);

        // At least one web_search tool span, parented to the LLM span.
        var toolSpans =
                spans.stream()
                        .filter(s -> isType(s, "tool"))
                        .filter(s -> "web_search".equals(s.getName()))
                        .toList();
        assertFalse(
                toolSpans.isEmpty(),
                "expected at least one web_search tool span, got: "
                        + spans.stream().map(SpanData::getName).toList());

        for (var tool : toolSpans) {
            assertEquals(
                    llm.getSpanId(),
                    tool.getParentSpanId(),
                    "web_search tool span must be a child of the LLM span");
            JsonNode metadata = JSON_MAPPER.readTree(tool.getAttributes().get(METADATA));
            assertEquals("server_tool_use", metadata.path("tool_call_type").asText());
            assertEquals("web_search_tool_result", metadata.path("tool_result_type").asText());
            assertFalse(metadata.path("tool_use_id").asText().isEmpty());
        }
    }

    @SneakyThrows
    private static boolean isType(SpanData span, String type) {
        String attr = span.getAttributes().get(SPAN_ATTRIBUTES);
        return attr != null && type.equals(JSON_MAPPER.readTree(attr).path("type").asText());
    }
}
