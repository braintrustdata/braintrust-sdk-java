package dev.braintrust.eval;

import dev.braintrust.api.BraintrustApiClient;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * A scorer evaluates the result of a task against a dataset case, producing a score between 0
 * (inclusive) and 1 (inclusive).
 *
 * @param <INPUT> type of the input data
 * @param <OUTPUT> type of the output data
 */
public interface Scorer<INPUT, OUTPUT> {
    String getName();

    /**
     * Scores the result of a successful task execution.
     *
     * @param taskResult the task output and originating dataset case
     * @return one or more scores, each with a value between 0 and 1 inclusive
     * @throws Exception if scoring fails, the error will be recorded on the span and {@link
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
     * @throws Exception if this method throws, the entire eval run is aborted
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
     * @throws Exception if this method throws, the entire eval run is aborted
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
            BraintrustApiClient apiClient,
            String projectName,
            String scorerSlug,
            @Nullable String version) {
        var function =
                apiClient
                        .getFunction(projectName, scorerSlug, version)
                        .orElseThrow(
                                () ->
                                        new RuntimeException(
                                                "Scorer not found: project="
                                                        + projectName
                                                        + ", slug="
                                                        + scorerSlug));

        return new ScorerBrainstoreImpl<>(apiClient, function.id(), version);
    }
}
