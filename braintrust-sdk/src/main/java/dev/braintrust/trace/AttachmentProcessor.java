package dev.braintrust.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.json.BraintrustJsonMapper;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * Scans JSON content for base64 attachments across multiple LLM provider formats (OpenAI, Bedrock,
 * Anthropic, Gemini) and replaces them with attachment references after uploading to object
 * storage. Handles all attachment types: images, documents, video, audio, etc.
 *
 * <p>Package-private; not exposed in the public API.
 */
@Slf4j
class AttachmentProcessor {
    private static final String DATA_URI_PREFIX = "data:([\\w/\\-.+]+);base64,";
    private static final String BASE64STRING = "([A-Za-z0-9+/=]{20,})";

    /** Matches data URIs in unquoted text node values — used by the OpenAI matcher/replacer. */
    private static final Pattern DATA_URI_PATTERN =
            Pattern.compile("%s%s".formatted(DATA_URI_PREFIX, BASE64STRING));

    /** Matches data URIs in the raw JSON string (with surrounding quotes) for the heuristic. */
    private static final Pattern DATA_URI_HEURISTIC_PATTERN =
            Pattern.compile("\"%s\"".formatted(DATA_URI_PATTERN.pattern()));

    private static final Pattern BYTE_TEXT_VALUE_PATTERN =
            Pattern.compile("\"(bytes|data)\"\\s*:\\s*\"%s\"".formatted(BASE64STRING));

    /**
     * Supported provider attachment formats, checked in order during tree traversal. To add a new
     * provider, append an {@link AttachmentFormat} entry here.
     */
    static final List<AttachmentFormat> ATTACHMENT_FORMATS =
            List.of(
                    // OpenAI: data URI in a text node value
                    // e.g. image_url.url = "data:image/png;base64,..." or
                    //      file.file_data = "data:application/pdf;base64,..."
                    new AttachmentFormat(
                            "openai",
                            DATA_URI_HEURISTIC_PATTERN.pattern(),
                            (key, node) ->
                                    node.isTextual()
                                            && isEntirelyDataUri(node.asText())
                                            && DATA_URI_PATTERN.matcher(node.asText()).find(),
                            AttachmentProcessor::replaceOpenAIDataUri),

                    // Bedrock Converse: parent block has a key like "image", "document",
                    // "video", or "audio" wrapping {"format": "png", "source": {"bytes": "..."}}
                    // We match at the parent level to use the block type key for MIME resolution,
                    // so ambiguous formats like "mp4" get the correct media category.
                    new AttachmentFormat(
                            "bedrock",
                            BYTE_TEXT_VALUE_PATTERN.pattern(),
                            (key, node) -> {
                                if (!node.isObject()) return false;
                                return getConverseBlock((ObjectNode) node) != null;
                            },
                            AttachmentProcessor::replaceBedrockAttachment),

                    // Anthropic: {"type": "base64", "media_type": "image/png", "data": "<base64>"}
                    // Applies to image and document source objects
                    new AttachmentFormat(
                            "anthropic",
                            BYTE_TEXT_VALUE_PATTERN.pattern(),
                            (key, node) -> {
                                if (!node.isObject()) return false;
                                JsonNode type = node.get("type");
                                JsonNode mediaType = node.get("media_type");
                                JsonNode data = node.get("data");
                                return type != null
                                        && "base64".equals(type.asText())
                                        && mediaType != null
                                        && mediaType.isTextual()
                                        && data != null
                                        && data.isTextual()
                                        && data.asText().length() >= 20;
                            },
                            AttachmentProcessor::replaceAnthropicAttachment),

                    // Gemini: {"inlineData": {"mimeType": "image/png", "data": "<base64>"}}
                    // Applies to all inline binary content (images, PDFs, audio, video)
                    new AttachmentFormat(
                            "gemini",
                            BYTE_TEXT_VALUE_PATTERN.pattern(),
                            (key, node) -> {
                                if (!node.isObject()) return false;
                                JsonNode inlineData = node.get("inlineData");
                                if (inlineData == null || !inlineData.isObject()) return false;
                                JsonNode mimeType = inlineData.get("mimeType");
                                JsonNode data = inlineData.get("data");
                                return mimeType != null
                                        && mimeType.isTextual()
                                        && data != null
                                        && data.isTextual()
                                        && data.asText().length() >= 20;
                            },
                            AttachmentProcessor::replaceGeminiAttachment));

    /**
     * Fast-path heuristic compiled from all {@link #ATTACHMENT_FORMATS} entries. If this doesn't
     * match the raw JSON string, we skip JSON parsing entirely.
     */
    static final Pattern BASE64_HEURISTIC = buildHeuristic();

    private static Pattern buildHeuristic() {
        var fragments = new LinkedHashSet<String>();
        for (var fmt : ATTACHMENT_FORMATS) {
            fragments.add(fmt.heuristicFragment());
        }
        return Pattern.compile(fragments.stream().collect(Collectors.joining("|")));
    }

    private final BraintrustConfig config;
    private final AttachmentUploader uploader;

    AttachmentProcessor(BraintrustConfig config, AttachmentUploader uploader) {
        this.config = config;
        this.uploader = uploader;
    }

    /**
     * Scans a JSON string for base64 attachments, uploads them, and returns the modified JSON with
     * attachment references.
     */
    String processAndUpload(String json) {
        if ((!config.autoConvertAIAttachments())
                || json == null
                || uploader.isShutdown()
                || !BASE64_HEURISTIC.matcher(json).find()) {
            return json;
        }

        try {
            JsonNode root = BraintrustJsonMapper.get().readTree(json);
            AtomicBoolean modified = new AtomicBoolean(false);
            JsonNode result = walkAndReplace(null, null, root, modified);
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

    /**
     * Walks the JSON tree. For each node, checks all {@link #ATTACHMENT_FORMATS} matchers. If one
     * matches, calls its replacer and returns the result (no further recursion into that subtree).
     * Otherwise recurses into children.
     */
    private JsonNode walkAndReplace(
            @Nullable ObjectNode parent,
            @Nullable String fieldName,
            @Nonnull JsonNode node,
            @Nonnull AtomicBoolean modified) {

        // Check each registered format
        for (var fmt : ATTACHMENT_FORMATS) {
            if (fmt.matcher().apply(fieldName, node)) {
                JsonNode replacement = fmt.replacer().apply(parent, fieldName, node, uploader);
                if (replacement != null) {
                    modified.set(true);
                    return replacement;
                }
            }
        }

        // No format matched — recurse into children
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            ObjectNode result = BraintrustJsonMapper.get().createObjectNode();
            var fields = objectNode.fieldNames();
            while (fields.hasNext()) {
                String childField = fields.next();
                JsonNode child = objectNode.get(childField);
                result.set(childField, walkAndReplace(objectNode, childField, child, modified));
            }
            return result;
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            ArrayNode result = BraintrustJsonMapper.get().createArrayNode();
            for (int i = 0; i < arrayNode.size(); i++) {
                result.add(walkAndReplace(null, null, arrayNode.get(i), modified));
            }
            return result;
        }
        return node;
    }

    // ── Replacer implementations ──────────────────────────────────────

    /** OpenAI: replace a data URI text node with an attachment reference object. */
    @SneakyThrows
    private static JsonNode replaceOpenAIDataUri(
            ObjectNode parent, String fieldName, JsonNode node, AttachmentUploader uploader) {
        var matcher = DATA_URI_PATTERN.matcher(node.asText());
        if (!matcher.find()) return null;

        String contentType = matcher.group(1);
        String base64Data = matcher.group(2);
        return uploadAndCreateRef(contentType, base64Data, uploader);
    }

    /**
     * Bedrock Converse: find and replace the attachment block within the parent content block.
     * Matches at parent level so the block type key (image/video/audio/document) determines the
     * MIME category.
     */
    @SneakyThrows
    private static JsonNode replaceBedrockAttachment(
            ObjectNode parent, String fieldName, JsonNode node, AttachmentUploader uploader) {
        ObjectNode obj = (ObjectNode) node;
        ConverseBlock block = getConverseBlock(obj);
        if (block == null) return null;

        ObjectNode inner = block.inner;
        String format = inner.get("format").asText();
        String contentType = block.formatMap.get(format.toLowerCase());
        if (contentType == null) return null;

        String base64Data = inner.get("source").get("bytes").asText();
        JsonNode refNode = uploadAndCreateRef(contentType, base64Data, uploader);
        if (refNode == null) return null;

        // Rebuild parent: copy all fields, but replace source.bytes in the matched block
        ObjectNode result = BraintrustJsonMapper.get().createObjectNode();
        var fields = obj.fieldNames();
        while (fields.hasNext()) {
            String f = fields.next();
            if (f.equals(block.blockTypeKey)) {
                ObjectNode newInner = BraintrustJsonMapper.get().createObjectNode();
                var innerFields = inner.fieldNames();
                while (innerFields.hasNext()) {
                    String inf = innerFields.next();
                    if ("source".equals(inf)) {
                        ObjectNode origSource = (ObjectNode) inner.get("source");
                        ObjectNode newSource = BraintrustJsonMapper.get().createObjectNode();
                        var sourceFields = origSource.fieldNames();
                        while (sourceFields.hasNext()) {
                            String sf = sourceFields.next();
                            newSource.set(sf, "bytes".equals(sf) ? refNode : origSource.get(sf));
                        }
                        newInner.set("source", newSource);
                    } else {
                        newInner.set(inf, inner.get(inf));
                    }
                }
                result.set(f, newInner);
            } else {
                result.set(f, obj.get(f));
            }
        }
        return result;
    }

    /** Anthropic: replace the entire source object with the attachment ref. */
    @SneakyThrows
    private static JsonNode replaceAnthropicAttachment(
            ObjectNode parent, String fieldName, JsonNode node, AttachmentUploader uploader) {
        ObjectNode obj = (ObjectNode) node;
        String contentType = obj.get("media_type").asText();
        String base64Data = obj.get("data").asText();
        return uploadAndCreateRef(contentType, base64Data, uploader);
    }

    /**
     * Gemini: replace {@code inlineData} with an attachment reference wrapper. Images use {@code
     * image_url: {url: ref}}, all other content types use {@code file: {file_data: ref}}.
     */
    @SneakyThrows
    private static JsonNode replaceGeminiAttachment(
            ObjectNode parent, String fieldName, JsonNode node, AttachmentUploader uploader) {
        ObjectNode obj = (ObjectNode) node;
        ObjectNode inlineData = (ObjectNode) obj.get("inlineData");
        String contentType = inlineData.get("mimeType").asText();
        String base64Data = inlineData.get("data").asText();

        JsonNode refNode = uploadAndCreateRef(contentType, base64Data, uploader);
        if (refNode == null) return null;

        boolean isImage = contentType.startsWith("image/");

        // Rebuild: swap inlineData for the appropriate wrapper
        ObjectNode result = BraintrustJsonMapper.get().createObjectNode();
        var fields = obj.fieldNames();
        while (fields.hasNext()) {
            String f = fields.next();
            if ("inlineData".equals(f)) {
                if (isImage) {
                    ObjectNode imageUrl = BraintrustJsonMapper.get().createObjectNode();
                    imageUrl.set("url", refNode);
                    result.set("image_url", imageUrl);
                } else {
                    ObjectNode file = BraintrustJsonMapper.get().createObjectNode();
                    file.set("file_data", refNode);
                    result.set("file", file);
                }
            } else {
                result.set(f, obj.get(f));
            }
        }
        return result;
    }

    // ── Shared helpers ────────────────────────────────────────────────

    /** Decodes base64 data, uploads it, and returns the attachment reference as a JsonNode. */
    @SneakyThrows
    private static JsonNode uploadAndCreateRef(
            String contentType, String base64Data, AttachmentUploader uploader) {
        byte[] data;
        try {
            data = Base64.getDecoder().decode(base64Data);
        } catch (IllegalArgumentException e) {
            log.debug("Failed to decode base64 data, skipping");
            return null;
        }

        String extension = contentTypeToExtension(contentType);
        String filename = "attachment" + extension;
        AttachmentReference ref = AttachmentReference.create(filename, contentType);
        if (!uploader.enqueue(ref, data)) {
            throw new UploaderRejectionException("uploader rejected attachment upload");
        }
        return BraintrustJsonMapper.get().readTree(ref.toJson());
    }

    static boolean isEntirelyDataUri(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("data:")
                && !trimmed.contains("\"")
                && !trimmed.contains("\\")
                && !trimmed.contains(" ");
    }

    // ── Bedrock Converse block detection ─────────────────────────────

    /** Per-block-type format-to-MIME mappings for the AWS Bedrock Converse API. */
    private static final java.util.Map<String, String> CONVERSE_IMAGE_FORMATS =
            java.util.Map.of(
                    "gif", "image/gif",
                    "jpeg", "image/jpeg",
                    "png", "image/png",
                    "webp", "image/webp");

    private static final java.util.Map<String, String> CONVERSE_VIDEO_FORMATS =
            java.util.Map.ofEntries(
                    java.util.Map.entry("flv", "video/x-flv"),
                    java.util.Map.entry("mkv", "video/x-matroska"),
                    java.util.Map.entry("mov", "video/quicktime"),
                    java.util.Map.entry("mp4", "video/mp4"),
                    java.util.Map.entry("mpeg", "video/mpeg"),
                    java.util.Map.entry("mpg", "video/mpeg"),
                    java.util.Map.entry("three_gp", "video/3gpp"),
                    java.util.Map.entry("webm", "video/webm"),
                    java.util.Map.entry("wmv", "video/x-ms-wmv"));

    private static final java.util.Map<String, String> CONVERSE_AUDIO_FORMATS =
            java.util.Map.ofEntries(
                    java.util.Map.entry("aac", "audio/aac"),
                    java.util.Map.entry("flac", "audio/flac"),
                    java.util.Map.entry("m4a", "audio/mp4"),
                    java.util.Map.entry("mka", "audio/x-matroska"),
                    java.util.Map.entry("mkv", "audio/x-matroska"),
                    java.util.Map.entry("mp3", "audio/mpeg"),
                    java.util.Map.entry("mp4", "audio/mp4"),
                    java.util.Map.entry("mpeg", "audio/mpeg"),
                    java.util.Map.entry("mpga", "audio/mpeg"),
                    java.util.Map.entry("ogg", "audio/ogg"),
                    java.util.Map.entry("opus", "audio/opus"),
                    java.util.Map.entry("pcm", "audio/pcm"),
                    java.util.Map.entry("wav", "audio/wav"),
                    java.util.Map.entry("webm", "audio/webm"),
                    java.util.Map.entry("x-aac", "audio/aac"));

    private static final java.util.Map<String, String> CONVERSE_DOCUMENT_FORMATS =
            java.util.Map.ofEntries(
                    java.util.Map.entry("csv", "text/csv"),
                    java.util.Map.entry("doc", "application/msword"),
                    java.util.Map.entry(
                            "docx",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                    java.util.Map.entry("html", "text/html"),
                    java.util.Map.entry("md", "text/markdown"),
                    java.util.Map.entry("pdf", "application/pdf"),
                    java.util.Map.entry("txt", "text/plain"),
                    java.util.Map.entry("xls", "application/vnd.ms-excel"),
                    java.util.Map.entry(
                            "xlsx",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));

    /** Maps Bedrock Converse block type keys to their format-to-MIME maps. */
    private static final java.util.Map<String, java.util.Map<String, String>>
            CONVERSE_BLOCK_TYPE_FORMATS =
                    java.util.Map.of(
                            "image", CONVERSE_IMAGE_FORMATS,
                            "video", CONVERSE_VIDEO_FORMATS,
                            "audio", CONVERSE_AUDIO_FORMATS,
                            "document", CONVERSE_DOCUMENT_FORMATS);

    private record ConverseBlock(
            String blockTypeKey, ObjectNode inner, java.util.Map<String, String> formatMap) {}

    /**
     * Checks whether a JSON object is a Bedrock Converse content block containing a recognized
     * block type key (image/video/audio/document) wrapping {@code {format, source: {bytes}}}.
     */
    @Nullable
    private static ConverseBlock getConverseBlock(ObjectNode obj) {
        for (var entry : CONVERSE_BLOCK_TYPE_FORMATS.entrySet()) {
            String blockKey = entry.getKey();
            var formatMap = entry.getValue();
            JsonNode inner = obj.get(blockKey);
            if (inner == null || !inner.isObject()) continue;
            JsonNode fmt = inner.get("format");
            JsonNode src = inner.get("source");
            if (fmt == null || !fmt.isTextual() || src == null || !src.isObject()) continue;
            if (!formatMap.containsKey(fmt.asText().toLowerCase())) continue;
            JsonNode bytes = src.get("bytes");
            if (bytes == null || !bytes.isTextual() || bytes.asText().length() < 20) continue;
            return new ConverseBlock(blockKey, (ObjectNode) inner, formatMap);
        }
        return null;
    }

    static String contentTypeToExtension(String contentType) {
        return switch (contentType.toLowerCase()) {
            case "image/png" -> ".png";
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            case "application/pdf" -> ".pdf";
            case "text/plain" -> ".txt";
            case "text/csv" -> ".csv";
            case "text/html" -> ".html";
            case "application/json" -> ".json";
            case "application/msword" -> ".doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                    ".docx";
            case "application/vnd.ms-excel" -> ".xls";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx";
            case "video/mp4" -> ".mp4";
            case "video/webm" -> ".webm";
            case "video/quicktime" -> ".mov";
            case "audio/mpeg", "audio/mp3" -> ".mp3";
            case "audio/wav" -> ".wav";
            default -> {
                String[] parts = contentType.split("/");
                yield (parts.length == 2) ? "." + parts[1].split("[;\\-]")[0] : "";
            }
        };
    }

    /**
     * Describes how to detect and replace a provider-specific base64 attachment structure. To add a
     * new provider format, append an entry to {@link #ATTACHMENT_FORMATS}.
     *
     * @param name human-readable name for logging/debugging and test coverage tracking
     * @param heuristicFragment regex fragment for the fast-path heuristic. Multiple formats'
     *     fragments are combined with {@code |} into a single pattern. Duplicates are removed.
     * @param matcher predicate called on every node during tree traversal. Returns {@code true} if
     *     this format should handle the node. Parameters: ({@code fieldName} of this node in its
     *     parent — null for root/array elements, {@code node} the current node).
     * @param replacer builds the replacement node. Parameters: ({@code parent} the parent
     *     ObjectNode or null, {@code fieldName} of this node in the parent, {@code node} the
     *     matched node). Returns the replacement node, or {@code null} to skip.
     */
    record AttachmentFormat(
            @Nonnull String name,
            @Nonnull String heuristicFragment,
            @Nonnull BiFunction<String, JsonNode, Boolean> matcher,
            @Nonnull ReplacerFunction replacer) {

        @FunctionalInterface
        interface ReplacerFunction {
            JsonNode apply(
                    @Nullable ObjectNode parent,
                    @Nullable String fieldName,
                    @Nonnull JsonNode node,
                    @Nonnull AttachmentUploader uploader);
        }
    }

    private static class UploaderRejectionException extends RuntimeException {
        public UploaderRejectionException(String message) {
            super(message);
        }
    }
}
