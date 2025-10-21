package dev.braintrust.trace;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Objects;
import javax.annotation.Nonnull;
import lombok.Getter;

/**
 * Utility to serialize LLM attachment data in a braintrust-friendly manner.
 *
 * <p>Users of the SDK likely don't need to use this utility directly because instrumentation will
 * properly serialize messages out of the box.
 *
 * <p>The serialized json will conform to the otel input/output GenericPart schema. See
 * https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-input-messages.json and
 * https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-output-messages.json
 */
public class Base64Attachment {
    @JsonProperty("type")
    @Getter
    private final String type = "base64_attachment";

    @JsonProperty("content")
    @Getter
    private final String base64Data;

    private Base64Attachment(@Nonnull String base64Data) {
        if (Objects.requireNonNull(base64Data).isEmpty()) {
            throw new IllegalArgumentException("base64Data cannot be empty");
        }
        // Check for data URL prefix (e.g., "data:image/png;base64,...")
        if (!base64Data.startsWith("data:") || !base64Data.contains(";base64,")) {
            throw new IllegalArgumentException(
                    "base64Data must be a data URL with format:"
                            + " data:<mime-type>;base64,<base64-string>");
        }

        this.base64Data = base64Data;
    }

    /**
     * Create a new attachment out of base64 data
     *
     * @param base64DataUri must conform to data:(content-type);base64,BYTES
     */
    public static Base64Attachment of(String base64DataUri) {
        return new Base64Attachment(base64DataUri);
    }

    /** convenience utility to convert a file to a base64 attachment */
    public static Base64Attachment ofFile(ContentType contentType, String filePath) {
        try {
            Path path = Paths.get(filePath);
            byte[] fileBytes = Files.readAllBytes(path);
            String base64Encoded = Base64.getEncoder().encodeToString(fileBytes);
            String dataUrl = "data:" + contentType.getMimeType() + ";base64," + base64Encoded;
            return of(dataUrl);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file: " + filePath, e);
        }
    }

    /** create a jackson serializer for attachment data */
    public static JsonSerializer<Base64Attachment> createSerializer() {
        return new JsonSerializer<>() {
            @Override
            public void serialize(
                    Base64Attachment value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeStartObject();
                try {
                    gen.writeStringField("type", value.type);
                    gen.writeStringField("content", value.base64Data);
                } finally {
                    gen.writeEndObject();
                }
            }
        };
    }

    public static class ContentType {
        // Common image formats
        public static ContentType IMAGE_PNG = new ContentType("image/png");
        public static ContentType IMAGE_JPEG = new ContentType("image/jpeg");
        public static ContentType IMAGE_GIF = new ContentType("image/gif");
        public static ContentType IMAGE_WEBP = new ContentType("image/webp");
        public static ContentType IMAGE_SVG = new ContentType("image/svg+xml");

        // Common document formats
        public static ContentType APPLICATION_PDF = new ContentType("application/pdf");
        public static ContentType TEXT_PLAIN = new ContentType("text/plain");
        public static ContentType APPLICATION_JSON = new ContentType("application/json");

        public static ContentType of(@Nonnull String mimeType) {
            return new ContentType(mimeType);
        }

        @Getter private final @Nonnull String mimeType;

        @Override
        public int hashCode() {
            return mimeType.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof ContentType) {
                return mimeType.equals(((ContentType) obj).mimeType);
            } else {
                return super.equals(obj);
            }
        }

        private ContentType(@Nonnull String mimeType) {
            Objects.requireNonNull(mimeType);
            this.mimeType = mimeType.toLowerCase();
        }
    }
}
