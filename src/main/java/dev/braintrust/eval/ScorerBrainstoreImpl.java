package dev.braintrust.eval;

import dev.braintrust.api.BraintrustApiClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A scorer that invokes a remote Braintrust function to compute scores.
 *
 * <p>This implementation fetches a scorer function from Braintrust and invokes it via the API for
 * each task result. The remote function receives the input, output, expected, and metadata as
 * arguments.
 */
public class ScorerBrainstoreImpl<INPUT, OUTPUT> implements Scorer<INPUT, OUTPUT> {
    private final BraintrustApiClient apiClient;
    private final String functionId;
    private final String scorerName;
    private final @Nullable String version;

    /**
     * Create a new remote scorer.
     *
     * @param apiClient the API client to use for invoking the function
     * @param functionId the ID of the function to invoke
     * @param scorerName the name of the scorer (used as default score name)
     * @param version optional version of the function to invoke. null always invokes latest
     *     version.
     */
    public ScorerBrainstoreImpl(
            BraintrustApiClient apiClient,
            String functionId,
            String scorerName,
            @Nullable String version) {
        this.apiClient = apiClient;
        this.functionId = functionId;
        this.scorerName = scorerName;
        this.version = version;
    }

    @Override
    public String getName() {
        return scorerName;
    }

    @Override
    public List<Score> score(TaskResult<INPUT, OUTPUT> taskResult) {
        var request =
                BraintrustApiClient.FunctionInvokeRequest.forScorer(
                        taskResult.datasetCase().input(),
                        taskResult.result(),
                        taskResult.datasetCase().expected(),
                        taskResult.datasetCase().metadata(),
                        version);

        Object result = apiClient.invokeFunction(functionId, request);
        return parseScoreResult(result);
    }

    /**
     * Parse the result from the function invocation into a list of scores.
     *
     * <p>Handles various response formats:
     *
     * <ul>
     *   <li>A single number (0.0-1.0) - converted to a Score with the scorer's name
     *   <li>A Score object: {"score": 0.8, "name": "score_name", "metadata": {...}}
     *   <li>An LLM judge response: {"name": "judge-name", "score": null, "metadata": {"choice":
     *       "0.9", ...}}
     *   <li>A list of Score objects
     *   <li>null - returns empty list (score skipped)
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private List<Score> parseScoreResult(Object result) {
        if (result == null) {
            // Scorer returned null to skip scoring
            return List.of();
        }

        // Handle a single number
        if (result instanceof Number number) {
            return List.of(new Score(scorerName, number.doubleValue()));
        }

        // Handle a list of scores
        if (result instanceof List<?> list) {
            List<Score> scores = new ArrayList<>();
            for (Object item : list) {
                scores.addAll(parseScoreResult(item));
            }
            return scores;
        }

        // Handle a score object (Map)
        if (result instanceof Map<?, ?> map) {
            Map<String, Object> scoreMap = (Map<String, Object>) map;

            // Extract name (use scorer name as fallback)
            String name = scorerName;
            Object nameValue = scoreMap.get("name");
            if (nameValue instanceof String s) {
                name = s;
            }

            // Extract score value
            Object scoreValue = scoreMap.get("score");

            // If score is null, check for LLM judge response with metadata.choice
            if (scoreValue == null) {
                Object metadataObj = scoreMap.get("metadata");
                if (metadataObj instanceof Map<?, ?> metadata) {
                    Object choiceValue = ((Map<String, Object>) metadata).get("choice");
                    if (choiceValue != null) {
                        double score = parseNumericValue(choiceValue);
                        return List.of(new Score(name, score));
                    }
                }
                // No score field and no choice in metadata - skip
                return List.of();
            }

            double score = parseNumericValue(scoreValue);
            return List.of(new Score(name, score));
        }

        throw new IllegalArgumentException(
                "Unexpected score result type: "
                        + result.getClass()
                        + ". Expected Number, Map, or List.");
    }

    /**
     * Parse a numeric value from either a Number or a String.
     *
     * @param value the value to parse
     * @return the double value
     * @throws IllegalArgumentException if the value cannot be parsed as a number
     */
    private double parseNumericValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String str) {
            try {
                return Double.parseDouble(str);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Cannot parse score value as number: '" + str + "'", e);
            }
        }
        throw new IllegalArgumentException(
                "Score value must be a number or numeric string, got: " + value.getClass());
    }
}
