package dev.braintrust.devserver;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.Builder;
import lombok.Data;

/** Response body for POST /eval endpoint (non-streaming) */
@Data
@Builder
public class EvalResponse {
    /** Experiment name */
    @JsonProperty("experimentName")
    private String experimentName;

    /** Project name */
    @JsonProperty("projectName")
    private String projectName;

    /** Project ID */
    @JsonProperty("projectId")
    private String projectId;

    /** Experiment ID */
    @JsonProperty("experimentId")
    private String experimentId;

    /** Experiment URL */
    @JsonProperty("experimentUrl")
    private String experimentUrl;

    /** Project URL */
    @JsonProperty("projectUrl")
    private String projectUrl;

    /** Comparison experiment name (optional) */
    @JsonProperty("comparisonExperimentName")
    @Nullable
    private String comparisonExperimentName;

    /** Score summaries by scorer name */
    @JsonProperty("scores")
    private Map<String, ScoreSummary> scores;

    /** Summary statistics for a scorer */
    @Data
    @Builder
    public static class ScoreSummary {
        /** Scorer name */
        private String name;

        /** Average score across all cases */
        private double score;

        /** Number of improvements vs baseline */
        private int improvements;

        /** Number of regressions vs baseline */
        private int regressions;
    }
}
