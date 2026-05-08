package dev.braintrust.trace;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Represents an attachment reference stored on a span in place of uploaded attachment data.
 *
 * <p>Its shape intentionally matches the cross-SDK Braintrust attachment reference format.
 */
record AttachmentReference(
        @Nonnull String type,
        @Nonnull String filename,
        @Nonnull String contentType,
        @Nonnull String key) {

    private static final String DEFAULT_TYPE = "braintrust_attachment";

    /**
     * Creates an attachment reference with a generated UUID key.
     *
     * @param filename the display filename for the attachment
     * @param contentType the MIME type of the attachment content
     * @return a new AttachmentReference with a unique key
     */
    static AttachmentReference create(@Nonnull String filename, @Nonnull String contentType) {
        Objects.requireNonNull(filename, "filename cannot be null");
        Objects.requireNonNull(contentType, "contentType cannot be null");
        return new AttachmentReference(
                DEFAULT_TYPE, filename, contentType, UUID.randomUUID().toString());
    }

    public String toJson() {
        return "{\"type\":\"braintrust_attachment\",\"content_type\":\""
                + contentType()
                + "\",\"filename\":\""
                + filename()
                + "\",\"key\":\""
                + key()
                + "\"}";
    }
}
