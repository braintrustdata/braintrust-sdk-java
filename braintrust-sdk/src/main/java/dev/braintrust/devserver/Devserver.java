package dev.braintrust.devserver;

import static dev.braintrust.json.BraintrustJsonMapper.fromJson;
import static dev.braintrust.json.BraintrustJsonMapper.toJson;

import com.fasterxml.jackson.databind.node.NullNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.braintrust.Braintrust;
import dev.braintrust.BraintrustUtils;
import dev.braintrust.Origin;
import dev.braintrust.api.BraintrustOpenApiClient;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.eval.*;
import dev.braintrust.trace.BraintrustContext;
import dev.braintrust.trace.BraintrustTracing;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

/** Remote Eval Dev Server */
@Slf4j
public class Devserver {
    private static final Pattern PREVIEW_DOMAIN_PATTERN =
            Pattern.compile("^https://[^/]+\\.preview\\.braintrust\\.dev$");

    /** How long {@link #stop()} waits for in-flight experiment snapshots to finish. */
    private static final Duration SNAPSHOT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(60);

    // Allowed headers for CORS
    private static final String ALLOWED_HEADERS =
            String.join(
                    ", ",
                    "Content-Type",
                    "X-Amz-Date",
                    "Authorization",
                    "X-Api-Key",
                    "X-Amz-Security-Token",
                    "X-Bt-Auth-Token",
                    "X-Bt-Parent",
                    "X-Bt-Org-Name",
                    "X-Bt-Project-Id",
                    "X-Bt-Stream-Fmt",
                    "X-Bt-Use-Cache",
                    "X-Bt-Use-Gateway",
                    "X-Stainless-Os",
                    "X-Stainless-Lang",
                    "X-Stainless-Package-Version",
                    "X-Stainless-Runtime",
                    "X-Stainless-Runtime-Version",
                    "X-Stainless-Arch");

    private static final String EXPOSED_HEADERS =
            "x-bt-cursor, x-bt-found-existing-experiment, x-bt-span-id, x-bt-span-export";

    private static final AttributeKey<String> PARENT =
            AttributeKey.stringKey(BraintrustTracing.PARENT_KEY);

    private final List<String> corsOriginWhitelist;
    private final BraintrustConfig config;

    @Getter
    @Accessors(fluent = true)
    private final String host;

    @Getter
    @Accessors(fluent = true)
    private final int port;

    private final @Nullable String orgName;
    private final Map<String, RemoteEval<?, ?>> evals;
    private @Nullable HttpServer server;

    /** Threads for HTTP request handling. Holds no threads until the first request arrives. */
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * Threads that eval cases run on, kept separate from the HTTP pool. Submitting to this pool
     * blocks once it is saturated, and playground runs submit cases while occupying an HTTP thread
     * — so sharing one pool with request handling would deadlock. Experiment snapshots keep using
     * it after their response has been sent, so it outlives individual requests.
     *
     * <p>Like the HTTP pool it holds no threads while idle, so there is nothing to gain from
     * deferring its creation to {@link #start()}.
     */
    private final ExecutorService evalExecutor;

    private final int maxConcurrency;

    /**
     * Experiment snapshots that are still running. A snapshot outlives the request that triggered
     * it (the caller gets the experiment link immediately and the cases keep running), so the
     * server has to remember them: {@link #stop()} waits on them rather than truncating an
     * experiment someone is already looking at. Finished runs are swept out as new ones arrive.
     */
    private final Set<EvalResult> runningSnapshots = ConcurrentHashMap.newKeySet();

    private final @Nullable Consumer<io.opentelemetry.sdk.trace.SdkTracerProviderBuilder>
            traceBuilderHook;
    private final @Nullable Consumer<BraintrustConfig.Builder> configBuilderHook;

    // LRU cache for token -> Braintrust mappings
    private final LRUCache<String, Braintrust> authCache = new LRUCache<>(32);

    private Devserver(Builder builder) {
        this.config = Objects.requireNonNull(builder.config);
        this.host = builder.host;
        this.port = builder.port;
        this.orgName = builder.orgName;
        this.traceBuilderHook = builder.traceBuilderHook;
        this.configBuilderHook = builder.configBuilderHook;
        this.maxConcurrency =
                builder.maxConcurrency != null
                        ? builder.maxConcurrency
                        : config.defaultMaxConcurrency();
        this.evalExecutor =
                BraintrustUtils.newExecutor(maxConcurrency, "braintrust-devserver-eval-");
        Map<String, RemoteEval<?, ?>> evalMap = new HashMap<>();
        for (RemoteEval<?, ?> eval : builder.evals) {
            if (evalMap.containsKey(eval.getName())) {
                throw new IllegalArgumentException("Duplicate evaluator name: " + eval.getName());
            }
            evalMap.put(eval.getName(), eval);
        }
        this.evals = Collections.unmodifiableMap(evalMap);
        if (orgName != null) {
            throw new NotSupportedYetException("org name filtering");
        }
        this.corsOriginWhitelist =
                List.copyOf(
                        BraintrustUtils.append(
                                BraintrustUtils.parseCsv(config.devserverCorsOriginWhitelistCsv()),
                                config.appUrl()));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Start the dev server. This method blocks until the server is stopped. */
    public synchronized void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("Server is already running");
        }
        if (executor.isShutdown()) {
            // The pools are final and shut down by stop(), so a stopped devserver is done for good.
            throw new IllegalStateException("Server has been stopped; build a new one to restart");
        }

        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(executor);

        server.createContext("/", withCors(this::handleHealthCheck));
        server.createContext("/list", withCors(this::handleList));
        server.createContext("/eval", withCors(this::handleEval));

        server.start();
        log.info("Braintrust dev server started on http://{}:{}", host, port);
        log.info("Registered {} evaluator(s): {}", evals.size(), evals.keySet());
    }

    /** Stop the dev server. */
    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            executor.shutdown();
            // Snapshot experiments run in the background and their link has already been handed
            // to the user, so give them a bounded chance to finish. This has to happen before the
            // pool is shut down: a snapshot still draining its dataset submits its remaining cases
            // as it goes, and those submits would be rejected.
            awaitRunningSnapshots(SNAPSHOT_SHUTDOWN_TIMEOUT);
            // Lets evals still running in the background finish, but rejects new work.
            evalExecutor.shutdown();
            log.info("Braintrust dev server stopped");
        }
    }

    /**
     * Remembers a snapshot run so {@link #stop()} can wait on it, sweeping out the ones that have
     * since finished. Snapshots are the only evals that outlive their request, and there are few of
     * them, so a sweep per snapshot is cheaper than anything that watches them continuously.
     */
    private void trackSnapshot(EvalResult result) {
        runningSnapshots.removeIf(EvalResult::isDone);
        // This may race with stop(): shutdown's best-effort sweep can finish before this result is
        // added. That is acceptable once server shutdown is underway, when in-flight requests and
        // their background evals are no longer guaranteed to complete.
        runningSnapshots.add(result);
    }

    /**
     * Blocks until every snapshot still running has finished, or {@code timeout} elapses across all
     * of them.
     *
     * @return true if they all finished
     */
    private boolean awaitRunningSnapshots(Duration timeout) {
        runningSnapshots.removeIf(EvalResult::isDone);
        if (runningSnapshots.isEmpty()) {
            return true;
        }
        log.info(
                "Waiting up to {}s for {} snapshot experiment(s) to finish",
                timeout.toSeconds(),
                runningSnapshots.size());
        var deadline = System.nanoTime() + timeout.toNanos();
        for (var result : runningSnapshots) {
            var remaining = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
            try {
                if (!result.awaitCompletion(remaining)) {
                    // Out of budget. The cases run on daemon threads, so whatever this snapshot
                    // has not flushed by the time the JVM exits is lost.
                    log.warn(
                            "Stopped waiting: snapshot experiment(s) still running and may be"
                                    + " incomplete: {}",
                            result.getExperimentUrl());
                    return false;
                }
            } catch (Exception e) {
                // The run aborted; awaitCompletion rethrows its error. Nothing to do about it at
                // shutdown and the other snapshots still deserve their wait.
            }
        }
        runningSnapshots.clear();
        return true;
    }

    private void handleHealthCheck(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        sendResponse(exchange, 200, "text/plain", "Hello, world!");
    }

    private void handleList(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }

        // Check API key is present
        RequestContext context = createRequestContext(exchange);
        String apiKey = extractApiKey(exchange, context);
        if (apiKey == null) {
            sendErrorResponse(exchange, 401, "Missing authentication token");
            return;
        }

        try {
            // Build the response: Map<evalName, EvalMetadata>
            Map<String, Map<String, Object>> response = new LinkedHashMap<>();

            for (Map.Entry<String, RemoteEval<?, ?>> entry : evals.entrySet()) {
                String evalName = entry.getKey();
                RemoteEval<?, ?> eval = entry.getValue();

                Map<String, Object> metadata = new LinkedHashMap<>();

                // Serialize parameters in the container format.
                if (!eval.getParameters().isEmpty()) {
                    Map<String, Map<String, Object>> schemaMap = new LinkedHashMap<>();
                    for (ParameterDef<?> param : eval.getParameters()) {
                        Map<String, Object> paramMetadata = new LinkedHashMap<>();
                        paramMetadata.put("type", param.type().toString().toLowerCase());

                        if (param.schema() != null) {
                            paramMetadata.put("schema", param.schema());
                        }

                        if (param.defaultValue() != null) {
                            paramMetadata.put("default", param.defaultValue());
                        }

                        if (param.description() != null) {
                            paramMetadata.put("description", param.description());
                        }

                        schemaMap.put(param.name(), paramMetadata);
                    }

                    Map<String, Object> parametersContainer = new LinkedHashMap<>();
                    parametersContainer.put("type", "braintrust.staticParameters");
                    parametersContainer.put("schema", schemaMap);
                    parametersContainer.put("source", NullNode.getInstance());
                    metadata.put("parameters", parametersContainer);
                }

                // Add scores (list of scorer names)
                List<Map<String, String>> scores = new ArrayList<>();
                for (var scorer : eval.getScorers()) {
                    Map<String, String> scoreInfo = new LinkedHashMap<>();
                    scoreInfo.put("name", scorer.getName());
                    scores.add(scoreInfo);
                }
                metadata.put("scores", scores);

                response.put(evalName, metadata);
            }

            String jsonResponse = toJson(response);
            sendResponse(exchange, 200, "application/json", jsonResponse);
        } catch (Exception e) {
            log.error("Error generating /list response", e);
            sendResponse(exchange, 500, "text/plain", "Internal Server Error");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void handleEval(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }

        // Check authorization and get Braintrust state
        RequestContext context = createRequestContext(exchange);
        context = getBraintrust(exchange, context);
        if (context == null) {
            sendErrorResponse(exchange, 401, "Missing required authentication headers");
            return;
        }

        try {
            InputStream requestBody = exchange.getRequestBody();
            var requestBodyString = new String(requestBody.readAllBytes(), StandardCharsets.UTF_8);
            EvalRequest request;
            try {
                request = fromJson(requestBodyString, EvalRequest.class);
            } catch (Exception e) {
                sendResponse(
                        exchange, 400, "text/plain", "Invalid request body: " + e.getMessage());
                return;
            }

            // Validate evaluator exists
            RemoteEval eval = evals.get(request.getName());
            if (eval == null) {
                sendResponse(
                        exchange, 404, "text/plain", "Evaluator not found: " + request.getName());
                return;
            }

            // Validate dataset specification
            if (request.getData() == null) {
                sendResponse(exchange, 400, "text/plain", "Missing 'data' field in request body");
                return;
            }

            EvalRequest.DataSpec dataSpec = request.getData();
            boolean hasInlineData = dataSpec.getData() != null && !dataSpec.getData().isEmpty();
            boolean hasByName =
                    dataSpec.getProjectName() != null && dataSpec.getDatasetName() != null;
            boolean hasById = dataSpec.getDatasetId() != null;

            // Ensure exactly one dataset specification method is provided
            int specCount = (hasInlineData ? 1 : 0) + (hasByName ? 1 : 0) + (hasById ? 1 : 0);
            if (specCount == 0) {
                sendResponse(
                        exchange,
                        400,
                        "text/plain",
                        "Dataset must be specified using one of: inline data (data.data), by name"
                                + " (data.project_name + data.dataset_name), or by ID"
                                + " (data.dataset_id)");
                return;
            }
            if (specCount > 1) {
                sendResponse(
                        exchange,
                        400,
                        "text/plain",
                        "Only one dataset specification method should be provided");
                return;
            }

            // Resolve remote scorers from the request
            List<Scorer<Object, Object>> remoteScorers = new ArrayList<>();
            if (request.getScores() != null) {
                var apiClient = context.getBraintrust().openApiClient();
                for (var remoteScorer : request.getScores()) {
                    remoteScorers.add(resolveRemoteScorer(remoteScorer, apiClient));
                }
                log.debug(
                        "Resolved {} remote scorer(s): {}",
                        remoteScorers.size(),
                        remoteScorers.stream().map(Scorer::getName).toList());
            }

            String datasetDescription =
                    hasInlineData
                            ? dataSpec.getData().size() + " inline cases"
                            : (hasByName
                                    ? "dataset '"
                                            + dataSpec.getProjectName()
                                            + "/"
                                            + dataSpec.getDatasetName()
                                            + "'"
                                    : "dataset ID '" + dataSpec.getDatasetId() + "'");
            log.debug("Executing evaluator '{}' with {}", request.getName(), datasetDescription);

            // Check if streaming is requested
            boolean isStreaming = request.getStream() != null && request.getStream();

            if (isStreaming) {
                // SSE streaming response - errors handled inside
                log.debug("Starting streaming evaluation for '{}'", request.getName());
                handleStreamingEval(exchange, eval, request, context, remoteScorers);
            } else {
                throw new NotSupportedYetException("non-streaming responses");
            }
        } catch (NotSupportedYetException e) {
            sendResponse(
                    exchange, 400, "text/plain", "TODO: feature not supported: " + e.description);
        } catch (Exception e) {
            log.error("Error executing eval", e);
            // Only send error response if we haven't started streaming
            // (streaming errors are handled within handleStreamingEval)
            sendResponse(exchange, 500, "text/plain", "Internal Server Error: " + e.getMessage());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <I, O> void handleStreamingEval(
            HttpExchange exchange,
            RemoteEval<I, O> eval,
            EvalRequest request,
            RequestContext context,
            List<Scorer<I, O>> remoteScorers)
            throws Exception {
        // Set SSE headers
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0); // 0 = chunked encoding

        try (OutputStream os = exchange.getResponseBody()) {
            try {
                // Get Braintrust instance from authenticated context
                Braintrust braintrust = context.getBraintrust();
                var apiClient = braintrust.openApiClient();

                // Determine project name and ID from the authenticated Braintrust instance
                final var project = apiClient.fetchOrCreateProject(braintrust.config());
                final var projectName = project.getName();
                final var projectId = project.getId().toString();
                final var orgName = apiClient.fetchOrgInfo(project.getOrgId().toString()).name();
                final var experimentName =
                        request.getExperimentName() != null
                                ? request.getExperimentName()
                                : eval.getName();
                final var projectUrl =
                        BraintrustUtils.createProjectURI(
                                        braintrust.config().appUrl(), orgName, projectName)
                                .toASCIIString();

                var tracer = BraintrustTracing.getTracer();

                // A remote eval can be triggered two ways:
                //   1. Playground run: the request carries a `parent` object (playground_id). We
                //      stream per-case progress under that parent (handled by the loop below).
                //   2. Experiment "snapshot": no such parent (instead experiment_name + project).
                //      We run a standard Eval, which creates a real experiment and emits standard
                //      spans, then send a summary + done with the experiment link.
                final var playgroundParent = extractPlaygroundParent(request);
                if (playgroundParent.isEmpty()) {
                    handleExperimentSnapshot(
                            os,
                            eval,
                            request,
                            braintrust,
                            apiClient,
                            projectId,
                            projectName,
                            projectUrl,
                            experimentName,
                            remoteScorers);
                    return;
                }

                // Merge parameters: evaluator defaults + request overrides
                final Parameters mergedParameters =
                        new Parameters(
                                eval.getParameters(),
                                null == request.getParameters()
                                        ? Map.of()
                                        : request.getParameters());

                // Execute task and scorers for each case
                final Map<String, List<Double>> scoresByName = new ConcurrentHashMap<>();
                final var parentInfo = playgroundParent.get();
                final var braintrustParent = parentInfo.braintrustParent();
                final var braintrustGeneration = parentInfo.generation();

                // Cases are evaluated concurrently on the eval pool. The cursor is drained by
                // this (request) thread, which submits cases as fast as the pool accepts them and
                // then waits for them; it never runs a case itself, so it cannot deadlock against
                // the pool. How many cases run at once is the pool's business, not ours.
                //
                // streamFailure is set by the first case whose progress event can't be written,
                // i.e. the client disconnected. Nobody is listening any more, so the run stops
                // there rather than evaluating (and billing for) the remaining cases. runFailure
                // similarly stops the run when a case hits an unrecoverable error, but remains
                // reportable to the connected client as an SSE error.
                var streamFailure = new AtomicReference<IOException>();
                var runFailure = new AtomicReference<Throwable>();
                var caseContext = Context.current();
                // The request thread starts as one pending party so completion cannot win while
                // cases are still being registered. The counter and single future avoid retaining
                // one Future for every case in a potentially large remote dataset.
                var pendingCases = new AtomicInteger(1);
                var casesCompleted = new CompletableFuture<Void>();
                int submittedCount = 0;
                Throwable drainError = null;
                try (var cursor =
                        extractDataset(
                                        request,
                                        apiClient,
                                        eval.getInputConverter(),
                                        eval.getOutputConverter())
                                .openCursor()) {
                    for (var next = cursor.next(); next.isPresent(); next = cursor.next()) {
                        if (streamFailure.get() != null || runFailure.get() != null) {
                            break;
                        }
                        var datasetCase = next.get();
                        pendingCases.incrementAndGet();
                        try {
                            evalExecutor.execute(
                                    () -> {
                                        try {
                                            evalPlaygroundCase(
                                                    os,
                                                    eval,
                                                    tracer,
                                                    caseContext,
                                                    mergedParameters,
                                                    braintrustParent,
                                                    braintrustGeneration,
                                                    remoteScorers,
                                                    scoresByName,
                                                    streamFailure,
                                                    runFailure,
                                                    datasetCase);
                                        } finally {
                                            caseCompleted(pendingCases, casesCompleted);
                                        }
                                    });
                            submittedCount++;
                        } catch (Throwable t) {
                            // execute() rejected the case, so undo the registration before
                            // surfacing the drain failure below.
                            caseCompleted(pendingCases, casesCompleted);
                            throw t;
                        }
                    }
                } catch (Throwable t) {
                    // e.g. a failure fetching the next page of a dataset, or a submit rejected
                    // because the server stopped. Cases already submitted still finish below.
                    drainError = t;
                }
                caseCompleted(pendingCases, casesCompleted);
                casesCompleted.join();
                var brokenStream = streamFailure.get();
                if (brokenStream != null) {
                    // There is nowhere to send a summary, a done or even an error event, so just
                    // log and let the finally below close the stream.
                    log.warn(
                            "Playground run aborted: client stream closed after {} case(s)",
                            submittedCount,
                            brokenStream);
                    return;
                }
                var caseFailure = runFailure.get();
                if (caseFailure instanceof Exception caseException) {
                    throw caseException;
                }
                if (caseFailure != null) {
                    throw new RuntimeException("Eval case failed", caseFailure);
                }
                if (drainError != null) {
                    throw new RuntimeException("Failed to evaluate dataset", drainError);
                }

                // Aggregate scores
                Map<String, EvalResponse.ScoreSummary> scoreSummaries = new LinkedHashMap<>();
                for (Map.Entry<String, List<Double>> entry : scoresByName.entrySet()) {
                    String scoreName = entry.getKey();
                    List<Double> values = entry.getValue();

                    double avgScore =
                            values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

                    scoreSummaries.put(
                            scoreName,
                            EvalResponse.ScoreSummary.builder()
                                    .name(scoreName)
                                    .score(avgScore)
                                    .improvements(0)
                                    .regressions(0)
                                    .build());
                }

                sendSummaryEvent(
                        os, projectName, projectId, experimentName, projectUrl, scoreSummaries);
                sendDoneEvent(os);
            } catch (Exception e) {
                // Send error event via SSE
                log.error("Error during streaming evaluation", e);
                try {
                    sendSSEEvent(
                            os, "error", e.getMessage() != null ? e.getMessage() : "Unknown error");
                } catch (IOException ioException) {
                    log.error("Failed to send error event", ioException);
                }
                // no need to re-throw. We've already sent 200 because we're streaming and the
                // client will see the error event
            } finally {
                try {
                    os.flush();
                    os.close();
                } catch (IOException e) {
                    log.error("Failed to close output stream", e);
                }
            }
        }
    }

    private static void caseCompleted(
            AtomicInteger pendingCases, CompletableFuture<Void> casesCompleted) {
        if (pendingCases.decrementAndGet() == 0) {
            casesCompleted.complete(null);
        }
    }

    /**
     * Evaluates one case of a playground run: emits the {@code eval}/{@code task}/{@code score}
     * spans under the playground parent, streams a {@code progress} event, and accumulates scores
     * into {@code scoresByName}.
     *
     * <p>Runs on the eval pool, one case per thread, so the OpenTelemetry scopes it opens stay
     * thread-confined. Task and initial scorer errors are handled by their scorer fallbacks; an
     * error that escapes those fallbacks is recorded in {@code runFailure} and aborts the run.
     * Failing to write to the SSE stream records {@code streamFailure} instead because the client
     * is gone and cannot receive an error event.
     */
    private <I, O> void evalPlaygroundCase(
            OutputStream os,
            RemoteEval<I, O> eval,
            Tracer tracer,
            Context caseContext,
            Parameters mergedParameters,
            BraintrustUtils.Parent braintrustParent,
            @Nullable String braintrustGeneration,
            List<Scorer<I, O>> remoteScorers,
            Map<String, List<Double>> scoresByName,
            AtomicReference<IOException> streamFailure,
            AtomicReference<Throwable> runFailure,
            DatasetCase<I, O> datasetCase) {
        if (streamFailure.get() != null || runFailure.get() != null) {
            // The run has already failed; don't start work whose results will be discarded.
            return;
        }
        try (var caseScope = caseContext.makeCurrent()) {
            var evalSpan =
                    tracer.spanBuilder("eval")
                            .setNoParent()
                            .setSpanKind(SpanKind.CLIENT)
                            .setAttribute(PARENT, braintrustParent.toParentValue())
                            .startSpan();
            Context evalContext = Context.current().with(evalSpan);
            evalContext =
                    BraintrustContext.setParentInBaggage(
                            evalContext, braintrustParent.type(), braintrustParent.id());
            // Make the eval context (with span and baggage) current
            try (var rootScope = evalContext.makeCurrent()) {
                final TaskResult<I, O> taskResult;
                { // run task
                    var taskSpan = tracer.spanBuilder("task").startSpan();
                    try (var unused = Context.current().with(taskSpan).makeCurrent()) {
                        var task = eval.getTask();
                        try {
                            taskResult = task.apply(datasetCase, mergedParameters);
                        } catch (Exception e) {
                            taskSpan.setStatus(StatusCode.ERROR, e.getMessage());
                            taskSpan.recordException(e);
                            taskSpan.end();
                            evalSpan.setStatus(StatusCode.ERROR, e.getMessage());
                            log.debug("Task threw exception for input: " + datasetCase.input(), e);
                            // Set eval span attributes so Braintrust can resolve the trace
                            setEvalSpanAttributesForError(
                                    evalSpan, braintrustParent, braintrustGeneration, datasetCase);
                            // Send progress event even on error so the Playground can link
                            // to the trace
                            sendProgressEvent(
                                    os,
                                    evalSpan.getSpanContext().getSpanId(),
                                    datasetCase.origin(),
                                    eval.getName(),
                                    null);
                            // run scoreForTaskException on each scorer
                            List<Scorer<I, O>> allScorersForError =
                                    new ArrayList<>(eval.getScorers());
                            allScorersForError.addAll(remoteScorers);
                            for (var scorer : allScorersForError) {
                                runScoreForTaskException(
                                        tracer,
                                        evalSpan,
                                        braintrustParent,
                                        braintrustGeneration,
                                        scorer,
                                        e,
                                        datasetCase,
                                        scoresByName);
                            }
                            return;
                        }
                        // Send progress event for task completion
                        sendProgressEvent(
                                os,
                                evalSpan.getSpanContext().getSpanId(),
                                datasetCase.origin(),
                                eval.getName(),
                                taskResult.result());
                        setTaskSpanAttributes(
                                taskSpan,
                                braintrustParent,
                                braintrustGeneration,
                                datasetCase,
                                taskResult);
                    } finally {
                        taskSpan.end();
                    }
                    // setting eval span attributes here because we need the task output
                    setEvalSpanAttributes(
                            evalSpan,
                            braintrustParent,
                            braintrustGeneration,
                            datasetCase,
                            taskResult);
                }
                // run scorers - one score span per scorer. Combine local scorers from
                // RemoteEval with remote scorers from request
                List<Scorer<I, O>> allScorers = new ArrayList<>(eval.getScorers());
                allScorers.addAll(remoteScorers);
                for (var scorer : allScorers) {
                    runScorer(
                            tracer,
                            evalSpan,
                            braintrustParent,
                            braintrustGeneration,
                            scorer,
                            taskResult,
                            scoresByName);
                }
            } finally {
                evalSpan.end();
            }
        } catch (IOException e) {
            // The SSE stream is broken — the client disconnected. Abort the run: the cases still
            // to come have nowhere to report to.
            streamFailure.compareAndSet(null, e);
        } catch (Throwable t) {
            if (runFailure.compareAndSet(null, t)) {
                log.warn("Aborting playground run after case failed: {}", datasetCase.input(), t);
            }
        }
    }

    /**
     * Handles an experiment "snapshot" run: a remote eval triggered as an Experiment from the UI
     * (no playground parent). Rather than re-implementing experiment creation and span emission, it
     * builds a first-class {@link Eval} — so snapshots get the exact same behavior as a normal
     * {@code Eval.run()} (experiment creation with {@code ensure_new}, dataset id/version linkage
     * for Braintrust-backed datasets, standard span shape).
     *
     * <p>The eval is started with {@link Eval#start()} rather than run to completion: as soon as
     * the experiment exists this streams a single {@code summary} (with the created experiment's
     * id/name/url) and a {@code done} event, then returns while the cases keep evaluating on the
     * server's executor. Errors creating the experiment surface to the caller; failures once the
     * run is underway are logged by the eval. The started run is registered with {@link
     * #trackSnapshot} so that stopping the server waits for it rather than truncating it.
     *
     * <p>Unlike playground runs, snapshots do not stream per-case {@code progress} events: the user
     * is handed the experiment link and views results in the experiment UI.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <I, O> void handleExperimentSnapshot(
            OutputStream os,
            RemoteEval<I, O> eval,
            EvalRequest request,
            Braintrust braintrust,
            BraintrustOpenApiClient apiClient,
            String projectId,
            String projectName,
            String projectUrl,
            String experimentName,
            List<Scorer<I, O>> remoteScorers)
            throws IOException {
        // Combine local scorers (from the RemoteEval) with remote scorers (from the request).
        List<Scorer<I, O>> allScorers = new ArrayList<>(eval.getScorers());
        allScorers.addAll(remoteScorers);

        var evalBuilder =
                Eval.<I, O>builder()
                        .name(experimentName)
                        .config(braintrust.config())
                        .apiClient(apiClient)
                        .projectId(projectId)
                        .dataset(
                                extractDataset(
                                        request,
                                        apiClient,
                                        eval.getInputConverter(),
                                        eval.getOutputConverter()))
                        .task(eval.getTask())
                        .scorers(allScorers.toArray(new Scorer[0]))
                        .parameters(eval.getParameters())
                        .parameterValues(
                                request.getParameters() == null
                                        ? Map.of()
                                        : request.getParameters())
                        // Each snapshot run should produce a distinct experiment even if a prior
                        // run used the same name (the backend dedupes the name on conflict).
                        .ensureNew(true);

        // Run cases on the eval pool so they outlive this request, and hand the experiment link
        // back as soon as the experiment exists rather than waiting for the run to finish. That
        // pool decides how many cases run at once, so maxConcurrency is deliberately not set here.
        evalBuilder.executor(evalExecutor);
        var evalResult = evalBuilder.build().start();
        // The run outlives this request; register it so stop() can wait on it.
        trackSnapshot(evalResult);

        // Snapshots don't stream per-scorer progress. The scores are recorded on the experiment
        // and visible via the experiment link.
        sendExperimentSnapshotSummaryEvent(
                os,
                projectName,
                projectId,
                evalResult.getExperimentId(),
                evalResult.getExperimentName() != null
                        ? evalResult.getExperimentName()
                        : experimentName,
                projectUrl,
                evalResult.getExperimentUrl());
        sendExperimentSnapshotDoneEvent(os);
    }

    private void setEvalSpanAttributes(
            Span evalSpan,
            BraintrustUtils.Parent braintrustParent,
            String braintrustGeneration,
            DatasetCase<?, ?> datasetCase,
            TaskResult<?, ?> taskResult) {
        var spanAttrs = new LinkedHashMap<>();
        spanAttrs.put("type", "eval");
        spanAttrs.put("name", "eval");
        if (braintrustGeneration != null) {
            spanAttrs.put("generation", braintrustGeneration);
        }
        evalSpan.setAttribute(PARENT, braintrustParent.toParentValue())
                .setAttribute("braintrust.span_attributes", toJson(spanAttrs))
                .setAttribute("braintrust.input_json", toJson(datasetCase.input()))
                .setAttribute("braintrust.expected_json", toJson(datasetCase.expected()));

        if (datasetCase.origin().isPresent()) {
            evalSpan.setAttribute("braintrust.origin", toJson(datasetCase.origin().get()));
        }
        if (!datasetCase.tags().isEmpty()) {
            evalSpan.setAttribute(
                    AttributeKey.stringArrayKey("braintrust.tags"), datasetCase.tags());
        }
        if (!datasetCase.metadata().isEmpty()) {
            evalSpan.setAttribute("braintrust.metadata", toJson(datasetCase.metadata()));
        }
        evalSpan.setAttribute("braintrust.output_json", toJson(taskResult.result()));
    }

    /**
     * Sets eval span attributes when the task threw an exception. Similar to {@link
     * #setEvalSpanAttributes} but does not require a TaskResult.
     */
    private void setEvalSpanAttributesForError(
            Span evalSpan,
            BraintrustUtils.Parent braintrustParent,
            String braintrustGeneration,
            DatasetCase<?, ?> datasetCase) {
        var spanAttrs = new LinkedHashMap<>();
        spanAttrs.put("type", "eval");
        spanAttrs.put("name", "eval");
        if (braintrustGeneration != null) {
            spanAttrs.put("generation", braintrustGeneration);
        }
        evalSpan.setAttribute(PARENT, braintrustParent.toParentValue())
                .setAttribute("braintrust.span_attributes", toJson(spanAttrs))
                .setAttribute("braintrust.input_json", toJson(datasetCase.input()))
                .setAttribute("braintrust.expected_json", toJson(datasetCase.expected()));

        if (datasetCase.origin().isPresent()) {
            evalSpan.setAttribute("braintrust.origin", toJson(datasetCase.origin().get()));
        }
        if (!datasetCase.tags().isEmpty()) {
            evalSpan.setAttribute(
                    AttributeKey.stringArrayKey("braintrust.tags"), datasetCase.tags());
        }
        if (!datasetCase.metadata().isEmpty()) {
            evalSpan.setAttribute("braintrust.metadata", toJson(datasetCase.metadata()));
        }
    }

    private void setTaskSpanAttributes(
            Span taskSpan,
            BraintrustUtils.Parent braintrustParent,
            String braintrustGeneration,
            DatasetCase<?, ?> datasetCase,
            TaskResult<?, ?> taskResult) {
        Map<String, Object> taskSpanAttrs = new LinkedHashMap<>();
        taskSpanAttrs.put("type", "task");
        taskSpanAttrs.put("name", "task");
        if (braintrustGeneration != null) {
            taskSpanAttrs.put("generation", braintrustGeneration);
        }

        taskSpan.setAttribute(PARENT, braintrustParent.toParentValue())
                .setAttribute("braintrust.span_attributes", toJson(taskSpanAttrs))
                .setAttribute("braintrust.input_json", toJson(datasetCase.input()))
                .setAttribute("braintrust.expected_json", toJson(datasetCase.expected()))
                .setAttribute("braintrust.output_json", toJson(taskResult.result()));
    }

    private void setScoreSpanAttributes(
            Span scoreSpan,
            BraintrustUtils.Parent braintrustParent,
            String braintrustGeneration,
            String scorerName,
            Map<String, Double> scorerScores) {
        Map<String, Object> scoreSpanAttrs = new LinkedHashMap<>();
        scoreSpanAttrs.put("type", "score");
        scoreSpanAttrs.put("name", scorerName);
        scoreSpanAttrs.put("purpose", "scorer");
        if (braintrustGeneration != null) {
            scoreSpanAttrs.put("generation", braintrustGeneration);
        }

        var scoresJson = toJson(scorerScores);
        scoreSpan
                .setAttribute(PARENT, braintrustParent.toParentValue())
                .setAttribute("braintrust.span_attributes", toJson(scoreSpanAttrs))
                .setAttribute("braintrust.output_json", scoresJson)
                .setAttribute("braintrust.scores", scoresJson);
    }

    /**
     * Runs a scorer against a successful task result. If the scorer throws, falls back to {@link
     * Scorer#scoreForScorerException}.
     */
    private <I, O> void runScorer(
            Tracer tracer,
            Span evalSpan,
            BraintrustUtils.Parent braintrustParent,
            String braintrustGeneration,
            Scorer<I, O> scorer,
            TaskResult<I, O> taskResult,
            Map<String, List<Double>> scoresByName) {
        var scoreSpan = tracer.spanBuilder("score").startSpan();
        try (var unused = Context.current().with(scoreSpan).makeCurrent()) {
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
            recordScores(
                    scoreSpan,
                    braintrustParent,
                    braintrustGeneration,
                    scorer,
                    scores,
                    scoresByName);
        } finally {
            scoreSpan.end();
        }
    }

    /**
     * Runs {@link Scorer#scoreForTaskException} when the task threw. If the fallback throws, the
     * eval aborts.
     */
    private <I, O> void runScoreForTaskException(
            Tracer tracer,
            Span evalSpan,
            BraintrustUtils.Parent braintrustParent,
            String braintrustGeneration,
            Scorer<I, O> scorer,
            Exception taskException,
            DatasetCase<I, O> datasetCase,
            Map<String, List<Double>> scoresByName) {
        var scoreSpan = tracer.spanBuilder("score").startSpan();
        try (var unused = Context.current().with(scoreSpan).makeCurrent()) {
            // if this throws, it propagates and the eval aborts
            var scores = scorer.scoreForTaskException(taskException, datasetCase);
            recordScores(
                    scoreSpan,
                    braintrustParent,
                    braintrustGeneration,
                    scorer,
                    scores,
                    scoresByName);
        } finally {
            scoreSpan.end();
        }
    }

    /** Records scores on the score span and accumulates them into scoresByName. */
    private void recordScores(
            Span scoreSpan,
            BraintrustUtils.Parent braintrustParent,
            String braintrustGeneration,
            Scorer<?, ?> scorer,
            List<Score> scores,
            Map<String, List<Double>> scoresByName) {
        if (scores == null || scores.isEmpty()) {
            return;
        }
        Map<String, Double> scorerScores = new LinkedHashMap<>();
        for (Score score : scores) {
            // Cases score concurrently. computeIfAbsent is atomic but the add() is not, so the
            // list itself has to be synchronized.
            scoresByName
                    .computeIfAbsent(
                            score.name(), k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(score.value());
            scorerScores.put(score.name(), score.value());
        }
        setScoreSpanAttributes(
                scoreSpan, braintrustParent, braintrustGeneration, scorer.getName(), scorerScores);
    }

    private void sendSSEEvent(OutputStream os, String eventType, String data) throws IOException {
        String event = "event: " + eventType + "\n" + "data: " + data + "\n\n";
        // Lock the stream, not the server: concurrent cases of one run must not interleave a
        // partial event, but concurrent *requests* write to different streams and shouldn't
        // serialize against each other (or against the synchronized start()/stop()).
        synchronized (os) {
            os.write(event.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void sendProgressEvent(
            OutputStream os,
            String spanId,
            Optional<Origin> origin,
            String evalName,
            Object taskResult)
            throws IOException {
        Map<String, Object> progressData = new LinkedHashMap<>();
        progressData.put("id", spanId);
        progressData.put("object_type", "task");

        origin.ifPresent(value -> progressData.put("origin", value));
        progressData.put("name", evalName);
        progressData.put("format", "code");
        progressData.put("output_type", "completion");
        progressData.put("event", "json_delta");
        progressData.put("data", toJson(taskResult));

        String progressJson = toJson(progressData);
        sendSSEEvent(os, "progress", progressJson);
    }

    private void sendSummaryEvent(
            OutputStream os,
            String projectName,
            String projectId,
            String experimentName,
            String projectUrl,
            Map<String, EvalResponse.ScoreSummary> scoreSummaries)
            throws IOException {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("projectName", projectName);
        summary.put("projectId", projectId);
        summary.put("experimentId", null);
        summary.put("experimentName", experimentName);
        summary.put("projectUrl", projectUrl);
        summary.put("experimentUrl", null);
        summary.put("comparisonExperimentName", null);

        Map<String, Object> scoresWithMeta = new LinkedHashMap<>();
        for (Map.Entry<String, EvalResponse.ScoreSummary> entry : scoreSummaries.entrySet()) {
            Map<String, Object> scoreData = new LinkedHashMap<>();
            scoreData.put("name", entry.getValue().getName());
            scoreData.put("_longest_score_name", entry.getKey().length());
            scoreData.put("score", entry.getValue().getScore());
            scoreData.put("improvements", entry.getValue().getImprovements());
            scoreData.put("regressions", entry.getValue().getRegressions());
            scoreData.put("diff", null);
            scoresWithMeta.put(entry.getKey(), scoreData);
        }
        summary.put("scores", scoresWithMeta);
        summary.put("metrics", Map.of());

        sendSSEEvent(os, "summary", toJson(summary));
    }

    private void sendDoneEvent(OutputStream os) throws IOException {
        sendSSEEvent(os, "done", "");
    }

    /**
     * Sends the {@code summary} event for an experiment snapshot run. Unlike {@link
     * #sendSummaryEvent} (playground), this references the created experiment via {@code
     * experimentId}/{@code experimentUrl} so the UI can link straight to it, and carries no
     * streamed per-scorer scores (results live on the experiment itself).
     */
    private void sendExperimentSnapshotSummaryEvent(
            OutputStream os,
            String projectName,
            String projectId,
            @Nullable String experimentId,
            String experimentName,
            String projectUrl,
            @Nullable String experimentUrl)
            throws IOException {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("projectName", projectName);
        summary.put("projectId", projectId);
        summary.put("experimentId", experimentId);
        summary.put("experimentName", experimentName);
        summary.put("projectUrl", projectUrl);
        summary.put("experimentUrl", experimentUrl);
        summary.put("comparisonExperimentName", null);
        summary.put("scores", Map.of());
        summary.put("metrics", Map.of());

        sendSSEEvent(os, "summary", toJson(summary));
    }

    /** Sends the terminating {@code done} event for an experiment snapshot run. */
    private void sendExperimentSnapshotDoneEvent(OutputStream os) throws IOException {
        sendSSEEvent(os, "done", "");
    }

    private void sendResponse(
            HttpExchange exchange, int statusCode, String contentType, String body)
            throws IOException {
        byte[] responseBytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    /**
     * Check if the origin is whitelisted for CORS.
     *
     * @param origin The Origin header value
     * @return true if the origin is allowed, false otherwise
     */
    private boolean isOriginAllowed(@Nullable String origin) {
        if (origin == null || origin.isEmpty()) {
            return true; // Allow requests without origin (e.g., same-origin)
        }
        // Check against whitelisted origins
        for (String allowedOrigin : corsOriginWhitelist) {
            if (allowedOrigin != null && allowedOrigin.equals(origin)) {
                return true;
            }
        }
        // Check against preview domain pattern
        return PREVIEW_DOMAIN_PATTERN.matcher(origin).matches();
    }

    /**
     * Apply CORS headers to the response.
     *
     * @param exchange The HTTP exchange
     */
    private void applyCorsHeaders(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");

        if (isOriginAllowed(origin)) {
            var headers = exchange.getResponseHeaders();
            if (origin != null && !origin.isEmpty()) {
                headers.set("Access-Control-Allow-Origin", origin);
            }
            headers.set("Access-Control-Allow-Credentials", "true");
            headers.set("Access-Control-Expose-Headers", EXPOSED_HEADERS);
        }
    }

    /**
     * Handle CORS preflight requests.
     *
     * @param exchange The HTTP exchange
     */
    private void handlePreflightRequest(HttpExchange exchange) throws IOException {
        String origin = exchange.getRequestHeaders().getFirst("Origin");

        if (!isOriginAllowed(origin)) {
            exchange.sendResponseHeaders(403, -1);
            return;
        }

        var headers = exchange.getResponseHeaders();
        if (origin != null && !origin.isEmpty()) {
            headers.set("Access-Control-Allow-Origin", origin);
        }
        headers.set("Access-Control-Allow-Methods", "GET, PATCH, POST, PUT, DELETE, OPTIONS");
        headers.set("Access-Control-Allow-Headers", ALLOWED_HEADERS);
        headers.set("Access-Control-Allow-Credentials", "true");
        headers.set("Access-Control-Max-Age", "86400");

        // Support for Chrome's Private Network Access
        String requestPrivateNetwork =
                exchange.getRequestHeaders().getFirst("Access-Control-Request-Private-Network");
        if ("true".equals(requestPrivateNetwork)) {
            headers.set("Access-Control-Allow-Private-Network", "true");
        }

        exchange.sendResponseHeaders(204, -1);
    }

    /**
     * Wrap a handler with CORS support.
     *
     * @param handler The handler to wrap
     * @return A handler that applies CORS headers
     */
    private HttpHandler withCors(HttpHandler handler) {
        return exchange -> {
            // Handle OPTIONS preflight requests
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                handlePreflightRequest(exchange);
                return;
            }

            // Apply CORS headers to all responses
            applyCorsHeaders(exchange);

            // Delegate to the actual handler
            handler.handle(exchange);
        };
    }

    /**
     * Extract API key from request headers.
     *
     * <p>Checks headers in order of precedence:
     *
     * <ol>
     *   <li>x-bt-auth-token (preferred)
     *   <li>Authorization: Bearer &lt;token&gt;
     *   <li>Authorization: &lt;token&gt;
     * </ol>
     *
     * @param exchange The HTTP exchange
     * @param context The request context (unused but for consistency)
     * @return The API key, or null if not present
     */
    @Nullable
    private String extractApiKey(HttpExchange exchange, RequestContext context) {
        var headers = exchange.getRequestHeaders();

        // 1. Check x-bt-auth-token header (preferred)
        String token = headers.getFirst("x-bt-auth-token");
        if (token != null && !token.isEmpty()) {
            return token;
        }

        // 2. Check Authorization header
        String authHeader = headers.getFirst("Authorization");
        if (authHeader != null && !authHeader.isEmpty()) {
            // Try Bearer format
            if (authHeader.startsWith("Bearer ")) {
                return authHeader.substring(7).trim();
            }
            // Try direct token
            return authHeader.trim();
        }

        return null;
    }

    /**
     * Create a request context with origin.
     *
     * @param exchange The HTTP exchange
     * @return RequestContext with appOrigin
     */
    private RequestContext createRequestContext(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null) {
            origin = "";
        }

        return RequestContext.builder().appOrigin(origin).build();
    }

    /**
     * Get Braintrust state for authenticated requests.
     *
     * <p>Validates that required headers are present and returns a RequestContext with populated
     * Braintrust from cache.
     *
     * <p>Required headers:
     *
     * <ul>
     *   <li>API key (x-bt-auth-token or Authorization)
     *   <li>x-bt-org-name
     *   <li>x-bt-project-id
     * </ul>
     *
     * <p>Cache key format: orgName:projectId:apiKey
     *
     * @param exchange The HTTP exchange
     * @param context The request context
     * @return RequestContext with populated state, or null if required headers are missing
     */
    @Nullable
    private RequestContext getBraintrust(HttpExchange exchange, RequestContext context) {
        // Extract API key
        String apiKey = extractApiKey(exchange, context);
        if (apiKey == null || apiKey.isEmpty()) {
            return null;
        }

        // Get x-bt-org-name header
        String orgName = exchange.getRequestHeaders().getFirst("x-bt-org-name");
        if (orgName == null || orgName.isEmpty()) {
            return null;
        }

        // Get x-bt-project-id header
        String projectId = exchange.getRequestHeaders().getFirst("x-bt-project-id");
        if (projectId == null || projectId.isEmpty()) {
            return null;
        }

        // Create composite cache key: orgName:projectId:apiKey
        String cacheKey = orgName + ":" + projectId + ":" + apiKey;

        // Get from cache or compute if not present
        Braintrust braintrust =
                authCache.getOrCompute(
                        cacheKey,
                        () -> {
                            log.debug(
                                    "Cached login state for org='{}', projectId='{}' (cache"
                                            + " size={})",
                                    orgName,
                                    projectId,
                                    authCache.size());

                            // Build config with hook if present
                            var configBuilder =
                                    BraintrustConfig.builder()
                                            .apiKey(apiKey)
                                            .defaultProjectId(projectId)
                                            .apiUrl(config.apiUrl())
                                            .appUrl(config.appUrl());

                            // Invoke hook if present to allow customization (e.g., enabling
                            // in-memory span export)
                            if (configBuilderHook != null) {
                                configBuilderHook.accept(configBuilder);
                            }

                            var bt = Braintrust.of(configBuilder.build());
                            bt.openApiClient().login(); // validates the API key
                            return bt;
                        });

        log.debug(
                "Retrieved login state for org='{}', projectId='{}' (cache size={})",
                orgName,
                projectId,
                authCache.size());

        // Return context with state populated
        return RequestContext.builder()
                .appOrigin(context.getAppOrigin())
                .token(apiKey)
                .braintrust(braintrust)
                .build();
    }

    /**
     * Send an error response with JSON body.
     *
     * @param exchange The HTTP exchange
     * @param statusCode The HTTP status code
     * @param message The error message
     * @throws IOException if response sending fails
     */
    private void sendErrorResponse(HttpExchange exchange, int statusCode, String message)
            throws IOException {
        Map<String, String> error = Map.of("error", message);
        sendResponse(exchange, statusCode, "application/json", toJson(error));
    }

    /**
     * Container for parent information extracted from eval request.
     *
     * @param braintrustParent The parent specification in "type:id" format (e.g.,
     *     "playground_id:abc123")
     * @param generation The generation identifier from the request
     */
    private record ParentInfo(
            @Nonnull BraintrustUtils.Parent braintrustParent, @Nullable String generation) {}

    /**
     * Extracts the playground parent (and generation) from the eval request, if present.
     *
     * <p>Playground runs send a {@code parent} object carrying {@code object_type}/{@code
     * object_id}; experiment ("snapshot") runs send no such parent (instead {@code experiment_name}
     * + project via headers) and are handled by {@link #handleExperimentSnapshot}. Returns empty
     * for the latter case.
     *
     * @param request The eval request
     * @return the playground {@link ParentInfo} if this is a playground run, else empty
     */
    private static Optional<ParentInfo> extractPlaygroundParent(EvalRequest request) {
        if (!(request.getParent() instanceof Map)) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> parentMap = (Map<String, Object>) request.getParent();
        String objectType = (String) parentMap.get("object_type");
        String objectId = (String) parentMap.get("object_id");
        if (objectType == null && objectId == null) {
            return Optional.empty();
        }

        if (objectType == null || objectId == null) {
            throw new IllegalArgumentException(
                    "malformed braintrust parent: %s, %s".formatted(objectType, objectId));
        }

        // Extract generation from propagated_event.span_attributes.generation
        String generation = null;
        Object propEventObj = parentMap.get("propagated_event");
        if (propEventObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> propEvent = (Map<String, Object>) propEventObj;
            Object spanAttrsObj = propEvent.get("span_attributes");
            if (spanAttrsObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> spanAttrs = (Map<String, Object>) spanAttrsObj;
                generation = (String) spanAttrs.get("generation");
            }
        }

        return Optional.of(
                new ParentInfo(
                        BraintrustUtils.parseParent("playground_id:" + objectId), generation));
    }

    /**
     * Extracts and loads the dataset from the eval request.
     *
     * <p>Supports three methods of loading data:
     *
     * <ol>
     *   <li>Inline data provided in the request
     *   <li>Fetch by project name and dataset name
     *   <li>Fetch by dataset ID
     * </ol>
     *
     * @param request The eval request containing dataset specification
     * @param apiClient The Braintrust API client for fetching datasets
     * @return The loaded dataset
     * @throws IllegalStateException if no dataset specification is provided
     * @throws IllegalArgumentException if dataset or project is not found
     */
    private static <I, O> Dataset<I, O> extractDataset(
            EvalRequest request,
            BraintrustOpenApiClient apiClient,
            Function<Object, I> inputConverter,
            Function<Object, O> outputConverter) {
        EvalRequest.DataSpec dataSpec = request.getData();

        if (dataSpec.getData() != null && !dataSpec.getData().isEmpty()) {
            // Method 1: Inline data
            List<DatasetCase<I, O>> cases = new ArrayList<>();
            for (EvalRequest.EvalCaseData caseData : dataSpec.getData()) {
                DatasetCase<I, O> datasetCase =
                        DatasetCase.of(
                                inputConverter.apply(caseData.getInput()),
                                outputConverter.apply(caseData.getExpected()),
                                caseData.getTags() != null ? caseData.getTags() : List.of(),
                                caseData.getMetadata() != null ? caseData.getMetadata() : Map.of());
                cases.add(datasetCase);
            }
            @SuppressWarnings("unchecked")
            DatasetCase<I, O>[] caseArray = cases.toArray(new DatasetCase[0]);
            return Dataset.of(caseArray);
        } else if (dataSpec.getProjectName() != null && dataSpec.getDatasetName() != null) {
            // Method 2: Fetch by project name and dataset name
            log.debug(
                    "Fetching dataset from Braintrust: project={}, dataset={}",
                    dataSpec.getProjectName(),
                    dataSpec.getDatasetName());
            return Dataset.fetchFromBraintrust(
                    apiClient,
                    dataSpec.getProjectName(),
                    dataSpec.getDatasetName(),
                    null,
                    inputConverter,
                    outputConverter);
        } else if (dataSpec.getDatasetId() != null) {
            // Method 3: Fetch by dataset ID
            log.debug("Fetching dataset from Braintrust by ID: {}", dataSpec.getDatasetId());
            var datasetsApi = new dev.braintrust.openapi.api.DatasetsApi(apiClient);
            var projectsApi = new dev.braintrust.openapi.api.ProjectsApi(apiClient);
            var dataset =
                    datasetsApi.getDatasetId(java.util.UUID.fromString(dataSpec.getDatasetId()));
            var project = projectsApi.getProjectId(dataset.getProjectId());

            String fetchedProjectName = project.getName();
            String fetchedDatasetName = dataset.getName();
            log.debug(
                    "Resolved dataset ID to project={}, dataset={}",
                    fetchedProjectName,
                    fetchedDatasetName);

            return Dataset.fetchFromBraintrust(
                    apiClient,
                    fetchedProjectName,
                    fetchedDatasetName,
                    null,
                    inputConverter,
                    outputConverter);
        } else {
            throw new IllegalStateException("No dataset specification provided");
        }
    }

    /**
     * Resolve a remote scorer from the eval request into a Scorer instance.
     *
     * @param remoteScorer the remote scorer specification from the request
     * @param apiClient the API client to use for invoking the scorer function
     * @return a Scorer that invokes the remote function
     * @throws IllegalArgumentException if the function_id is missing
     */
    private static Scorer<Object, Object> resolveRemoteScorer(
            EvalRequest.RemoteScorer remoteScorer, BraintrustOpenApiClient apiClient) {
        var functionIdSpec = remoteScorer.getFunctionId();

        if (functionIdSpec == null || functionIdSpec.getFunctionId() == null) {
            throw new IllegalArgumentException(
                    "Remote scorer '" + remoteScorer.getName() + "' missing function_id");
        }

        return new ScorerBrainstoreImpl<>(
                apiClient, functionIdSpec.getFunctionId(), functionIdSpec.getVersion());
    }

    public static class Builder {
        private @Nullable BraintrustConfig config = null;
        private String host = "localhost";
        private int port = 8300;
        private @Nullable String orgName = null;
        private List<RemoteEval<?, ?>> evals = new ArrayList<>();
        private @Nullable Consumer<io.opentelemetry.sdk.trace.SdkTracerProviderBuilder>
                traceBuilderHook = null;
        private @Nullable Consumer<BraintrustConfig.Builder> configBuilderHook = null;
        private @Nullable Integer maxConcurrency = null;

        public Devserver build() {
            if (evals.isEmpty()) {
                throw new IllegalStateException("At least one evaluator must be registered");
            }
            if (config == null) {
                throw new IllegalStateException("config is required");
            }
            return new Devserver(this);
        }

        public Builder config(BraintrustConfig config) {
            this.config = config;
            return this;
        }

        /**
         * Sizes the server's eval thread pool, and so how many cases it evaluates at once across
         * all in-flight playground runs and experiment snapshots. When unset, falls back to {@link
         * BraintrustConfig#defaultMaxConcurrency()}.
         *
         * <p>Cases run concurrently, so a {@link RemoteEval}'s task and scorers must be thread-safe
         * — which {@code RemoteEval} already requires. Pass {@code 1} to evaluate cases one at a
         * time.
         */
        public Builder maxConcurrency(int maxConcurrency) {
            if (maxConcurrency < 1) {
                throw new IllegalArgumentException(
                        "maxConcurrency must be at least 1, got " + maxConcurrency);
            }
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        public Builder registerEval(RemoteEval<?, ?> eval) {
            this.evals.add(eval);
            return this;
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * hook to run for each braintrust instance's config created by the devserver. The hook
         * receives the BraintrustConfig.Builder before it's built, allowing customization such as
         * enabling in-memory span export for testing.
         */
        public Builder braintrustConfigBuilderHook(
                Consumer<BraintrustConfig.Builder> configBuilderHook) {
            this.configBuilderHook = configBuilderHook;
            return this;
        }
    }

    private static class NotSupportedYetException extends RuntimeException {
        private final String description;

        public NotSupportedYetException(String description) {
            super("feature not supported yet: " + description);
            this.description = description;
        }
    }
}
