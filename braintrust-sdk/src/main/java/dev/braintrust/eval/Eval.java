package dev.braintrust.eval;

import dev.braintrust.BraintrustUtils;
import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.api.BraintrustOpenApiClient;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.eval.EvalListener.CaseListener;
import dev.braintrust.eval.EvalListener.RunListener;
import dev.braintrust.openapi.model.Project;
import dev.braintrust.trace.BrainstoreTrace;
import dev.braintrust.trace.BraintrustContext;
import dev.braintrust.trace.BraintrustTracing;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
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
    private final @Nonnull String experimentName;
    private final @Nonnull BraintrustConfig config;
    private final @Nonnull BraintrustOpenApiClient client;
    private final @Nonnull Project project;
    private final @Nonnull BraintrustOpenApiClient.OrgInfo orgInfo;
    private final @Nonnull Tracer tracer;
    private final @Nonnull Dataset<INPUT, OUTPUT> dataset;
    private final @Nonnull Task<INPUT, OUTPUT> task;
    private final @Nonnull List<Scorer<INPUT, OUTPUT>> scorers;
    private final @Nonnull List<Classifier<INPUT, OUTPUT>> classifiers;
    private final @Nonnull List<String> tags;
    private final @Nonnull Map<String, Object> metadata;
    private final @Nonnull Parameters parameters;
    private final @Nonnull EvalTargetProvider targetProvider;
    private final @Nonnull List<EvalListener> listeners;

    private Eval(Builder<INPUT, OUTPUT> builder) {
        this.experimentName = builder.experimentName;
        this.config = Objects.requireNonNull(builder.config);
        this.client = Objects.requireNonNull(builder.apiClient);
        this.project =
                client.fetchOrCreateProject(
                        builder.projectId, config.defaultProjectName().orElse(null));
        this.orgInfo = client.fetchOrgInfo(project.getOrgId().toString());
        this.tracer = Objects.requireNonNull(builder.tracer);
        this.dataset = builder.dataset;
        this.task = Objects.requireNonNull(builder.task);
        this.scorers = List.copyOf(builder.scorers);
        this.classifiers = List.copyOf(builder.classifiers);
        this.tags = List.copyOf(builder.tags);
        this.metadata = Map.copyOf(builder.metadata);
        this.parameters = builder.buildParameters();
        this.targetProvider = Objects.requireNonNull(builder.targetProvider);
        this.listeners = List.copyOf(builder.listeners);
    }

    /** Runs the evaluation and returns results. */
    public EvalResult run() {
        final EvalRunInfo runInfo;
        try (var cursor = dataset.openCursor()) {
            Optional<String> datasetVersion = Optional.empty();
            Optional<String> datasetId = Optional.empty();
            if (dataset instanceof DatasetBrainstoreImpl<INPUT, OUTPUT>) {
                datasetVersion = cursor.version();
                datasetId = Optional.of(dataset.id());
            }

            runInfo =
                    targetProvider.create(
                            new EvalTargetProvider.Context(
                                    config,
                                    client,
                                    project,
                                    orgInfo,
                                    experimentName,
                                    tags,
                                    metadata,
                                    datasetId,
                                    datasetVersion));

            var runListeners = new ArrayList<RunListener>(listeners.size());
            for (var listener : listeners) {
                runListeners.add(listener.createRunListener(runInfo));
            }

            runListeners.forEach(RunListener::onRunStart);
            cursor.forEach(datasetCase -> evalOne(runInfo, datasetCase, runListeners));
            runListeners.forEach(RunListener::onRunEnd);
        }

        return new EvalResult(runInfo.experimentUrl());
    }

    /** Makes {@code span} current with the braintrust parent set in baggage for child spans. */
    private Scope makeCurrent(Span span, BraintrustUtils.Parent parent) {
        var ctx = Context.current().with(span);
        ctx = BraintrustContext.setParentInBaggage(ctx, parent.type(), parent.id());
        return ctx.makeCurrent();
    }

    private void evalOne(
            EvalRunInfo runInfo,
            DatasetCase<INPUT, OUTPUT> datasetCase,
            List<RunListener> runListeners) {
        var caseListeners = new ArrayList<CaseListener>(runListeners.size());
        for (var runListener : runListeners) {
            caseListeners.add(runListener.createCaseListener(datasetCase));
        }
        var parent = runInfo.parent();

        // Eval owns the span structure: create the root span (name only), then let listeners
        // decorate it.
        var rootSpan =
                tracer.spanBuilder("eval") // TODO: allow names for eval cases
                        .setNoParent() // each eval case is its own trace
                        .setSpanKind(SpanKind.CLIENT)
                        .startSpan();
        for (var cl : caseListeners) {
            cl.onRootSpan(rootSpan, datasetCase);
        }
        try (var rootScope = makeCurrent(rootSpan, parent)) {
            TaskResult<INPUT, OUTPUT> taskResult = null;
            Exception taskError = null;
            var taskSpan = tracer.spanBuilder("task").startSpan();
            final String taskSpanId = taskSpan.getSpanContext().getSpanId();
            for (var cl : caseListeners) {
                cl.onTaskSpan(taskSpan, datasetCase);
            }
            try (var taskScope = makeCurrent(taskSpan, parent)) {
                taskResult = task.apply(datasetCase, parameters);
                for (var cl : caseListeners) {
                    cl.onTaskSuccess(rootSpan, taskSpan, taskResult);
                }
            } catch (Exception e) {
                taskError = e;
                for (var cl : caseListeners) {
                    cl.onTaskError(rootSpan, taskSpan, datasetCase, e);
                }
            }
            taskSpan.end();

            if (taskError != null) {
                log.debug("Task threw exception for input: " + datasetCase.input(), taskError);
                // run scoreForTaskException on each scorer (score spans nest under the root span,
                // since the task scope is now closed); classifiers are skipped
                for (var scorer : scorers) {
                    runScoreForTaskException(
                            caseListeners, rootSpan, parent, scorer, taskError, datasetCase);
                }
                return;
            }

            // A single BrainstoreTrace for this eval case, shared across all scorers/classifiers.
            // It fetches spans lazily on first access (only if a traced scorer/classifier calls
            // it). Only available when targeting an experiment.
            BrainstoreTrace trace =
                    runInfo.tracingSupported()
                            ? BrainstoreTrace.forExperiment(
                                    client,
                                    Objects.requireNonNull(runInfo.experimentId()),
                                    rootSpan.getSpanContext().getTraceId(),
                                    List.of(taskSpanId))
                            : null;

            // run scorers
            for (var scorer : scorers) {
                runScorer(caseListeners, rootSpan, parent, scorer, taskResult, trace);
            }

            // run classifiers. Classifier exceptions are non-fatal: they are recorded on the
            // classifier span and surfaced in the root span's metadata under `classifier_errors`,
            // but do not abort the eval or affect other classifiers/scorers. Classifiers only run
            // when the task succeeded (no scoreForTaskException analogue).
            for (int i = 0; i < classifiers.size(); i++) {
                runClassifier(
                        caseListeners, rootSpan, parent, classifiers.get(i), i, taskResult, trace);
            }
        } finally {
            for (var cl : caseListeners) {
                cl.onCaseEnd(rootSpan);
            }
            rootSpan.end();
        }
    }

    /**
     * Runs a scorer against a successful task result. If the scorer is a {@link TracedScorer}, it
     * receives the {@link BrainstoreTrace} for the eval case. If the scorer throws, falls back to
     * {@link Scorer#scoreForScorerException}. {@code onScoreResult} is dispatched only when scores
     * are valid; on validation/fallback failure the span is still ended and the eval aborts.
     */
    private void runScorer(
            List<CaseListener> caseListeners,
            Span rootSpan,
            BraintrustUtils.Parent parent,
            Scorer<INPUT, OUTPUT> scorer,
            TaskResult<INPUT, OUTPUT> taskResult,
            @Nullable BrainstoreTrace trace) {
        var scoreSpan = tracer.spanBuilder("score").startSpan();
        for (var cl : caseListeners) {
            cl.onScoreSpan(scoreSpan, scorer);
        }
        RuntimeException pending = null;
        try (var unused = makeCurrent(scoreSpan, parent)) {
            List<Score> scores;
            Exception scoreException = null;
            try {
                if (scorer instanceof TracedScorer<INPUT, OUTPUT> tracedScorer) {
                    scores = tracedScorer.score(taskResult, trace);
                } else {
                    scores = scorer.score(taskResult);
                }
            } catch (Exception e) {
                scoreException = e;
                log.debug("Scorer '{}' threw exception", scorer.getName(), e);
                // fall back to scoreForScorerException — if this throws, eval aborts
                scores = scorer.scoreForScorerException(e, taskResult);
            }
            validateScores(scorer, scores);
            final var finalScores = scores;
            final var finalException = scoreException;
            for (var cl : caseListeners) {
                cl.onScoreResult(scoreSpan, rootSpan, scorer, finalScores, finalException);
            }
        } catch (RuntimeException re) {
            // validation (or a throwing fallback) aborts the eval; record nothing for this score
            pending = re;
        } finally {
            scoreSpan.end();
        }
        if (pending != null) {
            throw pending;
        }
    }

    /**
     * Runs {@link Scorer#scoreForTaskException} when the task threw. If the fallback (or score
     * validation) throws, the eval aborts — but the score span is still ended.
     */
    private void runScoreForTaskException(
            List<CaseListener> caseListeners,
            Span rootSpan,
            BraintrustUtils.Parent parent,
            Scorer<INPUT, OUTPUT> scorer,
            Exception taskException,
            DatasetCase<INPUT, OUTPUT> datasetCase) {
        var scoreSpan = tracer.spanBuilder("score").startSpan();
        for (var cl : caseListeners) {
            cl.onScoreSpan(scoreSpan, scorer);
        }
        RuntimeException pending = null;
        try (var unused = makeCurrent(scoreSpan, parent)) {
            var scores = scorer.scoreForTaskException(taskException, datasetCase);
            validateScores(scorer, scores);
            for (var cl : caseListeners) {
                cl.onScoreResult(scoreSpan, rootSpan, scorer, scores, null);
            }
        } catch (RuntimeException re) {
            pending = re;
        } finally {
            scoreSpan.end();
        }
        if (pending != null) {
            throw pending;
        }
    }

    /**
     * Runs a classifier inside its own span. Exceptions are non-fatal: they are surfaced to
     * listeners via the {@code classifierException} argument of {@code onClassifierResult} and do
     * not propagate.
     */
    private void runClassifier(
            List<CaseListener> caseListeners,
            Span rootSpan,
            BraintrustUtils.Parent parent,
            Classifier<INPUT, OUTPUT> classifier,
            int index,
            TaskResult<INPUT, OUTPUT> taskResult,
            @Nullable BrainstoreTrace trace) {
        var resolvedName = classifier.getName();
        if (resolvedName == null || resolvedName.isBlank()) {
            resolvedName = "classifier_" + index;
        }
        var classifierSpan = tracer.spanBuilder(resolvedName).startSpan();
        for (var cl : caseListeners) {
            cl.onClassifierSpan(classifierSpan, classifier, resolvedName);
        }
        List<Classification> classifications = List.of();
        Exception classifierException = null;
        try (var unused = makeCurrent(classifierSpan, parent)) {
            if (classifier instanceof TracedClassifier<INPUT, OUTPUT> tracedClassifier) {
                classifications = tracedClassifier.classify(taskResult, trace);
            } else {
                classifications = classifier.classify(taskResult);
            }
            if (classifications == null) {
                classifications = List.of();
            }
        } catch (Exception e) {
            classifierException = e;
            classifications = List.of();
            log.debug("Classifier '{}' threw exception", resolvedName, e);
        } finally {
            final var finalClassifications = classifications;
            final var finalException = classifierException;
            final var finalResolvedName = resolvedName;
            for (var cl : caseListeners) {
                cl.onClassifierResult(
                        classifierSpan,
                        rootSpan,
                        classifier,
                        finalResolvedName,
                        finalClassifications,
                        finalException);
            }
            classifierSpan.end();
        }
    }

    /** Validates that every score value is between 0 and 1 inclusive. Throws (aborting) if not. */
    private void validateScores(Scorer<INPUT, OUTPUT> scorer, @Nullable List<Score> scores) {
        if (scores == null) {
            return;
        }
        for (var score : scores) {
            if (score.value() < 0.0 || score.value() > 1.0) {
                throw new RuntimeException(
                        "score must be between 0 and 1: %s : %s"
                                .formatted(scorer.getName(), score));
            }
        }
    }

    /** Creates a new eval builder. */
    public static <INPUT, OUTPUT> Builder<INPUT, OUTPUT> builder() {
        return new Builder<>();
    }

    /** Builder for creating evaluations with fluent API. */
    public static final class Builder<INPUT, OUTPUT> {
        private @Nonnull Dataset<INPUT, OUTPUT> dataset;
        private @Nonnull String experimentName = "unnamed-java-eval";
        private @Nullable BraintrustConfig config;
        private @Nullable BraintrustOpenApiClient apiClient;
        private @Nullable String projectId;
        private @Nullable Tracer tracer = null;
        private @Nullable Task<INPUT, OUTPUT> task;
        private @Nonnull List<Scorer<INPUT, OUTPUT>> scorers = List.of();
        private @Nonnull List<Classifier<INPUT, OUTPUT>> classifiers = List.of();
        private @Nonnull List<ParameterDef<?>> parameterDefs = List.of();
        private @Nonnull Map<String, Object> parameterValues = Map.of();
        private @Nonnull List<String> tags = List.of();
        private @Nonnull Map<String, Object> metadata = Map.of();
        private @Nonnull EvalTargetProvider targetProvider = new ExperimentTargetProvider();
        // Seeded with the standard span decorator; removable via clearListeners().
        private @Nonnull List<EvalListener> listeners =
                new ArrayList<>(List.of(new EvalSpanDecorator()));

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
            if (scorers.isEmpty() && classifiers.isEmpty()) {
                throw new RuntimeException("must provide at least one scorer or classifier");
            }
            if (null == apiClient) {
                apiClient = BraintrustOpenApiClient.of(config);
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

        public Builder<INPUT, OUTPUT> apiClient(BraintrustOpenApiClient apiClient) {
            this.apiClient = apiClient;
            return this;
        }

        @Deprecated
        public Builder<INPUT, OUTPUT> apiClient(BraintrustApiClient apiClient) {
            return apiClient(apiClient.openApiClient());
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
                                DatasetCase<INPUT, OUTPUT> datasetCase, Parameters parameters)
                                throws Exception {
                            var result = taskFn.apply(datasetCase.input());
                            return new TaskResult<>(result, datasetCase, parameters);
                        }
                    });
        }

        @SafeVarargs
        public final Builder<INPUT, OUTPUT> scorers(Scorer<INPUT, OUTPUT>... scorers) {
            this.scorers = List.of(scorers);
            return this;
        }

        @SafeVarargs
        public final Builder<INPUT, OUTPUT> classifiers(Classifier<INPUT, OUTPUT>... classifiers) {
            this.classifiers = List.of(classifiers);
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

        /** Adds a listener which will be notified of eval lifecycle events. */
        public Builder<INPUT, OUTPUT> addListener(@Nonnull EvalListener listener) {
            this.listeners.add(Objects.requireNonNull(listener));
            return this;
        }

        /**
         * Removes all attached listeners, including the built-in {@link EvalSpanDecorator}. Use
         * this to fully control span decoration (e.g. the playground attaches its own decorator).
         */
        public Builder<INPUT, OUTPUT> clearListeners() {
            this.listeners.clear();
            return this;
        }

        /**
         * Overrides how the eval target (parent / experiment) is resolved. Defaults to creating a
         * Braintrust experiment ({@link ExperimentTargetProvider}).
         */
        public Builder<INPUT, OUTPUT> evalTargetProvider(@Nonnull EvalTargetProvider provider) {
            this.targetProvider = Objects.requireNonNull(provider);
            return this;
        }

        /** Sets metadata for the experiment. */
        public Builder<INPUT, OUTPUT> metadata(Map<String, Object> metadata) {
            this.metadata = Map.copyOf(metadata);
            return this;
        }

        /**
         * Sets parameter definitions for this eval. Default values from the definitions are used
         * unless overridden via {@link #parameterValues(Map)}.
         */
        @SuppressWarnings("rawtypes")
        public Builder<INPUT, OUTPUT> parameters(ParameterDef<?>... parameterDefs) {
            this.parameterDefs = List.of(parameterDefs);
            return this;
        }

        /** Sets parameter definitions for this eval. */
        public Builder<INPUT, OUTPUT> parameters(List<ParameterDef<?>> parameterDefs) {
            this.parameterDefs = List.copyOf(parameterDefs);
            return this;
        }

        /**
         * Sets explicit parameter values, overriding any defaults from parameter definitions. Keys
         * not present here fall back to the default value from the corresponding {@link
         * ParameterDef}.
         */
        public Builder<INPUT, OUTPUT> parameterValues(Map<String, Object> values) {
            this.parameterValues = Map.copyOf(values);
            return this;
        }

        /** Builds the merged Parameters from definitions and explicit values. */
        private Parameters buildParameters() {
            if (parameterDefs.isEmpty() && parameterValues.isEmpty()) {
                return Parameters.empty();
            }
            return new Parameters(parameterDefs, parameterValues);
        }
    }
}
