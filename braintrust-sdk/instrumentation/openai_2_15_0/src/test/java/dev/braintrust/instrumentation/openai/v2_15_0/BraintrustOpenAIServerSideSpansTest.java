package dev.braintrust.instrumentation.openai.v2_15_0;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.helpers.ResponseAccumulator;
import com.openai.models.ChatModel;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.WebSearchTool;
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
 * Verifies that server-side tool calls in the OpenAI Responses API are captured as child {@code
 * type:"tool"} spans parented to the LLM span, giving each call its own cost/latency visibility on
 * the trace timeline. Web search ({@code web_search_call}) is the case exercised here.
 */
public class BraintrustOpenAIServerSideSpansTest {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final AttributeKey<String> SPAN_ATTRIBUTES =
            AttributeKey.stringKey("braintrust.span_attributes");
    private static final AttributeKey<String> METADATA =
            AttributeKey.stringKey("braintrust.metadata");

    @BeforeAll
    public static void beforeAll() {
        var instrumentation = ByteBuddyAgent.install();
        Instrumenter.install(
                instrumentation, BraintrustOpenAIServerSideSpansTest.class.getClassLoader());
    }

    private TestHarness testHarness;

    @BeforeEach
    void beforeEach() {
        testHarness = TestHarness.setup();
    }

    private static ResponseCreateParams webSearchRequest() {
        return ResponseCreateParams.builder()
                .model(ChatModel.GPT_4O)
                .inputOfResponse(
                        List.of(
                                ResponseInputItem.ofEasyInputMessage(
                                        EasyInputMessage.builder()
                                                .role(EasyInputMessage.Role.USER)
                                                .content(
                                                        "What is one recent headline about"
                                                            + " artificial intelligence? Use web"
                                                            + " search.")
                                                .build())))
                .addTool(
                        WebSearchTool.builder().type(WebSearchTool.Type.WEB_SEARCH_PREVIEW).build())
                .build();
    }

    @Test
    @SneakyThrows
    void testResponsesWebSearch() {
        OpenAIClient client =
                OpenAIOkHttpClient.builder()
                        .baseUrl(testHarness.openAiBaseUrl())
                        .apiKey(testHarness.openAiApiKey())
                        .build();

        Response response = client.responses().create(webSearchRequest());
        assertNotNull(response);

        var spans = testHarness.awaitExportedSpans(2);
        assertWebSearchToolSpans(spans);
    }

    @Test
    @SneakyThrows
    void testResponsesWebSearchStreaming() {
        OpenAIClient client =
                OpenAIOkHttpClient.builder()
                        .baseUrl(testHarness.openAiBaseUrl())
                        .apiKey(testHarness.openAiApiKey())
                        .build();

        var accumulator = ResponseAccumulator.create();
        try (StreamResponse<ResponseStreamEvent> stream =
                client.responses().createStreaming(webSearchRequest())) {
            stream.stream().forEach(accumulator::accumulate);
        }
        assertFalse(accumulator.response().output().isEmpty(), "should generate a response");

        var spans = testHarness.awaitExportedSpans(2);
        assertWebSearchToolSpans(spans);
    }

    @SneakyThrows
    private static void assertWebSearchToolSpans(List<SpanData> spans) {
        // Exactly one LLM span (the Responses request), plus one or more tool spans.
        var llmSpans = spans.stream().filter(s -> isType(s, "llm")).toList();
        assertEquals(1, llmSpans.size(), "expected a single LLM span");
        var llm = llmSpans.get(0);

        var webSearchSpans =
                spans.stream()
                        .filter(s -> isType(s, "tool"))
                        .filter(s -> "web_search_call".equals(s.getName()))
                        .toList();
        assertFalse(
                webSearchSpans.isEmpty(),
                "expected at least one web_search_call tool span, got spans: "
                        + spans.stream().map(SpanData::getName).toList());

        for (var ws : webSearchSpans) {
            assertEquals(
                    llm.getSpanId(),
                    ws.getParentSpanId(),
                    "web_search_call tool span must be a child of the LLM span");
            JsonNode metadata = JSON_MAPPER.readTree(ws.getAttributes().get(METADATA));
            assertEquals("web_search_call", metadata.path("tool_type").asText());
        }
    }

    @SneakyThrows
    private static boolean isType(SpanData span, String type) {
        String attr = span.getAttributes().get(SPAN_ATTRIBUTES);
        if (attr == null) {
            return false;
        }
        return type.equals(JSON_MAPPER.readTree(attr).path("type").asText());
    }
}
