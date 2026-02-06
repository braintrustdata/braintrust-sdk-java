package dev.braintrust.eval;

import dev.braintrust.BraintrustUtils;
import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.trace.BraintrustTracing;
import dev.braintrust.trace.SpanComponents;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * A scorer that invokes a remote Braintrust function to compute scores.
 *
 * <p>This implementation fetches a scorer function from Braintrust and invokes it via the API for
 * each task result. The remote function receives the input, output, expected, and metadata as
 * arguments.
 *
 * <p>Supports distributed tracing: if an object ID is available in OTEL baggage and a valid span
 * context exists, the scorer will pass parent span information to the remote function, enabling the
 * remote function's spans to appear as children of the local scorer span.
 */
@Slf4j
public class ScorerBrainstoreImpl<INPUT, OUTPUT> implements Scorer<INPUT, OUTPUT> {
    private final BraintrustApiClient apiClient;
    private final String functionId;
    private final @Nullable String version;
    private final AtomicReference<BraintrustApiClient.Function> braintrustFunction =
            new AtomicReference<>(null);
    private final AtomicReference<String> scorerName = new AtomicReference<>(null);

    /**
     * Create a new remote scorer.
     *
     * @param apiClient the API client to use for invoking the function
     * @param functionId braintrust function id
     * @param version optional version of the function to invoke. null always invokes latest
     *     version.
     */
    public ScorerBrainstoreImpl(
            BraintrustApiClient apiClient, String functionId, @Nullable String version) {
        this.apiClient = apiClient;
        this.functionId = functionId;
        this.version = version;
    }

    @Override
    public String getName() {
        ensureFunctionInfoCached();
        return scorerName.get();
    }

    @Override
    public List<Score> score(TaskResult<INPUT, OUTPUT> taskResult) {
        // Build parent span components for distributed tracing (as object, not base64 string)
        Object parent = buildParentSpanComponents();

        var request =
                BraintrustApiClient.FunctionInvokeRequest.of(
                        taskResult.datasetCase().input(),
                        taskResult.result(),
                        taskResult.datasetCase().expected(),
                        taskResult.datasetCase().metadata(),
                        version,
                        parent);

        Object result = apiClient.invokeFunction(getFunctionId(), request);
        return parseScoreResult(result);
    }

    private String getFunctionName() {
        ensureFunctionInfoCached();
        return braintrustFunction.get().name();
    }

    private String getFunctionId() {
        ensureFunctionInfoCached();
        return braintrustFunction.get().id();
    }

    private void ensureFunctionInfoCached() {
        if (scorerName.get() == null || braintrustFunction.get() == null) {
            // we could get multiple threads in here but that's fine (just redundant work)
            var function = apiClient.getFunctionById(functionId).orElseThrow();
            var projectAndOrgInfo =
                    apiClient.getProjectAndOrgInfo(function.projectId()).orElseThrow();
            var functionVersion = this.version == null ? "latest" : this.version;
            braintrustFunction.compareAndExchange(null, function);
            scorerName.compareAndExchange(
                    null,
                    "invoke-%s-%s-%s"
                            .formatted(
                                    projectAndOrgInfo.project().name(),
                                    function.slug(),
                                    functionVersion));
        }
    }

    /**
     * Builds the parent span components for distributed tracing.
     *
     * <p>Extracts the experiment ID from OTEL baggage and span/trace IDs from the current span
     * context. Returns the parent as a Map that can be serialized directly (the API accepts both
     * base64-encoded strings and object format).
     *
     * @return parent object for distributed tracing, or null if tracing context not available
     */
    @Nullable
    private Map<String, Object> buildParentSpanComponents() {
        try {
            // Get current span context
            SpanContext spanContext = Span.current().getSpanContext();
            if (!spanContext.isValid()) {
                return null;
            }

            // Get experiment ID from baggage (format: "experiment_id:abc123")
            String parentValue = Baggage.current().getEntryValue(BraintrustTracing.PARENT_KEY);
            if (parentValue == null || parentValue.isEmpty()) {
                return null;
            }

            var rowIds =
                    new SpanComponents.RowIds(spanContext.getSpanId(), spanContext.getTraceId());
            return new SpanComponents(BraintrustUtils.parseParent(parentValue), rowIds).toMap();
        } catch (Exception e) {
            log.warn("Failed to build parent span components: {}", e.getMessage(), e);
            return null;
        }
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
            return List.of(new Score(getFunctionName(), number.doubleValue()));
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
            String name = getFunctionName();
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
