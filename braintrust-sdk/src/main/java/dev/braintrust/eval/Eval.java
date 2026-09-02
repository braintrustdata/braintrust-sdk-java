package dev.braintrust.eval;

import static dev.braintrust.json.BraintrustJsonMapper.toJson;

import dev.braintrust.BraintrustUtils;
import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.api.BraintrustOpenApiClient;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.openapi.api.ExperimentsApi;
import dev.braintrust.openapi.model.CreateExperiment;
import dev.braintrust.openapi.model.Project;
import dev.braintrust.trace.BrainstoreTrace;
import dev.braintrust.trace.BraintrustContext;
import dev.braintrust.trace.BraintrustTracing;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final boolean ensureNew;
    private final int maxConcurrency;
    private final @Nullable Executor executor;

    /**
     * True when no executor was supplied, so each run creates its own pool and is responsible for
     * shutting it down when it finishes.
     */
    private final boolean ownsExecutor;

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
        this.ensureNew = builder.ensureNew;
        this.maxConcurrency =
                builder.maxConcurrency != null
                        ? builder.maxConcurrency
                        : config.defaultMaxConcurrency();
        this.executor = builder.executor;
        this.ownsExecutor = builder.executor == null;
    }

    /** Runs the evaluation to completion and returns the results. */
    public EvalResult run() {
        var result = start();
        result.awaitCompletion();
        return result;
    }

    /**
     * Creates the experiment and begins evaluating cases in the background, returning as soon as
     * the experiment exists on Braintrust.
     *
     * <p>The returned {@link EvalResult} carries the experiment id, name and url immediately — so
     * callers can surface the link right away — while its cases are still being evaluated. Use
     * {@link EvalResult#isDone()} and {@link EvalResult#awaitCompletion()} to observe the run.
     *
     * <p>Errors raised while creating the experiment are thrown from this method. Errors raised
     * once the run is underway are reported through {@link EvalResult#awaitCompletion()}.
     */
    public EvalResult start() {
        var state = new EvalResult.RunState();
        var cursor = dataset.openCursor();
        final EvalResult result;
        final Executor caseExecutor;
        try {
            Optional<String> datasetVersion = Optional.empty();
            Optional<String> datasetId = Optional.empty();
            if (dataset instanceof DatasetBrainstoreImpl<INPUT, OUTPUT>) {
                datasetVersion = cursor.version();
                datasetId = Optional.of(dataset.id());
            }

            var createExperiment =
                    new CreateExperiment().projectId(project.getId()).name(experimentName);

            if (ensureNew) {
                createExperiment.ensureNew(true);
            }
            if (!tags.isEmpty()) {
                createExperiment.tags(tags);
            }
            if (!metadata.isEmpty()) {
                createExperiment.metadata(metadata);
            }
            datasetId.ifPresent(id -> createExperiment.datasetId(UUID.fromString(id)));
            datasetVersion.ifPresent(createExperiment::datasetVersion);

            var experiment = new ExperimentsApi(client).postExperiment(createExperiment);

            // Use the experiment's actual name from the response: with ensure_new the backend may
            // dedupe a conflicting name (e.g. "foo" -> "foo-2f8ca776"), and the URL must point at
            // the real, created experiment.
            var resolvedName = experiment.getName() != null ? experiment.getName() : experimentName;
            var experimentUrl =
                    "%s/experiments/%s"
                            .formatted(
                                    BraintrustUtils.createProjectURI(
                                                    config.appUrl(),
                                                    orgInfo.name(),
                                                    project.getName())
                                            .toASCIIString(),
                                    resolvedName);
            result =
                    new EvalResult(
                            experiment.getId().toString(), resolvedName, experimentUrl, state);

            caseExecutor =
                    ownsExecutor
                            ? BraintrustUtils.newExecutor(maxConcurrency, "braintrust-eval-worker-")
                            : Objects.requireNonNull(executor);
        } catch (Throwable t) {
            cursor.close();
            throw t;
        }

        // Each case re-establishes the context that was current when the run was started. The eval
        // span itself is created with setNoParent(), so this carries baggage rather than parentage.
        var callerContext = Context.current();
        var experimentId = Objects.requireNonNull(result.getExperimentId());
        var coordinator =
                new Thread(
                        () ->
                                evalAllCases(
                                        cursor, caseExecutor, experimentId, callerContext, state),
                        "braintrust-eval-coordinator");
        coordinator.setDaemon(true);
        coordinator.start();
        return result;
    }

    /**
     * Drains the dataset cursor on this (coordinator) thread, submitting each case to {@code
     * caseExecutor} as fast as it will take them, then waits for every submitted case to finish.
     *
     * <p>The eval does not limit how many cases are in flight — the executor does. The default
     * executor blocks the submit below once every worker is busy, so the loop advances at the pace
     * the pool sets and the whole dataset is never materialized in memory. A caller-supplied
     * executor with an unbounded queue accepts every case immediately and provides no such
     * backpressure.
     *
     * <p>The drain is deliberately single-threaded: {@link Dataset.Cursor} is
     * {@code @NotThreadSafe} and its {@code next()} may make network calls, so one thread pulls
     * cases and fans them out.
     *
     * <p>Runs on the coordinator thread, never on a worker. Both submitting and waiting can block
     * until a case finishes, so doing either from inside the pool that runs the cases would
     * deadlock once the pool is saturated.
     */
    private void evalAllCases(
            Dataset.Cursor<DatasetCase<INPUT, OUTPUT>> cursor,
            Executor caseExecutor,
            String experimentId,
            Context callerContext,
            EvalResult.RunState state) {
        // The coordinator starts as one pending party so completion cannot win while cases are
        // still being registered. This counter and single future use constant memory for the run.
        var pendingCases = new AtomicInteger(1);
        var casesCompleted = new CompletableFuture<Void>();
        Throwable fatal = null;
        try (cursor) {
            for (var next = cursor.next(); next.isPresent(); next = cursor.next()) {
                if (state.isAborting()) {
                    // A case hit a fatal error (see EvalAbortedException). Stop feeding the pool;
                    // cases already in flight finish or skip themselves.
                    break;
                }
                var datasetCase = next.get();
                pendingCases.incrementAndGet();
                try {
                    caseExecutor.execute(
                            () -> {
                                try {
                                    evalOneTracked(experimentId, callerContext, datasetCase, state);
                                } finally {
                                    caseCompleted(pendingCases, casesCompleted);
                                }
                            });
                } catch (Throwable t) {
                    // execute() rejected the case, so undo the registration before surfacing the
                    // drain failure below.
                    caseCompleted(pendingCases, casesCompleted);
                    throw t;
                }
            }
        } catch (Throwable t) {
            // e.g. a failure fetching the next page of a dataset, or a rejected submit. Cases
            // already submitted still get to finish below.
            fatal = t;
        }
        caseCompleted(pendingCases, casesCompleted);
        casesCompleted.join();
        if (ownsExecutor && caseExecutor instanceof ExecutorService owned) {
            owned.shutdown();
        }
        if (fatal != null) {
            // A coordinator-side failure (dataset paging, a rejected submit) aborts the run too;
            // registering it here lets the first error encountered win either way.
            state.abort(fatal);
        }
        var abortCause = state.abortCauseOrNull();
        if (abortCause != null) {
            // Logged before completing, so that a caller unblocked by awaitCompletion() cannot
            // race ahead of it. Callers that never await (e.g. a run started with start() and left
            // to finish in the background) would otherwise never see this at all.
            log.error(
                    "Eval aborted for experiment {} after {} case(s)",
                    experimentId,
                    state.getCasesExecuted(),
                    abortCause);
        } else {
            log.debug(
                    "Eval complete for experiment {}: {} case(s) executed",
                    experimentId,
                    state.getCasesExecuted());
        }
        state.complete();
    }

    private static void caseCompleted(
            AtomicInteger pendingCases, CompletableFuture<Void> casesCompleted) {
        if (pendingCases.decrementAndGet() == 0) {
            casesCompleted.complete(null);
        }
    }

    /**
     * Evaluates one case and counts it against {@code state}. Case-level failures are handled
     * inside {@link #evalOne} and recorded on the case's spans; the run state only tracks how many
     * cases ran. Any throw that escapes {@link #evalOne} aborts the whole run, because it means the
     * eval rather than the case is broken.
     */
    private void evalOneTracked(
            String experimentId,
            Context callerContext,
            DatasetCase<INPUT, OUTPUT> datasetCase,
            EvalResult.RunState state) {
        if (state.isAborting()) {
            // Another case already aborted the run; don't start work we're about to throw away.
            return;
        }
        try (var unused = callerContext.makeCurrent()) {
            evalOne(experimentId, datasetCase);
            state.caseExecuted();
        } catch (EvalAbortedException e) {
            state.caseExecuted();
            log.error("Aborting eval for experiment {}", experimentId, e.getCause());
            // Reported to the caller through EvalResult#awaitCompletion(), which rethrows the
            // original error rather than this marker.
            state.abort(e.getCause());
        } catch (Throwable t) {
            // Nothing that reaches here is a contained per-case failure: a task that throws is
            // recorded on its span and passed to Scorer#scoreForTaskException, and classifier
            // exceptions land in the root span's `classifier_errors`. An escape therefore means
            // the eval or the SDK is broken, and every remaining case would hit the same problem,
            // so abort the run and surface the original error.
            state.caseExecuted();
            log.error("Aborting eval for input: {}", datasetCase.input(), t);
            state.abort(t);
        }
    }

    /**
     * Marks an error that must abort the entire run rather than just failing its case: the eval is
     * misconfigured or its scorers are broken, so every remaining case would hit the same problem.
     * Never escapes {@link #evalOneTracked}, which unwraps it onto the run's state.
     */
    private static final class EvalAbortedException extends RuntimeException {
        EvalAbortedException(Throwable cause) {
            super(cause);
        }
    }

    /**
     * Evaluates a single case. Runs entirely on one thread so that the OpenTelemetry scopes it
     * opens stay thread-confined.
     *
     * <p>Returns normally whether or not the case succeeded: a task exception is recorded on the
     * case's spans and handed to each {@link Scorer#scoreForTaskException}. Anything this method
     * throws aborts the run (see {@link #evalOneTracked}).
     */
    private void evalOne(String experimentId, DatasetCase<INPUT, OUTPUT> datasetCase) {
        var rootSpan =
                tracer.spanBuilder("eval") // TODO: allow names for eval cases
                        .setNoParent() // each eval case is its own trace
                        .setSpanKind(SpanKind.CLIENT)
                        .setAttribute(PARENT, "experiment_id:" + experimentId)
                        .setAttribute("braintrust.span_attributes", toJson(Map.of("type", "eval")))
                        .setAttribute("braintrust.input_json", toJson(datasetCase.input()))
                        .setAttribute("braintrust.expected_json", toJson(datasetCase.expected()))
                        .startSpan();
        // Everything from here on runs inside the try, so that a throw while writing attributes
        // (serializing a case's metadata, say) still ends the span instead of leaking it.
        try (var rootScope = BraintrustContext.ofExperiment(experimentId, rootSpan).makeCurrent()) {
            if (datasetCase.origin().isPresent()) {
                rootSpan.setAttribute("braintrust.origin", toJson(datasetCase.origin().get()));
            }
            if (!datasetCase.tags().isEmpty()) {
                rootSpan.setAttribute(
                        AttributeKey.stringArrayKey("braintrust.tags"), datasetCase.tags());
            }
            if (!datasetCase.metadata().isEmpty()) {
                rootSpan.setAttribute(
                        AttributeKey.stringKey("braintrust.metadata"),
                        toJson(datasetCase.metadata()));
            }
            final TaskResult<INPUT, OUTPUT> taskResult;
            final String taskSpanId;
            { // run task
                var taskSpan =
                        tracer.spanBuilder("task")
                                .setAttribute(PARENT, "experiment_id:" + experimentId)
                                .setAttribute(
                                        "braintrust.span_attributes",
                                        toJson(Map.of("type", "task")))
                                .setAttribute("braintrust.input_json", toJson(datasetCase.input()))
                                .setAttribute(
                                        "braintrust.expected_json", toJson(datasetCase.expected()))
                                .startSpan();
                taskSpanId = taskSpan.getSpanContext().getSpanId();
                try (var unused =
                        BraintrustContext.ofExperiment(experimentId, taskSpan).makeCurrent()) {
                    taskResult = task.apply(datasetCase, parameters);
                    taskSpan.setAttribute("braintrust.output_json", toJson(taskResult.result()));
                    rootSpan.setAttribute("braintrust.output_json", toJson(taskResult.result()));
                } catch (Exception e) {
                    taskSpan.setStatus(StatusCode.ERROR, e.getMessage());
                    taskSpan.recordException(e);
                    taskSpan.end();
                    rootSpan.setStatus(StatusCode.ERROR, e.getMessage());
                    log.debug("Task threw exception for input: " + datasetCase.input(), e);
                    // The case is over, but each scorer still gets a chance to score the failure.
                    // Classifiers do not run: they have no scoreForTaskException analogue.
                    for (var scorer : scorers) {
                        runScoreForTaskException(experimentId, rootSpan, scorer, e, datasetCase);
                    }
                    return;
                }
                taskSpan.end();
            }

            // Create a single BrainstoreTrace for this eval case, shared across all scorers.
            // It fetches spans lazily on first access (only if a TracedScorer actually calls it).
            // We wait specifically for the task span to appear, which guarantees its children
            // (LLM spans, tool spans) have also been indexed — since children end before parents.
            var rootTraceId = rootSpan.getSpanContext().getTraceId();
            var trace =
                    BrainstoreTrace.forExperiment(
                            client, experimentId, rootTraceId, List.of(taskSpanId));

            // run scorers - one span per scorer
            for (var scorer : scorers) {
                runScorer(experimentId, rootSpan, scorer, taskResult, trace);
            }

            // run classifiers - one span per classifier. Classifier exceptions are non-fatal:
            // they are recorded on the classifier span and surfaced in the root span's metadata
            // under `classifier_errors`, but do not abort the eval or affect other classifiers/
            // scorers. Classifiers only run when the task succeeded (no scoreForTaskException
            // analogue).
            if (!classifiers.isEmpty()) {
                Map<String, List<Map<String, Object>>> caseClassifications = new LinkedHashMap<>();
                Map<String, String> classifierErrors = new LinkedHashMap<>();
                for (int i = 0; i < classifiers.size(); i++) {
                    var classifier = classifiers.get(i);
                    var classifierName = classifier.getName();
                    if (classifierName == null || classifierName.isBlank()) {
                        classifierName = "classifier_" + i;
                    }
                    runClassifier(
                            experimentId,
                            classifier,
                            classifierName,
                            taskResult,
                            trace,
                            caseClassifications,
                            classifierErrors);
                }
                if (!caseClassifications.isEmpty()) {
                    rootSpan.setAttribute(
                            "braintrust.classifications", toJson(caseClassifications));
                }
                if (!classifierErrors.isEmpty()) {
                    Map<String, Object> mergedMetadata =
                            new LinkedHashMap<>(datasetCase.metadata());
                    mergedMetadata.put("classifier_errors", classifierErrors);
                    rootSpan.setAttribute(
                            AttributeKey.stringKey("braintrust.metadata"), toJson(mergedMetadata));
                }
            }
        } finally {
            rootSpan.end();
        }
    }

    /**
     * Runs a scorer against a successful task result. If the scorer is a {@link TracedScorer}, it
     * receives the {@link BrainstoreTrace} for the eval case. If the scorer throws, falls back to
     * {@link Scorer#scoreForScorerException}.
     */
    private void runScorer(
            String experimentId,
            Span rootSpan,
            Scorer<INPUT, OUTPUT> scorer,
            TaskResult<INPUT, OUTPUT> taskResult,
            BrainstoreTrace trace) {
        var scoreSpan =
                tracer.spanBuilder("score")
                        .setAttribute(PARENT, "experiment_id:" + experimentId)
                        .startSpan();
        try (var unused = BraintrustContext.ofExperiment(experimentId, scoreSpan).makeCurrent()) {
            List<Score> scores;
            try {
                if (scorer instanceof TracedScorer<INPUT, OUTPUT> tracedScorer) {
                    scores = tracedScorer.score(taskResult, trace);
                } else {
                    scores = scorer.score(taskResult);
                }
            } catch (Exception e) {
                scoreSpan.setStatus(StatusCode.ERROR, e.getMessage());
                scoreSpan.recordException(e);
                log.debug("Scorer '{}' threw exception", scorer.getName(), e);
                // fall back to scoreForScorerException — if this throws, eval aborts
                try {
                    scores = scorer.scoreForScorerException(e, taskResult);
                } catch (Throwable fatal) {
                    throw new EvalAbortedException(fatal);
                }
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
            final List<Score> scores;
            try {
                scores = scorer.scoreForTaskException(taskException, datasetCase);
            } catch (Throwable fatal) {
                // if this throws, the eval aborts
                throw new EvalAbortedException(fatal);
            }
            recordScores(scoreSpan, rootSpan, scorer, scores);
        } finally {
            scoreSpan.end();
        }
    }

    /**
     * Runs a classifier inside its own span. Exceptions are recorded on the classifier span and
     * surfaced via {@code classifierErrors}; they do not propagate.
     */
    private void runClassifier(
            String experimentId,
            Classifier<INPUT, OUTPUT> classifier,
            String resolvedName,
            TaskResult<INPUT, OUTPUT> taskResult,
            BrainstoreTrace trace,
            Map<String, List<Map<String, Object>>> caseClassifications,
            Map<String, String> classifierErrors) {
        var classifierSpan =
                tracer.spanBuilder(resolvedName)
                        .setAttribute(PARENT, "experiment_id:" + experimentId)
                        .startSpan();
        try (var unused =
                BraintrustContext.ofExperiment(experimentId, classifierSpan).makeCurrent()) {
            Map<String, Object> spanAttrs = new LinkedHashMap<>();
            spanAttrs.put("type", "classifier");
            spanAttrs.put("name", resolvedName);
            spanAttrs.put("purpose", "scorer");
            classifierSpan.setAttribute("braintrust.span_attributes", toJson(spanAttrs));

            List<Classification> classifications;
            try {
                if (classifier instanceof TracedClassifier<INPUT, OUTPUT> tracedClassifier) {
                    classifications = tracedClassifier.classify(taskResult, trace);
                } else {
                    classifications = classifier.classify(taskResult);
                }
                if (classifications == null) {
                    classifications = List.of();
                }
            } catch (Exception e) {
                classifierSpan.setStatus(StatusCode.ERROR, e.getMessage());
                classifierSpan.recordException(e);
                log.debug("Classifier '{}' threw exception", resolvedName, e);
                classifierErrors.put(
                        resolvedName, e.getMessage() == null ? e.toString() : e.getMessage());
                return;
            }

            // Group results by resolved item name (item.name, falling back to the classifier
            // name when blank). Same map is logged to the classifier span and merged into the
            // per-case aggregate logged on the root span.
            Map<String, List<Map<String, Object>>> outputByName = new LinkedHashMap<>();
            for (var item : classifications) {
                var itemName = item.name();
                if (itemName == null || itemName.isBlank()) {
                    itemName = resolvedName;
                }
                var itemMap = toClassificationItem(item);
                outputByName.computeIfAbsent(itemName, k -> new ArrayList<>()).add(itemMap);
                caseClassifications.computeIfAbsent(itemName, k -> new ArrayList<>()).add(itemMap);
            }
            classifierSpan.setAttribute("braintrust.output_json", toJson(outputByName));
        } finally {
            classifierSpan.end();
        }
    }

    /**
     * Converts a {@link Classification} to the wire-format {@code ClassificationItem}: drops {@code
     * name}, includes {@code label} and {@code metadata} only when present.
     */
    private static Map<String, Object> toClassificationItem(Classification c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        if (c.label() != null) {
            m.put("label", c.label());
        }
        if (c.metadata() != null) {
            m.put("metadata", c.metadata());
        }
        return m;
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
                // A scorer that returns an out-of-range score is broken, not unlucky: abort the
                // run rather than let every remaining case hit the same bug.
                throw new EvalAbortedException(
                        new RuntimeException(
                                "score must be between 0 and 1: %s : %s"
                                        .formatted(scorer.getName(), score)));
            }
            scorerScores.put(score.name(), score.value());
        }
        Map<String, Object> spanAttrs = new LinkedHashMap<>();
        spanAttrs.put("type", "score");
        spanAttrs.put("name", scorer.getName());
        spanAttrs.put("purpose", "scorer");
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
        private boolean ensureNew = false;
        private @Nullable Integer maxConcurrency = null;
        private @Nullable Executor executor;

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

        /** Sets the maximum amount of cases which can be evaluated simultaneously. */
        public Builder<INPUT, OUTPUT> maxConcurrency(int maxConcurrency) {
            if (maxConcurrency < 1) {
                throw new IllegalArgumentException(
                        "maxConcurrency must be at least 1, got " + maxConcurrency);
            }
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        /**
         * Sets the executor that eval cases run on, replacing the pool the eval would otherwise
         * create for itself (see {@link #maxConcurrency(int)}, which is then ignored).
         *
         * <p>This executor decides how many cases run at once, and it is also the eval's only
         * backpressure: the eval submits cases as fast as the executor accepts them. An executor
         * with an unbounded queue therefore accepts the entire dataset up front and holds every
         * pending case in memory. Prefer one that blocks or otherwise bounds its queue.
         *
         * <p>An executor supplied here is never shut down by the SDK — the caller owns its
         * lifecycle.
         */
        public Builder<INPUT, OUTPUT> executor(@Nonnull Executor executor) {
            this.executor = Objects.requireNonNull(executor);
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

        /** Sets metadata for the experiment. */
        public Builder<INPUT, OUTPUT> metadata(Map<String, Object> metadata) {
            this.metadata = Map.copyOf(metadata);
            return this;
        }

        /**
         * When {@code true}, sets {@code ensure_new} on the create-experiment request so a new
         * experiment is always created even if one with the same name already exists (the backend
         * dedupes the name on conflict). Useful for repeated runs (e.g. UI/remote snapshots) that
         * should each produce a distinct experiment. Defaults to {@code false}.
         */
        public Builder<INPUT, OUTPUT> ensureNew(boolean ensureNew) {
            this.ensureNew = ensureNew;
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
