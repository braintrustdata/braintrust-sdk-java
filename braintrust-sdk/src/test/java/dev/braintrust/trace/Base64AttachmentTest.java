package dev.braintrust.trace;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class Base64AttachmentTest {
    private static final ObjectMapper JSON_MAPPER = createObjectMapper();

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(Base64Attachment.class, Base64Attachment.createSerializer());
        mapper.registerModule(module);
        return mapper;
    }

    @Test
    void testOfWithValidDataUrl() {
        String validDataUrl = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUA";
        Base64Attachment attachment = Base64Attachment.of(validDataUrl);
        assertNotNull(attachment);
    }

    @Test
    void testBadBase64Data() {
        assertThrows(Exception.class, () -> Base64Attachment.of(null));
        assertThrows(Exception.class, () -> Base64Attachment.of(""));
    }

    @Test
    void testOfWithoutDataPrefixThrowsException() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> Base64Attachment.of("image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUA"));
        assertTrue(exception.getMessage().contains("data URL with format"));
    }

    @Test
    void testOfBase64WithoutMarkerThrowsException() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> Base64Attachment.of("data:image/png,iVBORw0KGgoAAAANSUhEUgAAAAUA"));
        assertTrue(exception.getMessage().contains("data URL with format"));
    }

    @Test
    void testOfFileWithNonExistentFileThrowsException() {
        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                Base64Attachment.ofFile(
                                        Base64Attachment.ContentType.IMAGE_PNG,
                                        "/nonexistent/path/to/file.png"));
        assertTrue(exception.getMessage().contains("Failed to read file"));
    }

    @Test
    @SneakyThrows
    void testFileCreatesBase64Content(@TempDir Path tempDir) {
        // Create a test file
        Path testFile = tempDir.resolve("test.jpg");
        byte[] testData = "test jpeg data".getBytes();
        Files.write(testFile, testData);

        // Create attachment from file
        Base64Attachment attachment =
                Base64Attachment.ofFile(
                        Base64Attachment.ContentType.IMAGE_JPEG, testFile.toString());

        // Serialize to JSON to verify the data URL format
        String json = JSON_MAPPER.writeValueAsString(attachment);

        // Parse JSON and verify structure
        var jsonNode = JSON_MAPPER.readTree(json);
        assertEquals(2, jsonNode.size());

        assertEquals("base64_attachment", jsonNode.get("type").asText());

        String content = jsonNode.get("content").asText();
        assertTrue(content.startsWith("data:image/jpeg;base64,"));

        // Verify the base64 data is correct
        String base64Part = content.substring("data:image/jpeg;base64,".length());
        byte[] decodedData = Base64.getDecoder().decode(base64Part);
        assertArrayEquals(testData, decodedData);
    }

    @Test
    void testContentTypeOfNormalizesToLowercase() {
        var customType = Base64Attachment.ContentType.of("IMAGE/PNG");
        assertEquals("image/png", customType.getMimeType());
    }

    @Test
    void testContentTypeOfWithNullThrowsException() {
        assertThrows(NullPointerException.class, () -> Base64Attachment.ContentType.of(null));
    }

    @Test
    void testContentTypeEquality() {
        var type1 = Base64Attachment.ContentType.of("image/png");
        var type2 = Base64Attachment.ContentType.of("image/png");
        var type3 = Base64Attachment.ContentType.of("image/jpeg");

        assertEquals(type1, type2);
        assertNotEquals(type1, type3);
        assertEquals(type1, Base64Attachment.ContentType.IMAGE_PNG);
    }

    @Test
    void testContentTypeHashCode() {
        var type1 = Base64Attachment.ContentType.of("image/png");
        var type2 = Base64Attachment.ContentType.of("image/png");

        assertEquals(type1.hashCode(), type2.hashCode());
    }
}
