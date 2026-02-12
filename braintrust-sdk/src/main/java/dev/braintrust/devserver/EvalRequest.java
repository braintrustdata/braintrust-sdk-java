package dev.braintrust.devserver;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.Data;

/** Request body for POST /eval endpoint */
@Data
public class EvalRequest {
    /** Name of the evaluator to run */
    private String name;

    /** Optional parameter overrides */
    @Nullable private Map<String, Object> parameters;

    /** Dataset specification */
    private DataSpec data;

    /** Optional experiment name override */
    @JsonProperty("experiment_name")
    @Nullable
    private String experimentName;

    /** Optional project ID override */
    @JsonProperty("project_id")
    @Nullable
    private String projectId;

    /** Optional additional remote scorers */
    @Nullable private List<RemoteScorer> scores;

    /** Optional parent span for tracing (can be string or object) */
    @Nullable private Object parent;

    /** Enable SSE streaming (default: false) */
    @Nullable private Boolean stream;

    /** Dataset specification - supports inline data, by name, or by ID */
    @Data
    public static class DataSpec {
        /** Inline data array */
        @Nullable private List<EvalCaseData> data;

        /** Project name (for loading by name) */
        @JsonProperty("project_name")
        @Nullable
        private String projectName;

        /** Dataset name (for loading by name) */
        @JsonProperty("dataset_name")
        @Nullable
        private String datasetName;

        /** Dataset ID (for loading by ID) */
        @JsonProperty("dataset_id")
        @Nullable
        private String datasetId;

        /** Optional BTQL filter (can be string or structured query object) */
        @JsonProperty("_internal_btql")
        @Nullable
        private Object btql;
    }

    /** Individual evaluation case data */
    @Data
    public static class EvalCaseData {
        /** Input for the task */
        private Object input;

        /** Expected output (optional) */
        @Nullable private Object expected;

        /** Metadata (optional) */
        @Nullable private Map<String, Object> metadata;

        /** Tags (optional) */
        @Nullable private List<String> tags;
    }

    /** Remote scorer specification */
    @Data
    public static class RemoteScorer {
        /** Scorer name */
        private String name;

        /** Function ID specification */
        @JsonProperty("function_id")
        private FunctionId functionId;
    }

    /** Function ID specification (multiple formats supported) */
    @Data
    public static class FunctionId {
        @JsonProperty("function_id")
        @Nullable
        private String functionId;

        @Nullable private String version;
        @Nullable private String name;

        @JsonProperty("prompt_session_id")
        @Nullable
        private String promptSessionId;

        @JsonProperty("inline_code")
        @Nullable
        private String inlineCode;

        @JsonProperty("global_function")
        @Nullable
        private String globalFunction;
    }
}
