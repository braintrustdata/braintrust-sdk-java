package dev.braintrust.eval;

import static dev.braintrust.json.BraintrustJsonMapper.toJson;

import dev.braintrust.BraintrustUtils;
import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.trace.BraintrustContext;
import dev.braintrust.trace.BraintrustTracing;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.util.*;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * An evaluation framework for testing AI models.
 *
 * @param <INPUT> The type of input data for the evaluation
 * @param <OUTPUT> The type of output produced by the task
 */
@Slf4j
public final class Eval<INPUT, OUTPUT> {
    private static final AttributeKey<String> PARENT =
            AttributeKey.stringKey(BraintrustTracing.PARENT_KEY);
    private final @Nonnull String experimentName;
    private final @Nonnull BraintrustConfig config;
    private final @Nonnull BraintrustApiClient client;
    private final @Nonnull BraintrustApiClient.OrganizationAndProjectInfo orgAndProject;
    private final @Nonnull Tracer tracer;
    private final @Nonnull Dataset<INPUT, OUTPUT> dataset;
    private final @Nonnull Task<INPUT, OUTPUT> task;
    private final @Nonnull List<Scorer<INPUT, OUTPUT>> scorers;
    private final @Nonnull List<String> tags;
    private final @Nonnull Map<String, Object> metadata;

    private Eval(Builder<INPUT, OUTPUT> builder) {
        this.experimentName = builder.experimentName;
        this.config = Objects.requireNonNull(builder.config);
        this.client = Objects.requireNonNull(builder.apiClient);
        if (null == builder.projectId) {
            this.orgAndProject = client.getProjectAndOrgInfo().orElseThrow();
        } else {
            this.orgAndProject =
                    client.getProjectAndOrgInfo(builder.projectId)
                            .orElseThrow(
                                    () ->
                                            new RuntimeException(
                                                    "invalid project id: " + builder.projectId));
        }
        this.tracer = Objects.requireNonNull(builder.tracer);
        this.dataset = builder.dataset;
        this.task = Objects.requireNonNull(builder.task);
        this.scorers = List.copyOf(builder.scorers);
        this.tags = List.copyOf(builder.tags);
        this.metadata = Map.copyOf(builder.metadata);
    }

    /** Runs the evaluation and returns results. */
    public EvalResult run() {
        try (var cursor = dataset.openCursor()) {
            Optional<String> datasetVersion = Optional.empty();
            Optional<String> datasetId = Optional.empty();
            if (dataset instanceof DatasetBrainstoreImpl<INPUT, OUTPUT>) {
                // TODO: come up with a means of expressing this naturally in the dataset api
                datasetVersion = cursor.version();
                datasetId = Optional.of(dataset.id());
            }
            var experiment =
                    client.getOrCreateExperiment(
                            new BraintrustApiClient.CreateExperimentRequest(
                                    orgAndProject.project().id(),
                                    experimentName,
                                    Optional.empty(),
                                    Optional.empty(),
                                    tags.isEmpty() ? Optional.empty() : Optional.of(tags),
                                    metadata.isEmpty() ? Optional.empty() : Optional.of(metadata),
                                    datasetId,
                                    datasetVersion));
            cursor.forEach(datasetCase -> evalOne(experiment.id(), datasetCase));
        }

        var experimentUrl =
                "%s/experiments/%s"
                        .formatted(
                                BraintrustUtils.createProjectURI(config.appUrl(), orgAndProject)
                                        .toASCIIString(),
                                experimentName);
        return new EvalResult(experimentUrl);
    }

    private void evalOne(String experimentId, DatasetCase<INPUT, OUTPUT> datasetCase) {
        var rootSpan =
                tracer.spanBuilder("eval") // TODO: allow names for eval cases
                        .setNoParent() // each eval case is its own trace
                        .setSpanKind(SpanKind.CLIENT)
                        .setAttribute(PARENT, "experiment_id:" + experimentId)
                        .setAttribute("braintrust.span_attributes", toJson(Map.of("type", "eval")))
                        .setAttribute(
                                "braintrust.input_json",
                                toJson(Map.of("input", datasetCase.input())))
                        .setAttribute("braintrust.expected", toJson(datasetCase.expected()))
                        .startSpan();
        if (datasetCase.origin().isPresent()) {
            rootSpan.setAttribute("braintrust.origin", toJson(datasetCase.origin().get()));
        }
        if (!datasetCase.tags().isEmpty()) {
            rootSpan.setAttribute(
                    AttributeKey.stringArrayKey("braintrust.tags"), datasetCase.tags());
        }
        if (!datasetCase.metadata().isEmpty()) {
            rootSpan.setAttribute(
                    AttributeKey.stringKey("braintrust.metadata"), toJson(datasetCase.metadata()));
        }
        try (var rootScope = BraintrustContext.ofExperiment(experimentId, rootSpan).makeCurrent()) {
            final TaskResult<INPUT, OUTPUT> taskResult;
            { // run task
                var taskSpan =
                        tracer.spanBuilder("task")
                                .setAttribute(PARENT, "experiment_id:" + experimentId)
                                .setAttribute(
                                        "braintrust.span_attributes",
                                        toJson(Map.of("type", "task")))
                                .startSpan();
                try (var unused =
                        BraintrustContext.ofExperiment(experimentId, taskSpan).makeCurrent()) {
                    taskResult = task.apply(datasetCase);
                    rootSpan.setAttribute(
                            "braintrust.output_json",
                            toJson(Map.of("output", taskResult.result())));
                } catch (Exception e) {
                    taskSpan.setStatus(StatusCode.ERROR, e.getMessage());
                    taskSpan.recordException(e);
                    taskSpan.end();
                    rootSpan.setStatus(StatusCode.ERROR, e.getMessage());
                    rootSpan.setAttribute(
                            "braintrust.output_json",
                            toJson(Collections.singletonMap("output", null)));
                    log.debug("Task threw exception for input: " + datasetCase.input(), e);
                    // run scoreForTaskException on each scorer
                    for (var scorer : scorers) {
                        runScoreForTaskException(experimentId, rootSpan, scorer, e, datasetCase);
                    }
                    return;
                }
                taskSpan.end();
            }
            // run scorers - one span per scorer
            for (var scorer : scorers) {
                runScorer(experimentId, rootSpan, scorer, taskResult);
            }
        } finally {
            rootSpan.end();
        }
    }

    /**
     * Runs a scorer against a successful task result. If the scorer throws, falls back to {@link
     * Scorer#scoreForScorerException}.
     */
    private void runScorer(
            String experimentId,
            Span rootSpan,
            Scorer<INPUT, OUTPUT> scorer,
            TaskResult<INPUT, OUTPUT> taskResult) {
        var scoreSpan =
                tracer.spanBuilder("score")
                        .setAttribute(PARENT, "experiment_id:" + experimentId)
                        .startSpan();
        try (var unused = BraintrustContext.ofExperiment(experimentId, scoreSpan).makeCurrent()) {
            List<Score> scores;
            try {
                scores = scorer.score(taskResult);
            } catch (Exception e) {
                scoreSpan.setStatus(StatusCode.ERROR, e.getMessage());
                scoreSpan.recordException(e);
                log.debug("Scorer '{}' threw exception", scorer.getName(), e);
                // fall back to scoreForScorerException — if this throws, eval aborts
                scores = scorer.scoreForScorerException(e, taskResult);
            }
            recordScores(scoreSpan, rootSpan, scorer, scores);
        } finally {
            scoreSpan.end();
        }
    }

    /**
     * Runs {@link Scorer#scoreForTaskException} when the task threw. If the fallback throws, the
     * eval aborts.
     */
    private void runScoreForTaskException(
            String experimentId,
            Span rootSpan,
            Scorer<INPUT, OUTPUT> scorer,
            Exception taskException,
            DatasetCase<INPUT, OUTPUT> datasetCase) {
        var scoreSpan =
                tracer.spanBuilder("score")
                        .setAttribute(PARENT, "experiment_id:" + experimentId)
                        .startSpan();
        try (var unused = BraintrustContext.ofExperiment(experimentId, scoreSpan).makeCurrent()) {
            // if this throws, it propagates and the eval aborts
            var scores = scorer.scoreForTaskException(taskException, datasetCase);
            recordScores(scoreSpan, rootSpan, scorer, scores);
        } finally {
            scoreSpan.end();
        }
    }

    /** Validates and records scores on the score span and root span. */
    private void recordScores(
            Span scoreSpan, Span rootSpan, Scorer<INPUT, OUTPUT> scorer, List<Score> scores) {
        if (scores == null || scores.isEmpty()) {
            return;
        }
        final Map<String, Double> scorerScores = new LinkedHashMap<>();
        for (var score : scores) {
            if (score.value() < 0.0 || score.value() > 1.0) {
                throw new RuntimeException(
                        "score must be between 0 and 1: %s : %s"
                                .formatted(scorer.getName(), score));
            }
            scorerScores.put(score.name(), score.value());
        }
        Map<String, Object> spanAttrs = new LinkedHashMap<>();
        spanAttrs.put("type", "score");
        spanAttrs.put("name", scorer.getName());
        scoreSpan.setAttribute("braintrust.span_attributes", toJson(spanAttrs));
        var scoresJson = toJson(scorerScores);
        scoreSpan.setAttribute("braintrust.output_json", scoresJson);
        scoreSpan.setAttribute("braintrust.scores", scoresJson);
    }

    /** Creates a new eval builder. */
    public static <INPUT, OUTPUT> Builder<INPUT, OUTPUT> builder() {
        return new Builder<>();
    }

    /** Builder for creating evaluations with fluent API. */
    public static final class Builder<INPUT, OUTPUT> {
        public @Nonnull Dataset<INPUT, OUTPUT> dataset;
        private @Nonnull String experimentName = "unnamed-java-eval";
        private @Nullable BraintrustConfig config;
        private @Nullable BraintrustApiClient apiClient;
        private @Nullable String projectId;
        private @Nullable Tracer tracer = null;
        private @Nullable Task<INPUT, OUTPUT> task;
        private @Nonnull List<Scorer<INPUT, OUTPUT>> scorers = List.of();
        private @Nonnull List<String> tags = List.of();
        private @Nonnull Map<String, Object> metadata = Map.of();

        public Eval<INPUT, OUTPUT> build() {
            if (config == null) {
                config = BraintrustConfig.fromEnvironment();
            }
            if (tracer == null) {
                tracer = BraintrustTracing.getTracer();
            }
            if (projectId == null) {
                projectId = config.defaultProjectId().orElse(null);
            }
            if (scorers.isEmpty()) {
                throw new RuntimeException("must provide at least one scorer");
            }
            if (null == apiClient) {
                apiClient = BraintrustApiClient.of(config);
            }
            Objects.requireNonNull(dataset);
            Objects.requireNonNull(task);
            return new Eval<>(this);
        }

        public Builder<INPUT, OUTPUT> name(@Nonnull String name) {
            this.experimentName = Objects.requireNonNull(name);
            return this;
        }

        public Builder<INPUT, OUTPUT> projectId(@Nonnull String projectId) {
            this.projectId = Objects.requireNonNull(projectId);
            return this;
        }

        public Builder<INPUT, OUTPUT> config(BraintrustConfig config) {
            this.config = config;
            return this;
        }

        public Builder<INPUT, OUTPUT> apiClient(BraintrustApiClient apiClient) {
            this.apiClient = apiClient;
            return this;
        }

        public Builder<INPUT, OUTPUT> tracer(Tracer tracer) {
            this.tracer = tracer;
            return this;
        }

        public Builder<INPUT, OUTPUT> dataset(Dataset<INPUT, OUTPUT> dataset) {
            this.dataset = dataset;
            return this;
        }

        /** Deprecated. Use {@link #cases(DatasetCase[])} or {@link #dataset(Dataset)} instead */
        @Deprecated
        @SafeVarargs
        public final Builder<INPUT, OUTPUT> cases(EvalCase<INPUT, OUTPUT>... cases) {
            return cases(
                    Arrays.stream(cases)
                            .map(evalCase -> DatasetCase.of(evalCase.input(), evalCase.expected()))
                            .toList()
                            .toArray(new DatasetCase[0]));
        }

        @SafeVarargs
        public final Builder<INPUT, OUTPUT> cases(DatasetCase<INPUT, OUTPUT>... cases) {
            if (cases.length == 0) {
                throw new RuntimeException("must provide at least one case");
            }
            return dataset(Dataset.of(cases));
        }

        public Builder<INPUT, OUTPUT> task(Task<INPUT, OUTPUT> task) {
            this.task = task;
            return this;
        }

        public Builder<INPUT, OUTPUT> taskFunction(Function<INPUT, OUTPUT> taskFn) {
            return task(
                    new Task<>() {
                        @Override
                        public TaskResult<INPUT, OUTPUT> apply(
                                DatasetCase<INPUT, OUTPUT> datasetCase) {
                            var result = taskFn.apply(datasetCase.input());
                            return new TaskResult<>(result, datasetCase);
                        }
                    });
        }

        @SafeVarargs
        public final Builder<INPUT, OUTPUT> scorers(Scorer<INPUT, OUTPUT>... scorers) {
            this.scorers = List.of(scorers);
            return this;
        }

        /** Sets tags for the experiment. */
        public Builder<INPUT, OUTPUT> tags(List<String> tags) {
            this.tags = List.copyOf(tags);
            return this;
        }

        /** Sets tags for the experiment (varargs convenience method). */
        public Builder<INPUT, OUTPUT> tags(String... tags) {
            this.tags = List.of(tags);
            return this;
        }

        /** Sets metadata for the experiment. */
        public Builder<INPUT, OUTPUT> metadata(Map<String, Object> metadata) {
            this.metadata = Map.copyOf(metadata);
            return this;
        }
    }
}
