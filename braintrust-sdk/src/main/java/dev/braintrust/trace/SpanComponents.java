package dev.braintrust.trace;

import dev.braintrust.BraintrustUtils;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Represents span components for distributed tracing in object format.
 *
 * <p>This is used to pass parent span context when invoking remote functions, enabling the remote
 * function's spans to appear as children of the caller's span in the Braintrust UI.
 */
public record SpanComponents(BraintrustUtils.Parent parent, @Nullable RowIds rowIds) {
    /**
     * braintrust-native SDKs have three IDs. Object id (braintrust db identifier), root span id,
     * and span id
     *
     * <p>Otel SDKs only have root span id and span id, so we send a special string to the backend
     * to signal to trace propagation logic that this trace came from an otel trace.
     */
    private static final String BRAINTRUST_OTEL_OBJECT_ID = "otel";

    /**
     * Row IDs for linking spans within a trace.
     *
     * @param spanId The OTEL span ID (16 hex characters)
     * @param rootSpanId The OTEL trace ID (32 hex characters)
     */
    public record RowIds(String spanId, String rootSpanId) {

        /** Convert to a Map for JSON serialization. */
        public Map<String, Object> toMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("id", BRAINTRUST_OTEL_OBJECT_ID);
            map.put("span_id", spanId);
            map.put("root_span_id", rootSpanId);
            return map;
        }
    }

    /**
     * Convert to a Map for JSON serialization.
     *
     * <p>The resulting map matches the InvokeParent object format expected by the API.
     */
    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("object_type", spanComponentsObjectType());
        map.put("object_id", parent.id());
        if (rowIds != null) {
            map.put("row_ids", rowIds.toMap());
        }
        return map;
    }

    private String spanComponentsObjectType() {
        return switch (parent.type()) {
            case "experiment_id" -> "experiment";
            case "playground_id" -> "playground_logs";
            case "project_id" -> "project_logs";
            default -> throw new RuntimeException("unknown parent type: " + parent.type());
        };
    }
}
