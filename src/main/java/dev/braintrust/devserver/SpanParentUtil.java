package dev.braintrust.devserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Utility for creating parent span identifiers compatible with Python's SpanComponentsV4 format.
 * This creates a simplified base64-encoded format that matches the Python implementation.
 */
public class SpanParentUtil {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final int ENCODING_VERSION = 3; // Using V3 for simplicity

    /**
     * Parse parent object from request and create a span parent identifier string.
     *
     * @param parentObj The parent object from the request (Map with object_type, object_id, etc.)
     * @return Base64-encoded parent identifier string, or null if parentObj is null
     */
    public static String parseParent(Object parentObj) {
        if (parentObj == null) {
            return null;
        }

        if (parentObj instanceof String) {
            return (String) parentObj;
        }

        if (!(parentObj instanceof Map)) {
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parentMap = (Map<String, Object>) parentObj;

        String objectType = (String) parentMap.get("object_type");
        String objectId = (String) parentMap.get("object_id");

        if (objectType == null || objectId == null) {
            return null;
        }

        // Map object types to their enum values
        int objectTypeValue;
        switch (objectType) {
            case "experiment":
                objectTypeValue = 1;
                break;
            case "project_logs":
                objectTypeValue = 2;
                break;
            case "playground_logs":
                objectTypeValue = 3;
                break;
            default:
                throw new IllegalArgumentException("Invalid object_type: " + objectType);
        }

        try {
            // Build a JSON object for the non-binary fields
            Map<String, Object> jsonData = new LinkedHashMap<>();
            jsonData.put("object_id", objectId);

            // Include propagated_event if present
            if (parentMap.containsKey("propagated_event")) {
                jsonData.put("propagated_event", parentMap.get("propagated_event"));
            }

            String jsonString = JSON_MAPPER.writeValueAsString(jsonData);
            byte[] jsonBytes = jsonString.getBytes(StandardCharsets.UTF_8);

            // Create binary format: version_byte + object_type_byte + num_hex_fields(0) + json
            byte[] binaryData = new byte[3 + jsonBytes.length];
            binaryData[0] = (byte) ENCODING_VERSION;
            binaryData[1] = (byte) objectTypeValue;
            binaryData[2] = 0; // num_hex_fields = 0 (simplified, no hex encoding)
            System.arraycopy(jsonBytes, 0, binaryData, 3, jsonBytes.length);

            // Base64 encode
            return Base64.getEncoder().encodeToString(binaryData);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode parent span identifier", e);
        }
    }
}
