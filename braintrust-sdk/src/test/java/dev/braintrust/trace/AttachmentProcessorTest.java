package dev.braintrust.trace;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.braintrust.TestHarness;
import dev.braintrust.json.BraintrustJsonMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Integration test for the attachment processing pipeline.
 *
 * <p>Uses the {@link TestHarness} which wires the {@link dev.braintrust.UnitTestSpanExporter} as an
 * additional delegate inside the {@link BraintrustSpanProcessor}. This means spans retrieved via
 * {@code awaitExportedSpans} reflect post-processing (base64 data replaced with attachment
 * references). The VCR layer stubs the S3 upload flow (login, signed URL, PUT, status).
 */
public class AttachmentProcessorTest {

    /** A small valid 1x1 PNG encoded as base64. */
    private static final String BASE64_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==";

    /** Fake base64 content standing in for a PDF document. */
    private static final String BASE64_PDF = "JVBERi0xLjQKMSAwIG9iago=";

    private static final AttributeKey<String> INPUT_JSON =
            AttributeKey.stringKey("braintrust.input_json");

    private static TestHarness testHarness;
    private static Tracer tracer;
    private static final AtomicInteger spanCounter = new AtomicInteger();

    @BeforeAll
    static void initHarness() {
        testHarness = TestHarness.setup();
        tracer = testHarness.openTelemetry().getTracer("attachment-processor-test");
    }

    // ── Parameterized: one case per provider attachment format ────────

    record FormatTestCase(String name, String inputJson, Consumer<JsonNode> assertions) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<FormatTestCase> attachmentFormatCases() {
        return Stream.of(
                // OpenAI: data URI in image_url.url
                new FormatTestCase(
                        "openai-image",
                        "[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"describe"
                            + " this\"},"
                            + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,"
                                + BASE64_PNG
                                + "\"}}]}]",
                        root -> {
                            JsonNode url =
                                    root.get(0).get("content").get(1).get("image_url").get("url");
                            assertAttachmentRef(url, "image/png");
                        }),

                // Bedrock image: {"format": "png", "source": {"bytes": "<base64>"}}
                new FormatTestCase(
                        "bedrock-image",
                        "[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"describe"
                                + " this\"},{\"type\":\"image\",\"image\":{\"format\":\"png\","
                                + "\"source\":{\"bytes\":\""
                                + BASE64_PNG
                                + "\"}}}]}]",
                        root -> {
                            JsonNode image = root.get(0).get("content").get(1).get("image");
                            assertEquals("png", image.get("format").asText());
                            assertAttachmentRef(image.get("source").get("bytes"), "image/png");
                        }),

                // Bedrock document: {"format": "pdf", "name": "doc", "source": {"bytes": "..."}}
                new FormatTestCase(
                        "bedrock-document",
                        "[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"summarize"
                            + " this\"},{\"type\":\"document\",\"document\":{\"format\":\"pdf\","
                            + "\"name\":\"report\",\"source\":{\"bytes\":\""
                                + BASE64_PDF
                                + "\"}}}]}]",
                        root -> {
                            JsonNode doc = root.get(0).get("content").get(1).get("document");
                            assertEquals("pdf", doc.get("format").asText());
                            assertEquals("report", doc.get("name").asText());
                            assertAttachmentRef(doc.get("source").get("bytes"), "application/pdf");
                        }),

                // Bedrock audio: uses block type key to resolve mp4 as audio/mp4 (not video/mp4)
                new FormatTestCase(
                        "bedrock-audio",
                        "[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"transcribe"
                                + " this\"},{\"type\":\"audio\",\"audio\":{\"format\":\"mp4\","
                                + "\"source\":{\"bytes\":\""
                                + BASE64_PDF
                                + "\"}}}]}]",
                        root -> {
                            JsonNode audio = root.get(0).get("content").get(1).get("audio");
                            assertEquals("mp4", audio.get("format").asText());
                            assertAttachmentRef(audio.get("source").get("bytes"), "audio/mp4");
                        }),

                // Anthropic image: {"type":"base64","media_type":"image/png","data":"<base64>"}
                new FormatTestCase(
                        "anthropic-image",
                        "[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"describe"
                                + " this\"},{\"type\":\"image\",\"source\":{\"type\":\"base64\","
                                + "\"media_type\":\"image/png\",\"data\":\""
                                + BASE64_PNG
                                + "\"}}]}]",
                        root -> {
                            JsonNode source = root.get(0).get("content").get(1).get("source");
                            assertAttachmentRef(source, "image/png");
                        }),

                // Anthropic document: same source structure, different media_type
                new FormatTestCase(
                        "anthropic-document",
                        "[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"summarize"
                                + " this\"},{\"type\":\"document\",\"source\":{\"type\":\"base64\","
                                + "\"media_type\":\"application/pdf\",\"data\":\""
                                + BASE64_PDF
                                + "\"}}]}]",
                        root -> {
                            JsonNode source = root.get(0).get("content").get(1).get("source");
                            assertAttachmentRef(source, "application/pdf");
                        }),

                // Gemini image: {"inlineData": {"mimeType": "image/png", "data": "<base64>"}}
                new FormatTestCase(
                        "gemini-image",
                        "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"describe"
                                + " this\"},{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\""
                                + BASE64_PNG
                                + "\"}}]}]}",
                        root -> {
                            JsonNode part = root.get("contents").get(0).get("parts").get(1);
                            assertNull(part.get("inlineData"), "inlineData should be removed");
                            assertAttachmentRef(part.get("image_url").get("url"), "image/png");
                        }),

                // Gemini PDF: non-image content uses file: {file_data: ref}
                new FormatTestCase(
                        "gemini-document",
                        "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"summarize"
                                + " this\"},{\"inlineData\":{\"mimeType\":\"application/pdf\","
                                + "\"data\":\""
                                + BASE64_PDF
                                + "\"}}]}]}",
                        root -> {
                            JsonNode part = root.get("contents").get(0).get("parts").get(1);
                            assertNull(part.get("inlineData"), "inlineData should be removed");
                            assertNull(part.get("image_url"), "non-image should not use image_url");
                            assertAttachmentRef(
                                    part.get("file").get("file_data"), "application/pdf");
                        }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("attachmentFormatCases")
    @SneakyThrows
    void attachmentFormatReplacesBase64WithRef(FormatTestCase testCase) {
        assertTrue(
                AttachmentProcessor.BASE64_HEURISTIC.matcher(testCase.inputJson).find(),
                "BASE64_HEURISTIC should match test data");

        String spanName = "fmt-" + testCase.name + "-" + spanCounter.incrementAndGet();
        var span = tracer.spanBuilder(spanName).startSpan();
        span.setAttribute("braintrust.input_json", testCase.inputJson);
        span.setAttribute("braintrust.parent", "project_name:" + TestHarness.defaultProjectName());
        span.end();

        var spans = testHarness.awaitExportedSpans(1);
        var exported =
                spans.stream()
                        .filter(s -> s.getName().equals(spanName))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("span not found"));

        String exportedJson = exported.getAttributes().get(INPUT_JSON);
        assertNotNull(exportedJson, "should have braintrust.input_json");
        assertNotEquals(testCase.inputJson, exportedJson, "base64 data should have been replaced");

        testCase.assertions.accept(BraintrustJsonMapper.get().readTree(exportedJson));
    }

    // ── Negative cases ────────────────────────────────────────────────

    @Test
    void nonDataUriInputIsUnchanged() {
        String inputJson = "[{\"role\":\"user\",\"content\":\"Hello, how are you?\"}]";
        assertEquals(inputJson, sendAndGetExportedInput(inputJson));
    }

    @Test
    void partialDataUriInTextIsNotReplaced() {
        String inputJson =
                "[{\"role\":\"user\",\"content\":\"Check this: data:image/png;base64,"
                        + BASE64_PNG
                        + " please\"}]";
        assertEquals(inputJson, sendAndGetExportedInput(inputJson));
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static String sendAndGetExportedInput(String inputJson) {
        String spanName = "negative-" + spanCounter.incrementAndGet();
        var span = tracer.spanBuilder(spanName).startSpan();
        span.setAttribute("braintrust.input_json", inputJson);
        span.setAttribute("braintrust.parent", "project_name:" + TestHarness.defaultProjectName());
        span.end();

        var spans = testHarness.awaitExportedSpans(1);
        return spans.stream()
                .filter(s -> s.getName().equals(spanName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("span not found"))
                .getAttributes()
                .get(INPUT_JSON);
    }

    private static void assertAttachmentRef(JsonNode node, String expectedContentType) {
        assertNotNull(node, "attachment ref node should not be null");
        assertTrue(node.isObject(), "attachment ref should be an object, got: " + node);
        assertEquals("braintrust_attachment", node.get("type").asText());
        assertEquals(expectedContentType, node.get("content_type").asText());
        assertNotNull(node.get("filename"));
        assertFalse(node.get("filename").asText().isEmpty());
        assertNotNull(node.get("key"));
        assertFalse(node.get("key").asText().isEmpty());
    }
}
