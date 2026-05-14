package dev.braintrust.trace;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.braintrust.TestHarness;
import dev.braintrust.json.BraintrustJsonMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the attachment processing pipeline.
 *
 * <p>Uses the {@link TestHarness} which wires the {@link dev.braintrust.UnitTestSpanExporter} as an
 * additional delegate inside the {@link BraintrustSpanProcessor}. This means spans retrieved via
 * {@code awaitExportedSpans} reflect post-processing (base64 data URIs replaced with attachment
 * references). The VCR layer stubs the S3 upload flow (login, signed URL, PUT, status).
 */
public class AttachmentProcessorTest {

    /** A small valid 1x1 PNG encoded as base64. */
    private static final String BASE64_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==";

    private static final AttributeKey<String> INPUT_JSON =
            AttributeKey.stringKey("braintrust.input_json");

    private static TestHarness testHarness;
    private static Tracer tracer;

    @BeforeAll
    static void initHarness() {
        testHarness = TestHarness.setup();
        tracer = testHarness.openTelemetry().getTracer("attachment-processor-test");
    }

    @Test
    @SneakyThrows
    void base64DataUriInImageUrlIsReplacedWithAttachmentReference() {
        String inputJson =
                "[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"What is in this"
                        + " image?\"},"
                        + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,"
                        + BASE64_PNG
                        + "\"}}]}]";

        var span = tracer.spanBuilder("attachment-test-image-url").startSpan();
        span.setAttribute("braintrust.input_json", inputJson);
        span.setAttribute("braintrust.parent", "project_name:" + TestHarness.defaultProjectName());
        span.end();

        var spans = testHarness.awaitExportedSpans(1);
        var exported =
                spans.stream()
                        .filter(s -> s.getName().equals("attachment-test-image-url"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("span not found"));

        String exportedInputJson = exported.getAttributes().get(INPUT_JSON);
        assertNotNull(exportedInputJson, "exported span should have braintrust.input_json");
        assertNotEquals(inputJson, exportedInputJson, "base64 data should have been replaced");

        // Parse and verify the attachment reference
        JsonNode root = BraintrustJsonMapper.get().readTree(exportedInputJson);
        JsonNode urlNode = root.get(0).get("content").get(1).get("image_url").get("url");

        assertTrue(
                urlNode.isObject(), "url should be an object (attachment reference), not a string");
        assertEquals("braintrust_attachment", urlNode.get("type").asText());
        assertEquals("image/png", urlNode.get("content_type").asText());
        assertEquals("attachment.png", urlNode.get("filename").asText());
        assertNotNull(urlNode.get("key"), "attachment key must be present");
        assertFalse(urlNode.get("key").asText().isEmpty(), "attachment key must not be empty");

        // Verify the rest of the message structure is intact
        assertEquals("user", root.get(0).get("role").asText());
        assertEquals("text", root.get(0).get("content").get(0).get("type").asText());
        assertEquals(
                "What is in this image?", root.get(0).get("content").get(0).get("text").asText());
        assertEquals("image_url", root.get(0).get("content").get(1).get("type").asText());
    }

    @Test
    void nonDataUriInputIsUnchanged() {
        String inputJson = "[{\"role\":\"user\",\"content\":\"Hello, how are you?\"}]";

        var span = tracer.spanBuilder("attachment-test-no-data-uri").startSpan();
        span.setAttribute("braintrust.input_json", inputJson);
        span.setAttribute("braintrust.parent", "project_name:" + TestHarness.defaultProjectName());
        span.end();

        var spans = testHarness.awaitExportedSpans(1);
        var exported =
                spans.stream()
                        .filter(s -> s.getName().equals("attachment-test-no-data-uri"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("span not found"));

        String exportedInputJson = exported.getAttributes().get(INPUT_JSON);
        assertEquals(inputJson, exportedInputJson, "input without base64 data should be unchanged");
    }

    @Test
    void partialDataUriInTextIsNotReplaced() {
        // A data URI embedded in surrounding text should NOT be replaced (isEntirelyDataUri check)
        String inputJson =
                "[{\"role\":\"user\",\"content\":\"Check this: data:image/png;base64,"
                        + BASE64_PNG
                        + " please\"}]";

        var span = tracer.spanBuilder("attachment-test-partial-data-uri").startSpan();
        span.setAttribute("braintrust.input_json", inputJson);
        span.setAttribute("braintrust.parent", "project_name:" + TestHarness.defaultProjectName());
        span.end();

        var spans = testHarness.awaitExportedSpans(1);
        var exported =
                spans.stream()
                        .filter(s -> s.getName().equals("attachment-test-partial-data-uri"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("span not found"));

        String exportedInputJson = exported.getAttributes().get(INPUT_JSON);
        assertEquals(
                inputJson,
                exportedInputJson,
                "partial data URI embedded in text should not be replaced");
    }
}
