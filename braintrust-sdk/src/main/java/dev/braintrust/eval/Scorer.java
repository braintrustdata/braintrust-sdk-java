package dev.braintrust.eval;

import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.api.BraintrustOpenApiClient;
import dev.braintrust.openapi.api.FunctionsApi;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A scorer evaluates the result of a task against a dataset case, producing a score between 0
 * (inclusive) and 1 (inclusive).
 *
 * <p>Implementations must be thread safe.
 *
 * @param <INPUT> type of the input data
 * @param <OUTPUT> type of the output data
 */
@ThreadSafe
public interface Scorer<INPUT, OUTPUT> {
    String getName();

    /**
     * Scores the result of a successful task execution.
     *
     * @param taskResult the task output and originating dataset case
     * @return one or more scores, each with a value between 0 and 1 inclusive
     *     <p>If this method thows, the error will be recorded on the span and {@link
     *     #scoreForScorerException} will be called as a fallback
     */
    List<Score> score(TaskResult<INPUT, OUTPUT> taskResult);

    /**
     * Provides fallback scores when the task function threw an exception. Called instead of {@link
     * #score} for each scorer.
     *
     * @param taskException the exception thrown by the task
     * @param datasetCase the dataset case that was being evaluated
     * @return fallback scores, or an empty list to skip scoring for this case
     */
    default List<Score> scoreForTaskException(
            Exception taskException, DatasetCase<INPUT, OUTPUT> datasetCase) {
        return List.of(new Score(getName(), 0.0));
    }

    /**
     * Provides fallback scores when this scorer's {@link #score} method threw an exception.
     *
     * @param scorerException the exception thrown by {@link #score}
     * @param taskResult the task result that was being scored
     * @return fallback scores, or an empty list to skip scoring for this case
     */
    default List<Score> scoreForScorerException(
            Exception scorerException, TaskResult<INPUT, OUTPUT> taskResult) {
        return List.of(new Score(getName(), 0.0));
    }

    static <INPUT, OUTPUT> Scorer<INPUT, OUTPUT> of(
            String scorerName, Function<TaskResult<INPUT, OUTPUT>, Double> scorerFn) {
        return new Scorer<>() {
            @Override
            public String getName() {
                return scorerName;
            }

            @Override
            public List<Score> score(TaskResult<INPUT, OUTPUT> taskResult) {
                return List.of(new Score(scorerName, scorerFn.apply(taskResult)));
            }
        };
    }

    static <INPUT, OUTPUT> Scorer<INPUT, OUTPUT> of(
            String scorerName, BiFunction<OUTPUT, OUTPUT, Double> scorerFn) {
        return new Scorer<>() {
            @Override
            public String getName() {
                return scorerName;
            }

            @Override
            public List<Score> score(TaskResult<INPUT, OUTPUT> taskResult) {
                return List.of(
                        new Score(
                                scorerName,
                                scorerFn.apply(
                                        taskResult.datasetCase().expected(), taskResult.result())));
            }
        };
    }

    @Deprecated
    static <INPUT, OUTPUT> Scorer<INPUT, OUTPUT> fetchFromBraintrust(
            BraintrustApiClient apiClient,
            String projectName,
            String scorerSlug,
            @Nullable String version) {
        return fetchFromBraintrust(apiClient.openApiClient(), projectName, scorerSlug, version);
    }

    /**
     * Fetch a scorer from Braintrust by project name and slug.
     *
     * @param apiClient the API client to use
     * @param projectName the name of the project containing the scorer
     * @param scorerSlug the unique slug identifier for the scorer
     * @param version optional version of the scorer to fetch
     * @return a Scorer that invokes the remote function
     * @throws RuntimeException if the scorer is not found
     */
    static <INPUT, OUTPUT> Scorer<INPUT, OUTPUT> fetchFromBraintrust(
            BraintrustOpenApiClient apiClient,
            String projectName,
            String scorerSlug,
            @Nullable String version) {
        return new ScorerBrainstoreImpl<>(
                apiClient, resolveFunctionId(apiClient, projectName, scorerSlug, version), version);
    }

    /**
     * Fetch a scorer from Braintrust by project name and slug, with custom converters that
     * transform the {@code input}, {@code output}, and {@code expected} scorer argument values into
     * a JSON-serializable form.
     *
     * <p>By default ({@link #fetchFromBraintrust(BraintrustOpenApiClient, String, String,
     * String)}), argument values are serialized with the SDK's shared mapper ({@link
     * dev.braintrust.json.BraintrustJsonMapper}). Use this variant to control serialization, e.g.
     * with a custom {@code ObjectMapper}:
     *
     * <pre>{@code
     * Scorer<MyInput, MyOutput> scorer = Scorer.fetchFromBraintrust(
     *         client, project, slug, null, myMapper::valueToTree, myMapper::valueToTree);
     * }</pre>
     *
     * <p>Case {@code metadata} and eval {@code parameters} are always serialized with the OpenAPI
     * client's mapper.
     *
     * @param apiClient the API client to use
     * @param projectName the name of the project containing the scorer
     * @param scorerSlug the unique slug identifier for the scorer
     * @param version optional version of the scorer to fetch
     * @param inputConverter converts each case's {@code input} value into a JSON-serializable form
     *     (e.g. a Jackson {@code JsonNode}, {@code Map}, or scalar); never invoked with null
     * @param outputConverter converts the task {@code output} and case {@code expected} values into
     *     a JSON-serializable form; never invoked with null
     * @return a Scorer that invokes the remote function
     * @throws RuntimeException if the scorer is not found
     */
    static <INPUT, OUTPUT> Scorer<INPUT, OUTPUT> fetchFromBraintrust(
            BraintrustOpenApiClient apiClient,
            String projectName,
            String scorerSlug,
            @Nullable String version,
            Function<INPUT, Object> inputConverter,
            Function<OUTPUT, Object> outputConverter) {
        return new ScorerBrainstoreImpl<>(
                apiClient,
                resolveFunctionId(apiClient, projectName, scorerSlug, version),
                version,
                inputConverter,
                outputConverter);
    }

    private static String resolveFunctionId(
            BraintrustOpenApiClient apiClient,
            String projectName,
            String scorerSlug,
            @Nullable String version) {
        var functionsApi = new FunctionsApi(apiClient);
        var objects =
                functionsApi
                        .getFunction(
                                null,
                                null,
                                null,
                                null,
                                null,
                                projectName,
                                null,
                                scorerSlug,
                                version,
                                null,
                                null)
                        .getObjects();

        if (objects.isEmpty()) {
            throw new RuntimeException(
                    "Scorer not found: project=" + projectName + ", slug=" + scorerSlug);
        }

        return objects.get(0).getId().toString();
    }
}
