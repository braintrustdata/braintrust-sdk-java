package dev.braintrust.instrumentation.awsbedrock.v2_30_0;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.Bedrock30TestUtils;
import dev.braintrust.TestHarness;
import dev.braintrust.instrumentation.Instrumenter;
import io.opentelemetry.api.common.AttributeKey;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.Message;

public class BraintrustAWSBedrockTest {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @BeforeAll
    static void beforeAll() {
        var instrumentation = ByteBuddyAgent.install();
        Instrumenter.install(instrumentation, BraintrustAWSBedrockTest.class.getClassLoader());
    }

    private TestHarness testHarness;
    private Bedrock30TestUtils bedrockUtils;

    @BeforeEach
    void beforeEach() {
        testHarness = TestHarness.setup();
        bedrockUtils = new Bedrock30TestUtils(testHarness);
    }

    static Stream<Arguments> modelProvider() {
        return Stream.of(
                Arguments.of("us.anthropic.claude-3-haiku-20240307-v1:0"),
                Arguments.of("us.amazon.nova-lite-v1:0"));
    }

    @ParameterizedTest(name = "converse with {0}")
    @MethodSource("modelProvider")
    @SneakyThrows
    void converseProducesLlmSpan(String modelId) {
        try (var client = bedrockUtils.syncClientBuilder().build()) {
            var response =
                    client.converse(
                            ConverseRequest.builder()
                                    .modelId(modelId)
                                    .messages(
                                            Message.builder()
                                                    .role(ConversationRole.USER)
                                                    .content(
                                                            ContentBlock.fromText(
                                                                    "What is the capital of France?"
                                                                        + " Reply in one word."))
                                                    .build())
                                    .build());
            assertNotNull(response);
            assertFalse(
                    response.output().message().content().isEmpty(),
                    "response should have content");

            var spans = testHarness.awaitExportedSpans(1);
            assertEquals(1, spans.size(), "expected exactly one span");
            var span = spans.get(0);

            String spanAttributesJson =
                    span.getAttributes().get(AttributeKey.stringKey("braintrust.span_attributes"));
            assertNotNull(spanAttributesJson, "braintrust.span_attributes should be set");
            JsonNode spanAttributes = JSON_MAPPER.readTree(spanAttributesJson);
            assertEquals("llm", spanAttributes.get("type").asText());
            assertNotNull(
                    span.getAttributes().get(AttributeKey.stringKey("braintrust.input_json")),
                    "braintrust.input_json should be set");
            assertNotNull(
                    span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json")),
                    "braintrust.output_json should be set");
        }
    }

    @Test
    @SneakyThrows
    void converseStreamProducesLlmSpan() {
        String modelId = "us.anthropic.claude-3-haiku-20240307-v1:0";

        try (var client = bedrockUtils.asyncClientBuilder().build()) {
            var accumulatedText = new AtomicReference<>(new StringBuilder());

            var responseHandler =
                    ConverseStreamResponseHandler.builder()
                            .subscriber(
                                    ConverseStreamResponseHandler.Visitor.builder()
                                            .onContentBlockDelta(
                                                    evt -> {
                                                        if (evt.delta().text() != null) {
                                                            accumulatedText
                                                                    .get()
                                                                    .append(evt.delta().text());
                                                        }
                                                    })
                                            .build())
                            .build();

            client.converseStream(
                            ConverseStreamRequest.builder()
                                    .modelId(modelId)
                                    .messages(
                                            Message.builder()
                                                    .role(ConversationRole.USER)
                                                    .content(
                                                            ContentBlock.fromText(
                                                                    "What is the capital of France?"
                                                                        + " Reply in one word."))
                                                    .build())
                                    .build(),
                            responseHandler)
                    .get();

            assertFalse(
                    accumulatedText.get().isEmpty(), "should have received streamed text content");

            var spans = testHarness.awaitExportedSpans(1);
            assertEquals(1, spans.size(), "expected exactly one span");
            var span = spans.get(0);

            String spanAttributesJson =
                    span.getAttributes().get(AttributeKey.stringKey("braintrust.span_attributes"));
            assertNotNull(spanAttributesJson, "braintrust.span_attributes should be set");
            JsonNode spanAttributes = JSON_MAPPER.readTree(spanAttributesJson);
            assertEquals("llm", spanAttributes.get("type").asText());
            assertNotNull(
                    span.getAttributes().get(AttributeKey.stringKey("braintrust.input_json")),
                    "braintrust.input_json should be set");
            assertNotNull(
                    span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json")),
                    "braintrust.output_json should be set");
        }
    }
}
