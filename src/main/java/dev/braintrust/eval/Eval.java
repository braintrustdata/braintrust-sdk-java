package dev.braintrust.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.BraintrustUtils;
import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.trace.BraintrustContext;
import dev.braintrust.trace.BraintrustTracing;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import java.util.*;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.SneakyThrows;

/**
 * An evaluation framework for testing AI models.
 *
 * @param <INPUT> The type of input data for the evaluation
 * @param <OUTPUT> The type of output produced by the task
 */
public final class Eval<INPUT, OUTPUT> {
    private static final AttributeKey<String> PARENT =
            AttributeKey.stringKey(BraintrustTracing.PARENT_KEY);
    private static final ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private final @Nonnull String experimentName;
    private final @Nonnull BraintrustConfig config;
    private final @Nonnull BraintrustApiClient client;
    private final @Nonnull BraintrustApiClient.OrganizationAndProjectInfo orgAndProject;
    private final @Nonnull Tracer tracer;
    private final @Nonnull List<EvalCase<INPUT, OUTPUT>> evalCases;
    private final @Nonnull Task<INPUT, OUTPUT> task;
    private final @Nonnull List<Scorer<INPUT, OUTPUT>> scorers;

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
        this.evalCases = List.copyOf(builder.evalCases);
        this.task = Objects.requireNonNull(builder.task);
        this.scorers = List.copyOf(builder.scorers);
    }

    /** Runs the evaluation and returns results. */
    public Result run() {
        var experiment =
                client.getOrCreateExperiment(
                        new BraintrustApiClient.CreateExperimentRequest(
                                orgAndProject.project().id(),
                                experimentName,
                                Optional.empty(),
                                Optional.empty()));
        var experimentID = experiment.id();
        var evalCaseResults =
                evalCases.stream().map(evalCase -> evalOne(experimentID, evalCase)).toList();
        return new Result();
    }

    private EvalCase.Result<INPUT, OUTPUT> evalOne(
            String experimentId, EvalCase<INPUT, OUTPUT> evalCase) {
        var rootSpan =
                tracer.spanBuilder("eval") // TODO: allow names for eval cases
                        .setNoParent() // each eval case is its own trace
                        .setSpanKind(SpanKind.CLIENT)
                        .setAttribute(PARENT, "experiment_id:" + experimentId)
                        .setAttribute("braintrust.span_attributes", "{\"type\":\"eval\"}")
                        // FIXME: use proper object mapper for json stuff
                        .setAttribute(
                                "braintrust.input_json",
                                "{ \"input\":\"" + evalCase.input() + "\"}")
                        .setAttribute("braintrust.expected", "\"" + evalCase.expected() + "\"")
                        .startSpan();
        try (var rootScope = BraintrustContext.ofExperiment(experimentId, rootSpan).makeCurrent()) {
            final OUTPUT result;
            { // run task
                var taskSpan =
                        tracer.spanBuilder("task")
                                .setAttribute(PARENT, "experiment_id:" + experimentId)
                                .setAttribute("braintrust.span_attributes", "{\"type\":\"task\"}")
                                .startSpan();
                try (var unused =
                        BraintrustContext.ofExperiment(experimentId, taskSpan).makeCurrent()) {
                    result = task.apply(evalCase);
                } finally {
                    taskSpan.end();
                }
                try {
                    rootSpan.setAttribute(
                            "braintrust.output_json",
                            JSON_MAPPER.writeValueAsString(
                                    Map.of("output", String.valueOf(result))));
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            }
            { // run scorers
                var scoreSpan =
                        tracer.spanBuilder("score")
                                .setAttribute(PARENT, "experiment_id:" + experimentId)
                                .setAttribute("braintrust.span_attributes", "{\"type\":\"score\"}")
                                .startSpan();
                try (var unused =
                        BraintrustContext.ofExperiment(experimentId, scoreSpan).makeCurrent()) {
                    // NOTE: linked hash map to preserve ordering. Not in the spec but nice user
                    // experience
                    final HashMap<String, Double> nameToScore = new LinkedHashMap<>();
                    var scores =
                            scorers.stream()
                                    .map(
                                            scorer -> {
                                                var score = scorer.score(evalCase, result);
                                                if (score < 0.0 || score > 1.0) {
                                                    throw new RuntimeException(
                                                            "score must be between 0 and 1: "
                                                                    + scorer.getName()
                                                                    + " : "
                                                                    + score);
                                                }
                                                nameToScore.put(scorer.getName(), score);
                                                return score;
                                            })
                                    .toList();
                    try {
                        scoreSpan.setAttribute(
                                "braintrust.scores", JSON_MAPPER.writeValueAsString(nameToScore));
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                } finally {
                    scoreSpan.end();
                }
            }
            return new EvalCase.Result<>(evalCase, result);
        } finally {
            rootSpan.end();
        }
    }

    /** Results of all eval cases of an experiment. */
    public class Result {
        @Getter private final String experimentUrl;

        @SneakyThrows
        private Result() {
            this.experimentUrl =
                    "%s/experiments/%s"
                            .formatted(
                                    BraintrustUtils.createProjectURI(config.appUrl(), orgAndProject)
                                            .toASCIIString(),
                                    experimentName);
        }

        public String createReportString() {
            try {
                return "Experiment complete. View results in braintrust: " + experimentUrl;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** Creates a new eval builder. */
    public static <INPUT, OUTPUT> Builder<INPUT, OUTPUT> builder() {
        return new Builder<>();
    }

    /** Builder for creating evaluations with fluent API. */
    public static final class Builder<INPUT, OUTPUT> {
        private @Nonnull String experimentName = "unnamed-java-eval";
        private @Nullable BraintrustConfig config;
        private @Nullable BraintrustApiClient apiClient;
        private @Nullable String projectId;
        private @Nullable Tracer tracer = null;
        private @Nonnull List<EvalCase<INPUT, OUTPUT>> evalCases = List.of();
        private @Nullable Task<INPUT, OUTPUT> task;
        private @Nonnull List<Scorer<INPUT, OUTPUT>> scorers = List.of();

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
            if (evalCases.isEmpty()) {
                throw new RuntimeException("must provide at least one eval case");
            }
            if (scorers.isEmpty()) {
                throw new RuntimeException("must provide at least one scorer");
            }
            if (null == apiClient) {
                apiClient = BraintrustApiClient.of(config);
            }
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

        @SafeVarargs
        public final Builder<INPUT, OUTPUT> cases(EvalCase<INPUT, OUTPUT>... cases) {
            this.evalCases = List.of(cases);
            return this;
        }

        public Builder<INPUT, OUTPUT> task(Task<INPUT, OUTPUT> task) {
            this.task = task;
            return this;
        }

        public Builder<INPUT, OUTPUT> taskFunction(Function<INPUT, OUTPUT> taskFn) {
            return task(evalCase -> taskFn.apply(evalCase.input()));
        }

        @SafeVarargs
        public final Builder<INPUT, OUTPUT> scorers(Scorer<INPUT, OUTPUT>... scorers) {
            this.scorers = List.of(scorers);
            return this;
        }
    }
}
