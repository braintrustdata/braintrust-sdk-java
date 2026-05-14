package dev.braintrust.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.json.BraintrustJsonMapper;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * Scans JSON content for base64 data URI attachments and replaces them with attachment references
 * after uploading to S3.
 *
 * <p>Package-private; not exposed in the public API.
 */
@Slf4j
class AttachmentProcessor {
    /**
     * quick heuristic to determine if the json payload contains a base64 encoded file
     *
     * <p>This is used for performance reasons as a fail-fast to avoid doing a json parse.
     */
    static final Pattern BASE64_DATA_URI_PATTERN =
            Pattern.compile("data:([\\w/\\-.+]+);base64,([A-Za-z0-9+/=]{20,})");

    private final BraintrustConfig config;
    private final AttachmentUploader uploader;

    AttachmentProcessor(BraintrustConfig config, AttachmentUploader uploader) {
        this.config = config;
        this.uploader = uploader;
    }

    /**
     * Scans a JSON string for base64 data URIs, uploads them, and returns the modified JSON with
     * attachment references.
     *
     * @param json the JSON string to scan
     * @return the modified JSON with base64 data replaced by attachment references, or the original
     *     JSON if no base64 data was found
     */
    String processAndUpload(String json) {
        if ((!config.autoConvertAIAttachments())
                || json == null
                || uploader.isShutdown()
                || !BASE64_DATA_URI_PATTERN.matcher(json).find()) {
            return json;
        }

        try {
            JsonNode root = BraintrustJsonMapper.get().readTree(json);
            AtomicBoolean modified = new AtomicBoolean(false);
            JsonNode result = replaceBase64Attachments(root, modified);
            return modified.get() ? BraintrustJsonMapper.get().writeValueAsString(result) : json;
        } catch (UploaderRejectionException e) {
            log.debug(
                    "attachment uploader rejected job. Proceeding without attachment"
                            + " replacements.");
            return json;
        } catch (Exception | StackOverflowError e) {
            log.info("uploader optimization failed, falling back to span uploads", e);
            uploader.shutdown(Duration.ofSeconds(0)); // don't block
            return json;
        }
    }

    // NOTE: not concerned with recursion blowing the stack because we're mutating AI vendor
    // messages which are not deep enough for this to be an issue.
    private JsonNode replaceBase64Attachments(JsonNode node, AtomicBoolean modified) {
        if (node.isTextual()) {
            return replaceInText((TextNode) node, modified);
        } else if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            ObjectNode result = BraintrustJsonMapper.get().createObjectNode();
            var fieldNames = objectNode.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                JsonNode child = objectNode.get(fieldName);
                result.set(fieldName, replaceBase64Attachments(child, modified));
            }
            return result;
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            ArrayNode result = BraintrustJsonMapper.get().createArrayNode();
            for (int i = 0; i < arrayNode.size(); i++) {
                result.add(replaceBase64Attachments(arrayNode.get(i), modified));
            }
            return result;
        }
        return node;
    }

    @SneakyThrows
    private JsonNode replaceInText(TextNode textNode, AtomicBoolean modified) {
        String value = textNode.asText();
        Matcher matcher = BASE64_DATA_URI_PATTERN.matcher(value);
        if (!matcher.find()) {
            return textNode;
        }
        if (!isEntirelyDataUri(value)) {
            log.debug("found base64 string but text contained extra content {}", value);
            return textNode;
        }

        matcher.reset();
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String contentType = matcher.group(1);
            String base64Data = matcher.group(2);
            byte[] data = Base64.getDecoder().decode(base64Data);

            String extension = contentTypeToExtension(contentType);
            String filename = "attachment" + extension;
            AttachmentReference ref = AttachmentReference.create(filename, contentType);

            if (!uploader.enqueue(ref, data)) {
                throw new UploaderRejectionException("uploader rejected attachment upload");
            }

            matcher.appendReplacement(sb, Matcher.quoteReplacement(ref.toJson()));
        }
        matcher.appendTail(sb);

        modified.set(true);

        return BraintrustJsonMapper.get().readTree(sb.toString());
    }

    static boolean isEntirelyDataUri(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("data:")
                && !trimmed.contains("\"")
                && !trimmed.contains("\\")
                && !trimmed.contains(" ");
    }

    private static String contentTypeToExtension(String contentType) {
        switch (contentType.toLowerCase()) {
            case "image/png":
                return ".png";
            case "image/jpeg":
            case "image/jpg":
                return ".jpg";
            case "image/gif":
                return ".gif";
            case "image/webp":
                return ".webp";
            case "image/svg+xml":
                return ".svg";
            case "application/pdf":
                return ".pdf";
            case "text/plain":
                return ".txt";
            case "application/json":
                return ".json";
            default:
                String[] parts = contentType.split("/");
                if (parts.length == 2) {
                    return "." + parts[1].split("[;\\-]")[0];
                }
                return "";
        }
    }

    private static class UploaderRejectionException extends RuntimeException {
        public UploaderRejectionException(String message) {
            super(message);
        }
    }
}
