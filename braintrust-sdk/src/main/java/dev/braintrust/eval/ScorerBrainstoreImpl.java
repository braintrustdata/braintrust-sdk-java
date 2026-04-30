package dev.braintrust.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.BraintrustUtils;
import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.api.BraintrustOpenApiClient;
import dev.braintrust.openapi.JSON;
import dev.braintrust.openapi.api.FunctionsApi;
import dev.braintrust.openapi.api.ProjectsApi;
import dev.braintrust.openapi.model.Function;
import dev.braintrust.openapi.model.InvokeApi;
import dev.braintrust.openapi.model.InvokeParent;
import dev.braintrust.openapi.model.SpanParentStruct;
import dev.braintrust.trace.BraintrustTracing;
import dev.braintrust.trace.SpanComponents;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private static final ObjectMapper MAPPER = new JSON().getMapper();

    private final BraintrustOpenApiClient apiClient;
    private final String functionId;
    private final @Nullable String version;
    private final AtomicReference<Function> braintrustFunction = new AtomicReference<>(null);
    private final AtomicReference<String> scorerName = new AtomicReference<>(null);

    @Deprecated
    public ScorerBrainstoreImpl(
            BraintrustApiClient apiClient, String functionId, @Nullable String version) {
        this(apiClient.openApiClient(), functionId, version);
    }

    /**
     * Create a new remote scorer.
     *
     * @param apiClient the API client to use for invoking the function
     * @param functionId braintrust function id
     * @param version optional version of the function to invoke. null always invokes latest
     *     version.
     */
    public ScorerBrainstoreImpl(
            BraintrustOpenApiClient apiClient, String functionId, @Nullable String version) {
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
        var scorerArgs = new java.util.LinkedHashMap<String, Object>();
        scorerArgs.put("input", taskResult.datasetCase().input());
        scorerArgs.put("output", taskResult.result());
        scorerArgs.put("expected", taskResult.datasetCase().expected());
        scorerArgs.put("metadata", taskResult.datasetCase().metadata());
        if (!taskResult.parameters().isEmpty()) {
            scorerArgs.put("parameters", taskResult.parameters().getMerged());
        }

        var invoke = new InvokeApi().input(scorerArgs).version(version);

        InvokeParent parent = buildParent();
        if (parent != null) {
            invoke.parent(parent);
        }

        Object result =
                new FunctionsApi(apiClient)
                        .postFunctionIdInvoke(UUID.fromString(getFunctionId()), invoke);
        return parseScoreResult(result);
    }

    private String getFunctionName() {
        ensureFunctionInfoCached();
        return braintrustFunction.get().getName();
    }

    private String getFunctionId() {
        ensureFunctionInfoCached();
        return braintrustFunction.get().getId().toString();
    }

    private void ensureFunctionInfoCached() {
        if (scorerName.get() == null || braintrustFunction.get() == null) {
            var functionsApi = new FunctionsApi(apiClient);
            var function = functionsApi.getFunctionId(UUID.fromString(functionId), version, null);

            var projectsApi = new ProjectsApi(apiClient);
            var project = projectsApi.getProjectId(function.getProjectId());

            var functionVersion = this.version == null ? "latest" : this.version;
            braintrustFunction.compareAndExchange(null, function);
            scorerName.compareAndExchange(
                    null,
                    "invoke-%s-%s-%s"
                            .formatted(project.getName(), function.getSlug(), functionVersion));
        }
    }

    /**
     * Builds an {@link InvokeParent} for distributed tracing, or null if no tracing context is
     * available.
     */
    @Nullable
    private InvokeParent buildParent() {
        try {
            SpanContext spanContext = Span.current().getSpanContext();
            if (!spanContext.isValid()) return null;

            String parentValue = Baggage.current().getEntryValue(BraintrustTracing.PARENT_KEY);
            if (parentValue == null || parentValue.isEmpty()) return null;

            var rowIds =
                    new SpanComponents.RowIds(spanContext.getSpanId(), spanContext.getTraceId());
            Map<String, Object> spanMap =
                    new SpanComponents(BraintrustUtils.parseParent(parentValue), rowIds).toMap();

            SpanParentStruct struct = MAPPER.convertValue(spanMap, SpanParentStruct.class);
            return new InvokeParent(InvokeParent.SchemaType.SpanParentStruct, struct);
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
        if (result == null) return List.of();

        if (result instanceof Number number) {
            return List.of(new Score(getFunctionName(), number.doubleValue()));
        }

        if (result instanceof List<?> list) {
            List<Score> scores = new ArrayList<>();
            for (Object item : list) {
                scores.addAll(parseScoreResult(item));
            }
            return scores;
        }

        if (result instanceof Map<?, ?> map) {
            Map<String, Object> scoreMap = (Map<String, Object>) map;

            String name = getFunctionName();
            Object nameValue = scoreMap.get("name");
            if (nameValue instanceof String s) name = s;

            Object scoreValue = scoreMap.get("score");

            if (scoreValue == null) {
                Object metadataObj = scoreMap.get("metadata");
                if (metadataObj instanceof Map<?, ?> metadata) {
                    Object choiceValue = ((Map<String, Object>) metadata).get("choice");
                    if (choiceValue != null) {
                        return List.of(new Score(name, parseNumericValue(choiceValue)));
                    }
                }
                return List.of();
            }

            return List.of(new Score(name, parseNumericValue(scoreValue)));
        }

        throw new IllegalArgumentException(
                "Unexpected score result type: "
                        + result.getClass()
                        + ". Expected Number, Map, or List.");
    }

    private double parseNumericValue(Object value) {
        if (value instanceof Number number) return number.doubleValue();
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
