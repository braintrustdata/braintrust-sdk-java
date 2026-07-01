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
import io.opentelemetry.api.trace.Span;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
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

        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newCachedThreadPool());

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
            log.info("Braintrust dev server stopped");
        }
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

                // Combine local scorers from RemoteEval with remote scorers from the request
                List<Scorer<I, O>> allScorers = new ArrayList<>(eval.getScorers());
                allScorers.addAll(remoteScorers);

                var sseListener =
                        new SseEvalListener(
                                os,
                                eval.getName(),
                                projectName,
                                projectId,
                                projectUrl,
                                experimentName);

                var builder =
                        Eval.<I, O>builder()
                                .name(experimentName)
                                .config(braintrust.config())
                                .apiClient(apiClient)
                                .projectId(projectId)
                                .dataset((Dataset<I, O>) extractDataset(request, apiClient))
                                .task(eval.getTask())
                                .scorers(allScorers.toArray(new Scorer[0]))
                                .parameters(eval.getParameters())
                                .parameterValues(
                                        request.getParameters() == null
                                                ? Map.of()
                                                : request.getParameters());

                var playgroundParent = extractPlaygroundParent(request);
                if (playgroundParent.isPresent()) {
                    // Playground run: target a playground_id parent (no experiment is created),
                    // weave the request's generation into span attributes, and use the playground
                    // span decorator in place of the standard one.
                    var pi = playgroundParent.get();
                    // No experiment id -> Eval won't build a BrainstoreTrace (traced scorers get a
                    // null trace). The devserver bypasses tracing this way rather than via a flag.
                    builder.evalTargetProvider(
                                    ctx ->
                                            new EvalRunInfo(
                                                    pi.braintrustParent(),
                                                    pi.generation(),
                                                    null,
                                                    null,
                                                    null))
                            .clearListeners()
                            .addListener(new PlaygroundSpanDecorator());
                } else {
                    // Experiment run (snapshot): create a fresh experiment (ensure_new) and use the
                    // standard span decorator / experiment_id parent — i.e. a plain Eval run.
                    builder.evalTargetProvider(new ExperimentTargetProvider(true));
                }

                // SSE streaming (progress per case, summary + done on completion).
                builder.addListener(sseListener).build().run();
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

    /**
     * An {@link EvalListener} that streams SSE {@code progress} events (one per case, including on
     * task error), accumulates per-scorer averages, and emits the {@code summary} + {@code done}
     * events when the run completes. Span decoration is handled separately by the standard {@link
     * dev.braintrust.eval.EvalSpanDecorator} (experiment runs) or {@link PlaygroundSpanDecorator}
     * (playground runs).
     */
    private final class SseEvalListener implements EvalListener {
        private final OutputStream os;
        private final String evalName;
        private final String projectName;
        private final String projectId;
        private final String projectUrl;
        private final String experimentName;
        private final Map<String, List<Double>> scoresByName = new ConcurrentHashMap<>();

        SseEvalListener(
                OutputStream os,
                String evalName,
                String projectName,
                String projectId,
                String projectUrl,
                String experimentName) {
            this.os = os;
            this.evalName = evalName;
            this.projectName = projectName;
            this.projectId = projectId;
            this.projectUrl = projectUrl;
            this.experimentName = experimentName;
        }

        private Map<String, EvalResponse.ScoreSummary> scoreSummaries() {
            Map<String, EvalResponse.ScoreSummary> scoreSummaries = new LinkedHashMap<>();
            for (var entry : scoresByName.entrySet()) {
                double avgScore =
                        entry.getValue().stream()
                                .mapToDouble(Double::doubleValue)
                                .average()
                                .orElse(0.0);
                scoreSummaries.put(
                        entry.getKey(),
                        EvalResponse.ScoreSummary.builder()
                                .name(entry.getKey())
                                .score(avgScore)
                                .improvements(0)
                                .regressions(0)
                                .build());
            }
            return scoreSummaries;
        }

        @Override
        public RunListener createRunListener(EvalRunInfo info) {
            return new RunListener() {
                @Override
                public CaseListener createCaseListener(DatasetCase<?, ?> datasetCase) {
                    return new SseCaseListener();
                }

                @Override
                public void onRunEnd() {
                    // experimentId/experimentName/experimentUrl are non-null for experiment runs,
                    // null for playground runs. For experiment runs prefer the actual (possibly
                    // deduped) experiment name from the run info so the UI links to the real
                    // experiment.
                    var resolvedExperimentName =
                            info.experimentName() != null ? info.experimentName() : experimentName;
                    try {
                        sendSummaryEvent(
                                os,
                                projectName,
                                projectId,
                                info.experimentId(),
                                resolvedExperimentName,
                                projectUrl,
                                info.experimentUrl(),
                                scoreSummaries());
                        sendDoneEvent(os);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to send summary/done event", e);
                    }
                }
            };
        }

        private final class SseCaseListener implements CaseListener {
            @Override
            public void onTaskSuccess(Span rootSpan, Span taskSpan, TaskResult<?, ?> taskResult) {
                sendProgress(rootSpan, taskResult.datasetCase(), taskResult.result());
            }

            @Override
            public void onTaskError(
                    Span rootSpan, Span taskSpan, DatasetCase<?, ?> datasetCase, Exception error) {
                // Send progress even on error so the Playground can link to the trace.
                sendProgress(rootSpan, datasetCase, null);
            }

            @Override
            public void onScoreResult(
                    Span scoreSpan,
                    Span rootSpan,
                    Scorer<?, ?> scorer,
                    List<Score> scores,
                    @Nullable Exception scoreException) {
                for (var score : scores) {
                    scoresByName
                            .computeIfAbsent(score.name(), k -> new ArrayList<>())
                            .add(score.value());
                }
            }

            private void sendProgress(
                    Span rootSpan, DatasetCase<?, ?> datasetCase, @Nullable Object output) {
                try {
                    sendProgressEvent(
                            os,
                            rootSpan.getSpanContext().getSpanId(),
                            datasetCase.origin(),
                            evalName,
                            output);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to send progress event", e);
                }
            }
        }
    }

    private void sendSSEEvent(OutputStream os, String eventType, String data) throws IOException {
        String event = "event: " + eventType + "\n" + "data: " + data + "\n\n";
        synchronized (this) {
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
            @Nullable String experimentId,
            String experimentName,
            String projectUrl,
            @Nullable String experimentUrl,
            Map<String, EvalResponse.ScoreSummary> scoreSummaries)
            throws IOException {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("projectName", projectName);
        summary.put("projectId", projectId);
        summary.put("experimentId", experimentId);
        summary.put("experimentName", experimentName);
        summary.put("projectUrl", projectUrl);
        summary.put("experimentUrl", experimentUrl);
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
     * object_id}; experiment ("snapshot") runs send no such parent (instead {@code
     * experiment_name}/{@code project_id}) and a fresh experiment is created. Returns empty for the
     * latter case.
     *
     * @param request The eval request
     * @return the playground {@link ParentInfo} if the request is a playground run, else empty
     */
    private static Optional<ParentInfo> extractPlaygroundParent(EvalRequest request) {
        if (!(request.getParent() instanceof Map)) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> parentMap = (Map<String, Object>) request.getParent();
        String objectType = (String) parentMap.get("object_type");
        String objectId = (String) parentMap.get("object_id");
        if (objectType == null || objectId == null) {
            return Optional.empty();
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
    private static Dataset<?, ?> extractDataset(
            EvalRequest request, BraintrustOpenApiClient apiClient) {
        EvalRequest.DataSpec dataSpec = request.getData();

        if (dataSpec.getData() != null && !dataSpec.getData().isEmpty()) {
            // Method 1: Inline data
            List<DatasetCase> cases = new ArrayList<>();
            for (EvalRequest.EvalCaseData caseData : dataSpec.getData()) {
                DatasetCase datasetCase =
                        DatasetCase.of(
                                caseData.getInput(),
                                caseData.getExpected(),
                                caseData.getTags() != null ? caseData.getTags() : List.of(),
                                caseData.getMetadata() != null ? caseData.getMetadata() : Map.of());
                cases.add(datasetCase);
            }
            return Dataset.of(cases.toArray(new DatasetCase[0]));
        } else if (dataSpec.getProjectName() != null && dataSpec.getDatasetName() != null) {
            // Method 2: Fetch by project name and dataset name
            log.debug(
                    "Fetching dataset from Braintrust: project={}, dataset={}",
                    dataSpec.getProjectName(),
                    dataSpec.getDatasetName());
            return Dataset.fetchFromBraintrust(
                    apiClient, dataSpec.getProjectName(), dataSpec.getDatasetName(), null);
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
                    apiClient, fetchedProjectName, fetchedDatasetName, null);
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
