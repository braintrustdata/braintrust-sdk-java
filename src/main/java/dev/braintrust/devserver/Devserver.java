package dev.braintrust.devserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.braintrust.Braintrust;
import dev.braintrust.BraintrustUtils;
import dev.braintrust.Origin;
import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.eval.*;
import dev.braintrust.trace.BraintrustContext;
import dev.braintrust.trace.BraintrustTracing;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
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
    private final @Nullable Consumer<io.opentelemetry.sdk.trace.SdkTracerProviderBuilder>
            traceBuilderHook;
    private final @Nullable Consumer<BraintrustConfig.Builder> configBuilderHook;
    private static final ObjectMapper JSON_MAPPER =
            new ObjectMapper()
                    .enable(
                            com.fasterxml.jackson.core.JsonParser.Feature
                                    .INCLUDE_SOURCE_IN_LOCATION);

    // LRU cache for token -> Braintrust mappings (max 32 entries as per api.md)
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

                Map<String, Map<String, Object>> parametersMap = new LinkedHashMap<>();
                for (Map.Entry<String, RemoteEval.Parameter> paramEntry :
                        eval.getParameters().entrySet()) {
                    String paramName = paramEntry.getKey();
                    RemoteEval.Parameter param = paramEntry.getValue();

                    Map<String, Object> paramMetadata = new LinkedHashMap<>();
                    paramMetadata.put("type", param.getType().getValue());

                    if (param.getDescription() != null) {
                        paramMetadata.put("description", param.getDescription());
                    }

                    if (param.getDefaultValue() != null) {
                        paramMetadata.put("default", param.getDefaultValue());
                    }

                    // Only include schema for data type parameters
                    if (param.getType() == RemoteEval.ParameterType.DATA
                            && param.getSchema() != null) {
                        paramMetadata.put("schema", param.getSchema());
                    }

                    parametersMap.put(paramName, paramMetadata);
                }
                metadata.put("parameters", parametersMap);

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

            String jsonResponse = JSON_MAPPER.writeValueAsString(response);
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
            EvalRequest request = JSON_MAPPER.readValue(requestBodyString, EvalRequest.class);

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

            // TODO: support remote scorers

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
                handleStreamingEval(exchange, eval, request, context);
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
    private void handleStreamingEval(
            HttpExchange exchange, RemoteEval eval, EvalRequest request, RequestContext context)
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
                BraintrustApiClient apiClient = braintrust.apiClient();

                // Determine project name and ID from the authenticated Braintrust instance
                final var orgAndProject =
                        apiClient.getOrCreateProjectAndOrgInfo(braintrust.config());
                final var projectName = orgAndProject.project().name();
                final var projectId = orgAndProject.project().id();
                final var experimentName =
                        request.getExperimentName() != null
                                ? request.getExperimentName()
                                : eval.getName();
                final var experimentUrl =
                        BraintrustUtils.createProjectURI(
                                                braintrust.config().appUrl(), orgAndProject)
                                        .toASCIIString()
                                + "/experiments/"
                                + experimentName;
                final var projectUrl =
                        BraintrustUtils.createProjectURI(
                                        braintrust.config().appUrl(), orgAndProject)
                                .toASCIIString();

                var tracer = BraintrustTracing.getTracer();

                // Execute task and scorers for each case
                final Map<String, List<Double>> scoresByName = new ConcurrentHashMap<>();
                final var parentInfo = extractParentInfo(request);
                final var braintrustParent = parentInfo.braintrustParent();
                final var braintrustGeneration = parentInfo.generation();

                extractDataset(request, apiClient)
                        .forEach(
                                datasetCase -> {
                                    var evalSpan =
                                            tracer.spanBuilder("eval")
                                                    .setNoParent()
                                                    .setSpanKind(SpanKind.CLIENT)
                                                    .setAttribute(
                                                            PARENT,
                                                            braintrustParent.toParentValue())
                                                    .startSpan();
                                    Context evalContext = Context.current().with(evalSpan);
                                    evalContext =
                                            BraintrustContext.setParentInBaggage(
                                                    evalContext,
                                                    braintrustParent.type(),
                                                    braintrustParent.id());
                                    // Make the eval context (with span and baggage) current
                                    try (var rootScope = evalContext.makeCurrent()) {
                                        final dev.braintrust.eval.TaskResult taskResult;
                                        { // run task
                                            var taskSpan = tracer.spanBuilder("task").startSpan();
                                            try (var unused =
                                                    Context.current()
                                                            .with(taskSpan)
                                                            .makeCurrent()) {
                                                var task = eval.getTask();
                                                taskResult = task.apply(datasetCase);
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
                                            // setting eval span attributes here because we need the
                                            // task output
                                            setEvalSpanAttributes(
                                                    evalSpan,
                                                    braintrustParent,
                                                    braintrustGeneration,
                                                    datasetCase,
                                                    taskResult);
                                        }
                                        // run scorers - one score span per scorer
                                        for (var scorer : (List<Scorer<?, ?>>) eval.getScorers()) {
                                            var scoreSpan = tracer.spanBuilder("score").startSpan();
                                            try (var unused =
                                                    Context.current()
                                                            .with(scoreSpan)
                                                            .makeCurrent()) {
                                                List<Score> scores = scorer.score(taskResult);

                                                Map<String, Double> scorerScores =
                                                        new LinkedHashMap<>();
                                                for (Score score : scores) {
                                                    scoresByName
                                                            .computeIfAbsent(
                                                                    score.name(),
                                                                    k -> new ArrayList<>())
                                                            .add(score.value());
                                                    scorerScores.put(score.name(), score.value());
                                                }
                                                // Set score span attributes before ending span
                                                setScoreSpanAttributes(
                                                        scoreSpan,
                                                        braintrustParent,
                                                        braintrustGeneration,
                                                        scorer.getName(),
                                                        scorerScores);
                                            } finally {
                                                scoreSpan.end();
                                            }
                                        }
                                    } catch (IOException e) {
                                        throw new RuntimeException(
                                                "Failed to send progress event", e);
                                    } finally {
                                        evalSpan.end();
                                    }
                                });

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
                        os,
                        projectName,
                        projectId,
                        experimentName,
                        projectUrl,
                        experimentUrl,
                        scoreSummaries);
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
                throw e;
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
                .setAttribute("braintrust.span_attributes", json(spanAttrs))
                .setAttribute("braintrust.input_json", json(Map.of("input", datasetCase.input())))
                .setAttribute("braintrust.expected_json", json(datasetCase.expected()));

        if (datasetCase.origin().isPresent()) {
            evalSpan.setAttribute("braintrust.origin", json(datasetCase.origin().get()));
        }
        if (!datasetCase.tags().isEmpty()) {
            evalSpan.setAttribute(
                    AttributeKey.stringArrayKey("braintrust.tags"), datasetCase.tags());
        }
        if (!datasetCase.metadata().isEmpty()) {
            evalSpan.setAttribute("braintrust.metadata", json(datasetCase.metadata()));
        }
        evalSpan.setAttribute(
                "braintrust.output_json", json(Map.of("output", taskResult.result())));
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
                .setAttribute("braintrust.span_attributes", json(taskSpanAttrs))
                .setAttribute("braintrust.input_json", json(Map.of("input", datasetCase.input())))
                .setAttribute(
                        "braintrust.output_json", json(Map.of("output", taskResult.result())));
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
        if (braintrustGeneration != null) {
            scoreSpanAttrs.put("generation", braintrustGeneration);
        }

        scoreSpan
                .setAttribute(PARENT, braintrustParent.toParentValue())
                .setAttribute("braintrust.span_attributes", json(scoreSpanAttrs))
                .setAttribute("braintrust.output_json", json(scorerScores));
    }

    private void sendSSEEvent(OutputStream os, String eventType, String data) throws IOException {
        String event = "event: " + eventType + "\n" + "data: " + data + "\n\n";
        os.write(event.getBytes(StandardCharsets.UTF_8));
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
        progressData.put("data", JSON_MAPPER.writeValueAsString(taskResult));

        String progressJson = JSON_MAPPER.writeValueAsString(progressData);
        sendSSEEvent(os, "progress", progressJson);
    }

    private void sendSummaryEvent(
            OutputStream os,
            String projectName,
            String projectId,
            String experimentName,
            String projectUrl,
            String experimentUrl,
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

        sendSSEEvent(os, "summary", JSON_MAPPER.writeValueAsString(summary));
    }

    private void sendDoneEvent(OutputStream os) throws IOException {
        sendSSEEvent(os, "done", "");
    }

    private String json(Object o) {
        try {
            return JSON_MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
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
                            // Cache miss - would validate token with Braintrust API here
                            // TODO: Implement actual token validation with
                            // loginToState(token, orgName)
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

                            return Braintrust.of(configBuilder.build());
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

    private OpenTelemetry createOpenTelemetry(Braintrust braintrust) {
        var tracerBuilder = SdkTracerProvider.builder();
        var loggerBuilder = SdkLoggerProvider.builder();
        var meterBuilder = SdkMeterProvider.builder();
        var contextPropagator =
                ContextPropagators.create(
                        TextMapPropagator.composite(
                                W3CTraceContextPropagator.getInstance(),
                                W3CBaggagePropagator.getInstance()));
        braintrust.openTelemetryEnable(tracerBuilder, loggerBuilder, meterBuilder);

        // Invoke hook if present to allow customization (e.g., adding span processors)
        if (traceBuilderHook != null) {
            traceBuilderHook.accept(tracerBuilder);
        }

        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerBuilder.build())
                .setLoggerProvider(loggerBuilder.build())
                .setMeterProvider(meterBuilder.build())
                .setPropagators(contextPropagator)
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
        String json = JSON_MAPPER.writeValueAsString(error);
        sendResponse(exchange, statusCode, "application/json", json);
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
     * Extracts parent information from the eval request.
     *
     * @param request The eval request
     * @return ParentInfo containing braintrustParent and generation
     */
    private static ParentInfo extractParentInfo(EvalRequest request) {
        String parentSpec = null;
        String generation = null;

        // Extract parent spec and generation from request
        if (request.getParent() != null && request.getParent() instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> parentMap = (Map<String, Object>) request.getParent();
            String objectType = (String) parentMap.get("object_type");
            String objectId = (String) parentMap.get("object_id");

            // Extract generation from propagated_event.span_attributes.generation
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

            if (objectType != null && objectId != null) {
                parentSpec = "playground_id:" + objectId;
            }
        }

        if (parentSpec == null) {
            throw new IllegalArgumentException("braintrust parent (playground_id) not found");
        }
        return new ParentInfo(BraintrustUtils.parseParent(parentSpec), generation);
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
            EvalRequest request, BraintrustApiClient apiClient) {
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
            var datasetMetadata = apiClient.getDataset(dataSpec.getDatasetId());
            if (datasetMetadata.isEmpty()) {
                throw new IllegalArgumentException("Dataset not found: " + dataSpec.getDatasetId());
            }

            var project = apiClient.getProject(datasetMetadata.get().projectId());
            if (project.isEmpty()) {
                throw new IllegalArgumentException(
                        "Project not found: " + datasetMetadata.get().projectId());
            }

            String fetchedProjectName = project.get().name();
            String fetchedDatasetName = datasetMetadata.get().name();
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
