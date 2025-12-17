package dev.braintrust.devserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.braintrust.BraintrustUtils;
import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.eval.Dataset;
import dev.braintrust.eval.DatasetCase;
import dev.braintrust.eval.Score;
import dev.braintrust.trace.BraintrustTracing;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
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

    // Whitelisted origins for CORS
    private static final List<String> WHITELISTED_ORIGINS =
            Arrays.asList(
                    "https://www.braintrust.dev",
                    "https://www.braintrustdata.com",
                    "http://localhost:3000",
                    // TODO: use config for these settings
                    System.getenv("WHITELISTED_ORIGIN"),
                    System.getenv("BRAINTRUST_APP_URL"));

    @Getter
    @Accessors(fluent = true)
    private final String host;

    @Getter
    @Accessors(fluent = true)
    private final int port;

    private final @Nullable String orgName;
    private final Map<String, RemoteEval<?, ?>> evals;
    private @Nullable HttpServer server;
    private static final ObjectMapper JSON_MAPPER =
            new ObjectMapper()
                    .enable(
                            com.fasterxml.jackson.core.JsonParser.Feature
                                    .INCLUDE_SOURCE_IN_LOCATION);

    private Devserver(Builder builder) {
        this.host = builder.host;
        this.port = builder.port;
        this.orgName = builder.orgName;
        Map<String, RemoteEval<?, ?>> evalMap = new HashMap<>();
        for (RemoteEval<?, ?> eval : builder.evals) {
            if (evalMap.containsKey(eval.getName())) {
                throw new IllegalArgumentException("Duplicate evaluator name: " + eval.getName());
            }
            evalMap.put(eval.getName(), eval);
        }
        this.evals = Collections.unmodifiableMap(evalMap);
        if (orgName != null) {
            throw new RuntimeException("TODO: org name filtering not implemented yet");
        }
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

        // TODO: Add authentication check here
        // For now, we'll allow unauthenticated access for local testing

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

        // TODO: Add authentication check here
        // For now, we'll allow unauthenticated access for local testing

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
            log.info("Executing evaluator '{}' with {}", request.getName(), datasetDescription);

            // Check if streaming is requested
            boolean isStreaming = request.getStream() != null && request.getStream();

            if (isStreaming) {
                // SSE streaming response - errors handled inside
                log.info("Starting streaming evaluation for '{}'", request.getName());
                handleStreamingEval(exchange, eval, request);
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
    private EvalResponse executeEval(RemoteEval eval, EvalRequest request) throws Exception {
        // Get or create API client
        BraintrustApiClient apiClient = BraintrustApiClient.of(eval.getConfig());

        // Determine project name and ID
        String projectName = eval.getProjectName();
        var orgAndProject = apiClient.getOrCreateProjectAndOrgInfo(eval.getConfig());
        String projectId = orgAndProject.project().id();

        // Create experiment
        String experimentName =
                request.getExperimentName() != null
                        ? request.getExperimentName()
                        : eval.getName() + "-" + System.currentTimeMillis();

        var experiment =
                apiClient.getOrCreateExperiment(
                        new BraintrustApiClient.CreateExperimentRequest(
                                projectId, experimentName, Optional.empty(), Optional.empty()));

        // Load dataset using one of three methods
        Dataset<?, ?> dataset;
        EvalRequest.DataSpec dataSpec = request.getData();

        if (dataSpec.getBtql() != null) {
            log.warn("TODO: apply btql ordering: {}", dataSpec.getBtql());
        }
        if (dataSpec.getData() != null && !dataSpec.getData().isEmpty()) {
            // Inline data
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
            dataset = Dataset.of(cases.toArray(new DatasetCase[0]));
        } else if (dataSpec.getProjectName() != null && dataSpec.getDatasetName() != null) {
            // Fetch by dataset name
            dataset =
                    Dataset.fetchFromBraintrust(
                            apiClient, dataSpec.getProjectName(), dataSpec.getDatasetName(), null);
        } else if (dataSpec.getDatasetId() != null) {
            // Fetch by dataset ID
            var datasetMetadata = apiClient.getDataset(dataSpec.getDatasetId());
            if (datasetMetadata.isEmpty()) {
                throw new IllegalArgumentException("Dataset not found: " + dataSpec.getDatasetId());
            }

            // Get project info to obtain project name
            var project = apiClient.getProject(datasetMetadata.get().projectId());
            if (project.isEmpty()) {
                throw new IllegalArgumentException(
                        "Project not found: " + datasetMetadata.get().projectId());
            }

            String fetchedProjectName = project.get().name();
            String fetchedDatasetName = datasetMetadata.get().name();

            dataset =
                    Dataset.fetchFromBraintrust(
                            apiClient, fetchedProjectName, fetchedDatasetName, null);
        } else {
            throw new IllegalStateException("No dataset specification provided");
        }
        // Execute task and scorers for each case
        Map<String, List<Double>> scoresByName = new LinkedHashMap<>();
        dataset.forEach(
                datasetCase -> {
                    // Run task
                    var task = eval.getTask();
                    var taskResult = task.apply(datasetCase);
                    // Run scorers
                    var scorers = eval.getScorers();
                    for (Object scorerObj : scorers) {
                        dev.braintrust.eval.Scorer scorer = (dev.braintrust.eval.Scorer) scorerObj;
                        List<Score> scores = scorer.score(taskResult);
                        for (Score score : scores) {
                            scoresByName
                                    .computeIfAbsent(score.name(), k -> new ArrayList<>())
                                    .add(score.value());
                        }
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
                            .improvements(0) // TODO: comparison with baseline
                            .regressions(0) // TODO: comparison with baseline
                            .build());
        }

        // Build response
        String experimentUrl =
                BraintrustUtils.createProjectURI(eval.getConfig().appUrl(), orgAndProject)
                                .toASCIIString()
                        + "/experiments/"
                        + experimentName;
        String projectUrl =
                BraintrustUtils.createProjectURI(eval.getConfig().appUrl(), orgAndProject)
                        .toASCIIString();

        return EvalResponse.builder()
                .experimentName(experimentName)
                .projectName(projectName)
                .projectId(projectId)
                .experimentId(experiment.id())
                .experimentUrl(experimentUrl)
                .projectUrl(projectUrl)
                .comparisonExperimentName(null)
                .scores(scoreSummaries)
                .build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void handleStreamingEval(HttpExchange exchange, RemoteEval eval, EvalRequest request)
            throws Exception {
        // Set SSE headers
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0); // 0 = chunked encoding

        try (OutputStream os = exchange.getResponseBody()) {
            try {
                // Get or create API client
                BraintrustApiClient apiClient = BraintrustApiClient.of(eval.getConfig());

                // Determine project name and ID
                String projectName = eval.getProjectName();
                var orgAndProject = apiClient.getOrCreateProjectAndOrgInfo(eval.getConfig());
                String projectId = orgAndProject.project().id();

                // Generate experiment name (same logic as non-streaming)
                String experimentName =
                        request.getExperimentName() != null
                                ? request.getExperimentName()
                                : eval.getName();

                // Create experiment
                var experiment =
                        apiClient.getOrCreateExperiment(
                                new BraintrustApiClient.CreateExperimentRequest(
                                        projectId,
                                        experimentName,
                                        Optional.empty(),
                                        Optional.empty()));

                log.info("Created experiment: {} ({})", experimentName, experiment.id());

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

                // Build URLs
                String experimentUrl =
                        BraintrustUtils.createProjectURI(eval.getConfig().appUrl(), orgAndProject)
                                        .toASCIIString()
                                + "/experiments/"
                                + experimentName;
                String projectUrl =
                        BraintrustUtils.createProjectURI(eval.getConfig().appUrl(), orgAndProject)
                                .toASCIIString();

                // Python doesn't send start event, so commenting out
                /*
                Map<String, Object> startData = new LinkedHashMap<>();
                startData.put("experimentName", experimentName);
                startData.put("projectName", projectName);
                startData.put("projectId", projectId);
                startData.put("experimentId", experiment.id());
                startData.put("experimentUrl", experimentUrl);
                startData.put("projectUrl", projectUrl);
                startData.put("comparisonExperimentName", null);
                startData.put("scores", Map.of());
                String startJson = JSON_MAPPER.writeValueAsString(startData);
                sendSSEEvent(os, "start", startJson);
                */

                // Load dataset using one of three methods (same logic as executeEval)
                Dataset<?, ?> dataset;
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
                                        caseData.getMetadata() != null
                                                ? caseData.getMetadata()
                                                : Map.of());
                        cases.add(datasetCase);
                    }
                    dataset = Dataset.of(cases.toArray(new DatasetCase[0]));
                } else if (dataSpec.getProjectName() != null && dataSpec.getDatasetName() != null) {
                    // Method 2: Fetch by project name and dataset name
                    log.info(
                            "Fetching dataset from Braintrust: project={}, dataset={}",
                            dataSpec.getProjectName(),
                            dataSpec.getDatasetName());
                    dataset =
                            Dataset.fetchFromBraintrust(
                                    apiClient,
                                    dataSpec.getProjectName(),
                                    dataSpec.getDatasetName(),
                                    null);
                } else if (dataSpec.getDatasetId() != null) {
                    // Method 3: Fetch by dataset ID
                    log.info("Fetching dataset from Braintrust by ID: {}", dataSpec.getDatasetId());
                    var datasetMetadata = apiClient.getDataset(dataSpec.getDatasetId());
                    if (datasetMetadata.isEmpty()) {
                        throw new IllegalArgumentException(
                                "Dataset not found: " + dataSpec.getDatasetId());
                    }

                    var project = apiClient.getProject(datasetMetadata.get().projectId());
                    if (project.isEmpty()) {
                        throw new IllegalArgumentException(
                                "Project not found: " + datasetMetadata.get().projectId());
                    }

                    String fetchedProjectName = project.get().name();
                    String fetchedDatasetName = datasetMetadata.get().name();
                    log.info(
                            "Resolved dataset ID to project={}, dataset={}",
                            fetchedProjectName,
                            fetchedDatasetName);

                    dataset =
                            Dataset.fetchFromBraintrust(
                                    apiClient, fetchedProjectName, fetchedDatasetName, null);
                } else {
                    throw new IllegalStateException("No dataset specification provided");
                }

                // Get tracer for creating spans
                Tracer tracer = BraintrustTracing.getTracer();

                // Execute task and scorers for each case, sending progress events and creating
                // spans
                Map<String, List<Double>> scoresByName = new LinkedHashMap<>();
                int[] caseCount = {0}; // Use array for mutability in lambda
                final java.util.concurrent.atomic.AtomicInteger execCounter =
                        new java.util.concurrent.atomic.AtomicInteger(
                                1); // Start at 1, increment for each span
                final String finalParentSpec = parentSpec; // Make effectively final for lambda
                final String finalGeneration = generation; // Make effectively final for lambda
                if (finalParentSpec == null) {
                    throw new RuntimeException("parent required");
                }

                dataset.forEach(
                        datasetCase -> {
                            caseCount[0]++;
                            log.info("Processing dataset case #{}", caseCount[0]);

                            // Build span attributes with exec_counter and generation (eval span)
                            Map<String, Object> evalSpanAttrs = new LinkedHashMap<>();
                            evalSpanAttrs.put("type", "eval");
                            evalSpanAttrs.put("name", "eval");
                            evalSpanAttrs.put("exec_counter", execCounter.getAndIncrement());
                            if (finalGeneration != null) {
                                evalSpanAttrs.put("generation", finalGeneration);
                            }

                            // Create eval span for this dataset case (matches Eval.java pattern)
                            var evalSpan =
                                    tracer.spanBuilder("eval")
                                            .setNoParent() // each eval case is its own trace
                                            .setSpanKind(SpanKind.CLIENT)
                                            .setAttribute(PARENT, finalParentSpec)
                                            .setAttribute(
                                                    "braintrust.span_attributes",
                                                    json(evalSpanAttrs))
                                            .setAttribute(
                                                    "braintrust.input_json",
                                                    json(Map.of("input", datasetCase.input())))
                                            .setAttribute(
                                                    "braintrust.expected",
                                                    json(datasetCase.expected()))
                                            .startSpan();
                            if (datasetCase.origin().isPresent()) {
                                evalSpan.setAttribute(
                                        "braintrust.origin", json(datasetCase.origin().get()));
                            }
                            if (!datasetCase.tags().isEmpty()) {
                                evalSpan.setAttribute(
                                        AttributeKey.stringArrayKey("braintrust.tags"),
                                        datasetCase.tags());
                            }
                            if (!datasetCase.metadata().isEmpty()) {
                                evalSpan.setAttribute(
                                        "braintrust.metadata", json(datasetCase.metadata()));
                            }

                            try (var rootScope = Context.current().with(evalSpan).makeCurrent()) {
                                final dev.braintrust.eval.TaskResult taskResult;
                                { // run task
                                    // Build task span attributes with exec_counter and generation
                                    Map<String, Object> taskSpanAttrs = new LinkedHashMap<>();
                                    taskSpanAttrs.put("type", "task");
                                    taskSpanAttrs.put("name", "task");
                                    taskSpanAttrs.put(
                                            "exec_counter", execCounter.getAndIncrement());
                                    if (finalGeneration != null) {
                                        taskSpanAttrs.put("generation", finalGeneration);
                                    }

                                    var taskSpan =
                                            tracer.spanBuilder("task")
                                                    .setAttribute(PARENT, finalParentSpec)
                                                    .setAttribute(
                                                            "braintrust.span_attributes",
                                                            json(taskSpanAttrs))
                                                    .startSpan();
                                    try (var unused =
                                            Context.current().with(taskSpan).makeCurrent()) {
                                        var task = eval.getTask();
                                        taskResult = task.apply(datasetCase);
                                        // Send progress event for task completion
                                        Map<String, Object> progressData = new LinkedHashMap<>();
                                        progressData.put(
                                                "id", evalSpan.getSpanContext().getSpanId());
                                        progressData.put("object_type", "task");

                                        // Build origin from dataset case if available
                                        Map<String, Object> origin = new LinkedHashMap<>();
                                        origin.put("object_type", "dataset");
                                        origin.put("object_id", dataSpec.getDatasetId());
                                        if (datasetCase.origin().isPresent()) {
                                            progressData.put("origin", datasetCase.origin().get());
                                        }
                                        progressData.put("name", eval.getName());
                                        progressData.put("format", "code");
                                        progressData.put("output_type", "completion");
                                        progressData.put("event", "json_delta");
                                        // Send just the output value as JSON string, not wrapped in
                                        // an object
                                        progressData.put(
                                                "data",
                                                JSON_MAPPER.writeValueAsString(
                                                        taskResult.result()));
                                        String progressJson =
                                                JSON_MAPPER.writeValueAsString(progressData);
                                        sendSSEEvent(os, "progress", progressJson);
                                    } finally {
                                        taskSpan.end();
                                    }

                                    // Set output on eval span (not task span)
                                    evalSpan.setAttribute(
                                            "braintrust.output_json",
                                            json(Map.of("output", taskResult.result())));
                                }
                                { // run scorers - ONE score span for all scorers (matches
                                    // Eval.java)
                                    // Build score span attributes with exec_counter and generation
                                    Map<String, Object> scoreSpanAttrs = new LinkedHashMap<>();
                                    scoreSpanAttrs.put("type", "score");
                                    scoreSpanAttrs.put("name", "score");
                                    scoreSpanAttrs.put(
                                            "exec_counter", execCounter.getAndIncrement());
                                    if (finalGeneration != null) {
                                        scoreSpanAttrs.put("generation", finalGeneration);
                                    }

                                    var scoreSpan =
                                            tracer.spanBuilder("score")
                                                    .setAttribute(PARENT, finalParentSpec)
                                                    .setAttribute(
                                                            "braintrust.span_attributes",
                                                            json(scoreSpanAttrs))
                                                    .startSpan();
                                    try (var unused =
                                            Context.current().with(scoreSpan).makeCurrent()) {
                                        Map<String, Double> nameToScore = new LinkedHashMap<>();
                                        var scorers = eval.getScorers();
                                        log.info("Running {} scorers", scorers.size());

                                        for (Object scorerObj : scorers) {
                                            dev.braintrust.eval.Scorer scorer =
                                                    (dev.braintrust.eval.Scorer) scorerObj;
                                            List<Score> scores = scorer.score(taskResult);
                                            log.info(
                                                    "Scorer '{}' produced {} scores",
                                                    scorer.getName(),
                                                    scores.size());

                                            for (Score score : scores) {
                                                scoresByName
                                                        .computeIfAbsent(
                                                                score.name(),
                                                                k -> new ArrayList<>())
                                                        .add(score.value());
                                                nameToScore.put(score.name(), score.value());

                                                // Python doesn't send progress events for scorers,
                                                // so commenting out
                                                /*
                                                Map<String, Object> scoreProgressData = new LinkedHashMap<>();
                                                scoreProgressData.put("id", scoreSpan.getSpanContext().getSpanId());
                                                scoreProgressData.put("object_type", "scorer");
                                                scoreProgressData.put("origin", null);
                                                scoreProgressData.put("name", score.name());
                                                scoreProgressData.put("format", "code");
                                                scoreProgressData.put("output_type", "score");
                                                scoreProgressData.put("event", "json_delta");
                                                Map<String, Object> scoreData = Map.of("name", score.name(), "score", score.value());
                                                scoreProgressData.put(
                                                        "data", JSON_MAPPER.writeValueAsString(scoreData));
                                                String scoreProgressJson = JSON_MAPPER.writeValueAsString(scoreProgressData);
                                                sendSSEEvent(os, "progress", scoreProgressJson);
                                                */
                                            }
                                        }

                                        // Set all scores on the single score span
                                        scoreSpan.setAttribute(
                                                "braintrust.scores", json(nameToScore));
                                    } finally {
                                        scoreSpan.end();
                                    }
                                }
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to send progress event", e);
                            } finally {
                                evalSpan.end();
                            }
                        });
                log.info("Completed dataset iteration. Processed {} cases", caseCount[0]);

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

                // Build summary with experiment info
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("projectName", projectName);
                summary.put("projectId", experiment.id());
                summary.put("experimentId", null);
                summary.put("experimentName", experimentName);
                summary.put("projectUrl", experimentUrl);
                summary.put("experimentUrl", null);
                summary.put("comparisonExperimentName", null);

                // Add scores with additional Python-specific fields
                Map<String, Object> scoresWithMeta = new LinkedHashMap<>();
                int longestScoreName = 0;
                for (Map.Entry<String, EvalResponse.ScoreSummary> entry :
                        scoreSummaries.entrySet()) {
                    longestScoreName = Math.max(longestScoreName, entry.getKey().length());
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

                // Send summary event
                sendSSEEvent(os, "summary", JSON_MAPPER.writeValueAsString(summary));

                // Send done event
                sendSSEEvent(os, "done", "");

            } catch (Exception e) {
                // Send error event via SSE
                log.error("Error during streaming evaluation", e);
                try {
                    sendSSEEvent(
                            os, "error", e.getMessage() != null ? e.getMessage() : "Unknown error");
                } catch (IOException ioException) {
                    log.error("Failed to send error event", ioException);
                }
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

    private void sendSSEEvent(OutputStream os, String eventType, String data) throws IOException {
        String event = "event: " + eventType + "\n" + "data: " + data + "\n\n";
        os.write(event.getBytes(StandardCharsets.UTF_8));
        log.info("sent event: \n{}\n", event);
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
    private static boolean isOriginAllowed(@Nullable String origin) {
        if (origin == null || origin.isEmpty()) {
            return true; // Allow requests without origin (e.g., same-origin)
        }

        // Check against whitelisted origins
        for (String allowedOrigin : WHITELISTED_ORIGINS) {
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
    private static void applyCorsHeaders(HttpExchange exchange) {
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
    private static void handlePreflightRequest(HttpExchange exchange) throws IOException {
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
    private static HttpHandler withCors(HttpHandler handler) {
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

    public static class Builder {
        private String host = "localhost";
        private int port = 8300;
        private @Nullable String orgName = null;
        private List<RemoteEval<?, ?>> evals = new ArrayList<>();

        public Devserver build() {
            if (evals.isEmpty()) {
                throw new IllegalStateException("At least one evaluator must be registered");
            }
            return new Devserver(this);
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

        public Builder orgName(String orgName) {
            this.orgName = orgName;
            return this;
        }
    }

    private static class NotSupportedYetException extends RuntimeException {
        private final String description;

        public NotSupportedYetException(String description) {
            super(description);
            this.description = description;
        }
    }
}
