package dev.braintrust.api;

import static dev.braintrust.json.BraintrustJsonMapper.fromJson;
import static dev.braintrust.json.BraintrustJsonMapper.toJson;

import dev.braintrust.config.BraintrustConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * Provides the necessary API calls for the Braintrust SDK. Users of the SDK should favor using
 * {@link dev.braintrust.eval.Eval} or {@link dev.braintrust.trace.BraintrustTracing}
 */
public interface BraintrustApiClient {
    /**
     * Attempt Braintrust login
     *
     * @return LoginResponse containing organization info
     * @throws LoginException if login fails due to invalid credentials or network errors
     */
    LoginResponse login() throws LoginException;

    /** Creates or gets a project by name. */
    Project getOrCreateProject(String projectName);

    /** Gets a project by ID. */
    Optional<Project> getProject(String projectId);

    /** Creates an experiment. */
    Experiment getOrCreateExperiment(CreateExperimentRequest request);

    /** Lists experiments for a project. */
    List<Experiment> listExperiments(String projectId);

    /** Get project and org info for the default project ID */
    Optional<OrganizationAndProjectInfo> getProjectAndOrgInfo();

    /** Get project and org info for the given project ID */
    Optional<OrganizationAndProjectInfo> getProjectAndOrgInfo(String projectId);

    // TODO: cache project+org info?
    /** Get project and org info for the given config. Creating them if necessary */
    OrganizationAndProjectInfo getOrCreateProjectAndOrgInfo(BraintrustConfig config);

    /** Get a prompt by slug and optional version */
    Optional<Prompt> getPrompt(
            @Nonnull String projectName, @Nonnull String slug, @Nullable String version);

    /** Fetch dataset events with pagination */
    DatasetFetchResponse fetchDatasetEvents(String datasetId, DatasetFetchRequest request);

    /** Get dataset metadata by ID */
    Optional<Dataset> getDataset(String datasetId);

    /** Query datasets by project name and dataset name */
    List<Dataset> queryDatasets(String projectName, String datasetName);

    /**
     * Get a function by project name and slug, with optional version.
     *
     * @param projectName the name of the project containing the function
     * @param slug the unique slug identifier for the function
     * @param version optional version identifier (transaction id or version string)
     * @return the function if found
     */
    Optional<Function> getFunction(
            @Nonnull String projectName, @Nonnull String slug, @Nullable String version);

    /**
     * Get a function by its ID.
     *
     * @param functionId the ID of the function
     * @return the function if found
     */
    Optional<Function> getFunctionById(@Nonnull String functionId);

    /**
     * Invoke a function (scorer, prompt, or tool) by its ID.
     *
     * @param functionId the ID of the function to invoke
     * @param request the invocation request containing input, expected output, etc.
     * @return the result of the function invocation
     */
    Object invokeFunction(@Nonnull String functionId, @Nonnull FunctionInvokeRequest request);

    /**
     * Execute a BTQL (Braintrust Query Language) query. Supports both BTQL pipe syntax and standard
     * SQL syntax.
     *
     * @param query the BTQL/SQL query string
     * @return the query result containing rows of data
     */
    BtqlQueryResponse btqlQuery(@Nonnull String query);

    static BraintrustApiClient of(BraintrustConfig config) {
        return new HttpImpl(config);
    }

    @Slf4j
    class HttpImpl implements BraintrustApiClient {
        private final BraintrustConfig config;
        private final HttpClient httpClient;

        HttpImpl(BraintrustConfig config) {
            this(config, createDefaultHttpClient(config));
        }

        private HttpImpl(BraintrustConfig config, HttpClient httpClient) {
            this.config = config;
            this.httpClient = httpClient;
        }

        @Override
        public Project getOrCreateProject(String projectName) {
            try {
                var request = new CreateProjectRequest(projectName);
                return postAsync("/v1/project", request, Project.class).get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Optional<Project> getProject(String projectId) {
            try {
                return getAsync("/v1/project/" + projectId, Project.class)
                        .handle(
                                (project, error) -> {
                                    if (error != null && isNotFound(error)) {
                                        return Optional.<Project>empty();
                                    }
                                    if (error != null) {
                                        throw new CompletionException(error);
                                    }
                                    return Optional.of(project);
                                })
                        .get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Experiment getOrCreateExperiment(CreateExperimentRequest request) {
            try {
                return postAsync("/v1/experiment", request, Experiment.class).get();
            } catch (InterruptedException | ExecutionException e) {
                throw new ApiException(e);
            }
        }

        @Override
        public List<Experiment> listExperiments(String projectId) {
            try {
                return getAsync("/v1/experiment?project_id=" + projectId, ExperimentList.class)
                        .get()
                        .objects();
            } catch (InterruptedException | ExecutionException e) {
                throw new ApiException(e);
            }
        }

        @Override
        public LoginResponse login() throws LoginException {
            try {
                return postAsync(
                                "/api/apikey/login",
                                new LoginRequest(config.apiKey()),
                                LoginResponse.class)
                        .get();
            } catch (InterruptedException | ExecutionException e) {
                throw new LoginException("Failed to login to Braintrust", e);
            }
        }

        @Override
        public Optional<OrganizationAndProjectInfo> getProjectAndOrgInfo() {
            var projectId = config.defaultProjectId().orElse(null);
            if (null == projectId) {
                projectId = getOrCreateProject(config.defaultProjectName().orElseThrow()).id();
            }
            return getProjectAndOrgInfo(projectId);
        }

        @Override
        public Optional<OrganizationAndProjectInfo> getProjectAndOrgInfo(String projectId) {
            var project = getProject(projectId).orElse(null);
            if (null == project) {
                return Optional.empty();
            }
            OrganizationInfo orgInfo = null;
            for (var org : login().orgInfo()) {
                if (project.orgId().equalsIgnoreCase(org.id())) {
                    orgInfo = org;
                    break;
                }
            }
            if (null == orgInfo) {
                throw new ApiException(
                        "Should not happen. Unable to find project's org: " + project.orgId());
            }
            return Optional.of(new OrganizationAndProjectInfo(orgInfo, project));
        }

        @Override
        public OrganizationAndProjectInfo getOrCreateProjectAndOrgInfo(BraintrustConfig config) {
            // Get or create project based on config
            Project project;
            if (config.defaultProjectId().isPresent()) {
                var projectId = config.defaultProjectId().get();
                project =
                        getProject(projectId)
                                .orElseThrow(
                                        () ->
                                                new ApiException(
                                                        "Project with ID '"
                                                                + projectId
                                                                + "' not found"));
            } else if (config.defaultProjectName().isPresent()) {
                var projectName = config.defaultProjectName().get();
                project = getOrCreateProject(projectName);
            } else {
                throw new ApiException(
                        "Either project ID or project name must be provided in config");
            }

            // Fetch organization info
            OrganizationInfo orgInfo = null;
            for (var org : login().orgInfo()) {
                if (project.orgId().equalsIgnoreCase(org.id())) {
                    orgInfo = org;
                    break;
                }
            }
            if (null == orgInfo) {
                throw new ApiException("Unable to find organization for project: " + project.id());
            }

            return new OrganizationAndProjectInfo(orgInfo, project);
        }

        @Override
        public Optional<Prompt> getPrompt(
                @Nonnull String projectName, @Nonnull String slug, @Nullable String version) {
            Objects.requireNonNull(projectName, slug);
            try {
                var uriBuilder = new StringBuilder(config.apiUrl() + "/v1/prompt?");

                if (!slug.isEmpty()) {
                    uriBuilder.append("slug=").append(slug);
                }

                if (!projectName.isEmpty()) {
                    if (uriBuilder.charAt(uriBuilder.length() - 1) != '?') {
                        uriBuilder.append("&");
                    }
                    uriBuilder.append("project_name=").append(projectName);
                }

                if (version != null && !version.isEmpty()) {
                    if (uriBuilder.charAt(uriBuilder.length() - 1) != '?') {
                        uriBuilder.append("&");
                    }
                    uriBuilder.append("version=").append(version);
                }

                PromptListResponse response =
                        getAsync(
                                        uriBuilder.toString().replace(config.apiUrl(), ""),
                                        PromptListResponse.class)
                                .get();

                if (response.objects() == null || response.objects().isEmpty()) {
                    return Optional.empty();
                }

                if (response.objects().size() > 1) {
                    throw new ApiException(
                            "Multiple objects found for slug: "
                                    + slug
                                    + ", projectName: "
                                    + projectName);
                }

                return Optional.of(response.objects().get(0));
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public DatasetFetchResponse fetchDatasetEvents(
                String datasetId, DatasetFetchRequest request) {
            try {
                String path = "/v1/dataset/" + datasetId + "/fetch";
                return postAsync(path, request, DatasetFetchResponse.class).get();
            } catch (InterruptedException | ExecutionException e) {
                throw new ApiException(e);
            }
        }

        @Override
        public Optional<Dataset> getDataset(String datasetId) {
            try {
                return getAsync("/v1/dataset/" + datasetId, Dataset.class)
                        .handle(
                                (dataset, error) -> {
                                    if (error != null && isNotFound(error)) {
                                        return Optional.<Dataset>empty();
                                    }
                                    if (error != null) {
                                        throw new CompletionException(error);
                                    }
                                    return Optional.of(dataset);
                                })
                        .get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public List<Dataset> queryDatasets(String projectName, String datasetName) {
            try {
                String path =
                        "/v1/dataset?project_name=" + projectName + "&dataset_name=" + datasetName;
                DatasetList response = getAsync(path, DatasetList.class).get();
                return response.objects();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Optional<Function> getFunction(
                @Nonnull String projectName, @Nonnull String slug, @Nullable String version) {
            Objects.requireNonNull(projectName, "projectName must not be null");
            Objects.requireNonNull(slug, "slug must not be null");
            try {
                var uriBuilder = new StringBuilder("/v1/function?");
                uriBuilder.append("slug=").append(slug);
                uriBuilder.append("&project_name=").append(projectName);

                if (version != null && !version.isEmpty()) {
                    uriBuilder.append("&version=").append(version);
                }

                FunctionListResponse response =
                        getAsync(uriBuilder.toString(), FunctionListResponse.class).get();

                if (response.objects() == null || response.objects().isEmpty()) {
                    return Optional.empty();
                }

                if (response.objects().size() > 1) {
                    throw new ApiException(
                            "Multiple functions found for slug: "
                                    + slug
                                    + ", projectName: "
                                    + projectName);
                }

                return Optional.of(response.objects().get(0));
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Optional<Function> getFunctionById(@Nonnull String functionId) {
            Objects.requireNonNull(functionId, "functionId must not be null");
            try {
                String path = "/v1/function/" + functionId;
                return Optional.of(getAsync(path, Function.class).get());
            } catch (InterruptedException | ExecutionException e) {
                if (e.getCause() instanceof ApiException apiEx
                        && apiEx.getMessage().contains("404")) {
                    return Optional.empty();
                }
                throw new RuntimeException(e);
            }
        }

        @Override
        public Object invokeFunction(
                @Nonnull String functionId, @Nonnull FunctionInvokeRequest request) {
            Objects.requireNonNull(functionId, "functionId must not be null");
            Objects.requireNonNull(request, "request must not be null");
            try {
                String path = "/v1/function/" + functionId + "/invoke";
                return postAsync(path, request, Object.class).get();
            } catch (InterruptedException | ExecutionException e) {
                throw new ApiException("Failed to invoke function: " + functionId, e);
            }
        }

        @Override
        public BtqlQueryResponse btqlQuery(@Nonnull String query) {
            Objects.requireNonNull(query, "query must not be null");
            try {
                var request = new BtqlQueryRequest(query);
                return postAsync("/btql", request, BtqlQueryResponse.class).get();
            } catch (InterruptedException | ExecutionException e) {
                throw new ApiException("Failed to execute BTQL query", e);
            }
        }

        private <T> CompletableFuture<T> getAsync(String path, Class<T> responseType) {
            var request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(config.apiUrl() + path))
                            .header("Authorization", "Bearer " + config.apiKey())
                            .header("Accept", "application/json")
                            .timeout(config.requestTimeout())
                            .GET()
                            .build();

            return sendAsync(request, responseType);
        }

        private <T> CompletableFuture<T> postAsync(
                String path, Object body, Class<T> responseType) {
            var jsonBody = toJson(body);

            var request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(config.apiUrl() + path))
                            .header("Authorization", "Bearer " + config.apiKey())
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .timeout(config.requestTimeout())
                            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                            .build();

            return sendAsync(request, responseType);
        }

        private <T> CompletableFuture<T> sendAsync(HttpRequest request, Class<T> responseType) {
            log.debug("API Request: {} {}", request.method(), request.uri());

            return httpClient
                    .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> handleResponse(response, responseType));
        }

        private <T> T handleResponse(HttpResponse<String> response, Class<T> responseType) {
            log.debug("API Response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return fromJson(response.body(), responseType);
            } else {
                log.warn(
                        "API request failed with status {}: {}",
                        response.statusCode(),
                        response.body());
                throw new ApiException(
                        String.format(
                                "API request failed with status %d: %s",
                                response.statusCode(), response.body()));
            }
        }

        private boolean isNotFound(Throwable error) {
            // Unwrap CompletionException if present
            Throwable cause = error;
            if (error instanceof CompletionException && error.getCause() != null) {
                cause = error.getCause();
            }

            if (cause instanceof ApiException) {
                return ((ApiException) cause).getMessage().contains("404");
            }
            return false;
        }

        private static HttpClient createDefaultHttpClient(BraintrustConfig config) {
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        }
    }

    /** Implementation for test doubling */
    @Slf4j
    class InMemoryImpl implements BraintrustApiClient {
        private final List<OrganizationAndProjectInfo> organizationAndProjectInfos;
        private final Set<Experiment> experiments =
                Collections.newSetFromMap(new ConcurrentHashMap<>());
        private final List<Prompt> prompts = new ArrayList<>();
        private final List<Function> functions = new ArrayList<>();
        private final Map<String, java.util.function.Function<FunctionInvokeRequest, Object>>
                functionInvokers = new ConcurrentHashMap<>();

        public InMemoryImpl(OrganizationAndProjectInfo... organizationAndProjectInfos) {
            this.organizationAndProjectInfos =
                    new ArrayList<>(List.of(organizationAndProjectInfos));
        }

        public InMemoryImpl(
                List<OrganizationAndProjectInfo> organizationAndProjectInfos,
                List<Prompt> prompts) {
            this.organizationAndProjectInfos = new ArrayList<>(organizationAndProjectInfos);
            this.prompts.addAll(prompts);
        }

        @Override
        public LoginResponse login() {
            return new LoginResponse(
                    organizationAndProjectInfos.stream().map(o -> o.orgInfo).toList());
        }

        @Override
        public Project getOrCreateProject(String projectName) {
            // Find existing project by name
            for (var orgAndProject : organizationAndProjectInfos) {
                if (orgAndProject.project().name().equals(projectName)) {
                    return orgAndProject.project();
                }
            }

            // Create new project if not found
            var defaultOrgInfo =
                    organizationAndProjectInfos.isEmpty()
                            ? new OrganizationInfo("default-org-id", "Default Organization")
                            : organizationAndProjectInfos.get(0).orgInfo();

            var newProject =
                    new Project(
                            "project-" + UUID.randomUUID().toString(),
                            projectName,
                            defaultOrgInfo.id(),
                            java.time.Instant.now().toString(),
                            java.time.Instant.now().toString());

            organizationAndProjectInfos.add(
                    new OrganizationAndProjectInfo(defaultOrgInfo, newProject));
            return newProject;
        }

        @Override
        public Optional<Project> getProject(String projectId) {
            return organizationAndProjectInfos.stream()
                    .map(OrganizationAndProjectInfo::project)
                    .filter(project -> project.id().equals(projectId))
                    .findFirst();
        }

        @Override
        public Experiment getOrCreateExperiment(CreateExperimentRequest request) {
            var existing =
                    experiments.stream()
                            .filter(exp -> exp.name().equals(request.name()))
                            .findFirst();
            if (existing.isPresent()) {
                return existing.get();
            }
            var newExperiment =
                    new Experiment(
                            request.name().hashCode() + "",
                            request.projectId(),
                            request.name(),
                            request.description(),
                            request.tags().orElse(List.of()),
                            request.metadata().orElse(Map.of()),
                            "notused",
                            "notused");
            experiments.add(newExperiment);
            return newExperiment;
        }

        @Override
        public List<Experiment> listExperiments(String projectId) {
            return experiments.stream().filter(exp -> exp.projectId().equals(projectId)).toList();
        }

        @Override
        public Optional<OrganizationAndProjectInfo> getProjectAndOrgInfo() {
            return organizationAndProjectInfos.isEmpty()
                    ? Optional.empty()
                    : Optional.of(organizationAndProjectInfos.get(0));
        }

        @Override
        public Optional<OrganizationAndProjectInfo> getProjectAndOrgInfo(String projectId) {
            return organizationAndProjectInfos.stream()
                    .filter(orgAndProject -> orgAndProject.project().id().equals(projectId))
                    .findFirst();
        }

        @Override
        public OrganizationAndProjectInfo getOrCreateProjectAndOrgInfo(BraintrustConfig config) {
            // Get or create project based on config
            Project project;
            if (config.defaultProjectId().isPresent()) {
                var projectId = config.defaultProjectId().get();
                project =
                        getProject(projectId)
                                .orElseThrow(
                                        () ->
                                                new ApiException(
                                                        "Project with ID '"
                                                                + projectId
                                                                + "' not found"));
            } else if (config.defaultProjectName().isPresent()) {
                var projectName = config.defaultProjectName().get();
                project = getOrCreateProject(projectName);
            } else {
                throw new ApiException(
                        "Either project ID or project name must be provided in config");
            }

            // Find the organization info for this project
            return organizationAndProjectInfos.stream()
                    .filter(info -> info.project().id().equals(project.id()))
                    .findFirst()
                    .orElseThrow(
                            () ->
                                    new ApiException(
                                            "Unable to find organization for project: "
                                                    + project.id()));
        }

        @Override
        public Optional<Prompt> getPrompt(
                @Nonnull String projectName, @Nonnull String slug, @Nullable String version) {
            Objects.requireNonNull(projectName, slug);
            List<Prompt> matchingPrompts =
                    prompts.stream()
                            .filter(
                                    prompt -> {
                                        // Filter by slug if provided
                                        if (slug != null && !slug.isEmpty()) {
                                            if (!prompt.slug().equals(slug)) {
                                                return false;
                                            }
                                        }

                                        // Filter by project name if provided
                                        if (projectName != null && !projectName.isEmpty()) {
                                            // Find project by name and check if ID matches
                                            Project project = getOrCreateProject(projectName);
                                            if (!prompt.projectId().equals(project.id())) {
                                                return false;
                                            }
                                        }

                                        // Filter by version if provided
                                        // Note: Version filtering would require additional metadata
                                        // on Prompt
                                        // For now, we'll skip this as Prompt doesn't have a
                                        // version field

                                        return true;
                                    })
                            .toList();

            if (matchingPrompts.isEmpty()) {
                return Optional.empty();
            }

            if (matchingPrompts.size() > 1) {
                throw new ApiException(
                        "Multiple objects found for slug: "
                                + slug
                                + ", projectName: "
                                + projectName);
            }

            return Optional.of(matchingPrompts.get(0));
        }

        // Will add dataset support if needed in unit tests (this is unlikely to be needed though)
        @Override
        public DatasetFetchResponse fetchDatasetEvents(
                String datasetId, DatasetFetchRequest request) {
            return new DatasetFetchResponse(List.of(), null);
        }

        @Override
        public Optional<Dataset> getDataset(String datasetId) {
            return Optional.empty();
        }

        @Override
        public List<Dataset> queryDatasets(String projectName, String datasetName) {
            return List.of();
        }

        @Override
        public Optional<Function> getFunction(
                @Nonnull String projectName, @Nonnull String slug, @Nullable String version) {
            throw new RuntimeException("will not be invoked");
        }

        @Override
        public Optional<Function> getFunctionById(@Nonnull String functionId) {
            throw new RuntimeException("will not be invoked");
        }

        @Override
        public Object invokeFunction(
                @Nonnull String functionId, @Nonnull FunctionInvokeRequest request) {
            throw new RuntimeException("will not be invoked");
        }

        @Override
        public BtqlQueryResponse btqlQuery(@Nonnull String query) {
            throw new RuntimeException("will not be invoked");
        }
    }

    // Request/Response DTOs

    record CreateProjectRequest(String name) {}

    record Project(String id, String name, String orgId, String createdAt, String updatedAt) {}

    record ProjectList(List<Project> projects) {}

    record ExperimentList(List<Experiment> objects) {}

    record CreateExperimentRequest(
            String projectId,
            String name,
            Optional<String> description,
            Optional<String> baseExperimentId,
            Optional<List<String>> tags,
            Optional<Map<String, Object>> metadata) {

        public CreateExperimentRequest(String projectId, String name) {
            this(
                    projectId,
                    name,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
        }
    }

    record Experiment(
            String id,
            String projectId,
            String name,
            Optional<String> description,
            List<String> tags,
            Map<String, Object> metadata,
            String createdAt,
            String updatedAt) {}

    record CreateDatasetRequest(String projectId, String name, Optional<String> description) {
        public CreateDatasetRequest(String projectId, String name) {
            this(projectId, name, Optional.empty());
        }
    }

    record Dataset(
            String id,
            String projectId,
            String name,
            Optional<String> description,
            String createdAt,
            String updatedAt) {}

    record DatasetList(List<Dataset> objects) {}

    record DatasetEvent(Object input, Optional<Object> output, Optional<Object> metadata) {
        public DatasetEvent(Object input) {
            this(input, Optional.empty(), Optional.empty());
        }

        public DatasetEvent(Object input, Object output) {
            this(input, Optional.of(output), Optional.empty());
        }
    }

    record InsertEventsRequest(List<DatasetEvent> events) {}

    record InsertEventsResponse(int insertedCount) {}

    record DatasetFetchRequest(int limit, @Nullable String cursor, @Nullable String version) {
        public DatasetFetchRequest(int limit) {
            this(limit, null, null);
        }

        public DatasetFetchRequest(int limit, @Nullable String cursor) {
            this(limit, cursor, null);
        }
    }

    record DatasetFetchResponse(List<Map<String, Object>> events, @Nullable String cursor) {}

    // User and Organization models for login functionality
    record OrganizationInfo(String id, String name) {}

    record LoginRequest(String token) {}

    record LoginResponse(List<OrganizationInfo> orgInfo) {}

    record OrganizationAndProjectInfo(OrganizationInfo orgInfo, Project project) {}

    // Prompt models
    record PromptData(Object prompt, Object options) {}

    record Prompt(
            String id,
            String projectId,
            String orgId,
            String name,
            String slug,
            Optional<String> description,
            String created,
            PromptData promptData,
            Optional<List<String>> tags,
            Optional<Object> metadata) {}

    record PromptListResponse(List<Prompt> objects) {}

    // Function models for remote scorers/prompts/tools

    /**
     * Represents a Braintrust function (scorer, prompt, tool, or task). Functions can be invoked
     * remotely via the API.
     */
    record Function(
            String id,
            String projectId,
            String orgId,
            String name,
            String slug,
            Optional<String> description,
            String created,
            Optional<Object> functionData,
            Optional<Object> promptData,
            Optional<List<String>> tags,
            Optional<Object> metadata,
            Optional<String> functionType,
            Optional<Object> origin,
            Optional<Object> functionSchema) {}

    record FunctionListResponse(List<Function> objects) {}

    /**
     * Request body for invoking a function. The input field wraps the function arguments.
     *
     * <p>For remote Python/TypeScript scorers, the scorer handler parameters (input, output,
     * expected, metadata) must be wrapped in the outer input field.
     *
     * <p>The parent field enables distributed tracing by linking the remote function's spans to the
     * caller's span context. It can be either a base64-encoded SpanComponents string or an object
     * with object_type, object_id, and row_ids.
     */
    record FunctionInvokeRequest(
            @Nullable Object input, @Nullable String version, @Nullable Object parent) {

        /** Create a simple invoke request with just input */
        public static FunctionInvokeRequest of(Object input) {
            return new FunctionInvokeRequest(input, null, null);
        }

        /** Create a simple invoke request with input and version */
        public static FunctionInvokeRequest of(Object input, @Nullable String version) {
            return new FunctionInvokeRequest(input, version, null);
        }

        /**
         * Create an invoke request for a scorer with distributed tracing support.
         *
         * @param input the input to the task being scored
         * @param output the output from the task being scored
         * @param expected the expected output
         * @param metadata additional metadata
         * @param version optional function version
         * @param parent optional parent for distributed tracing - can be a base64-encoded
         *     SpanComponents string or a Map with object_type, object_id, and row_ids
         */
        public static FunctionInvokeRequest of(
                Object input,
                Object output,
                Object expected,
                Object metadata,
                @Nullable String version,
                @Nullable Object parent) {
            // Wrap scorer args in an inner map that becomes the outer "input" field
            var scorerArgs = new java.util.LinkedHashMap<String, Object>();
            scorerArgs.put("input", input);
            scorerArgs.put("output", output);
            scorerArgs.put("expected", expected);
            scorerArgs.put("metadata", metadata);
            return new FunctionInvokeRequest(scorerArgs, version, parent);
        }
    }

    /** Request body for BTQL queries. */
    record BtqlQueryRequest(String query) {}

    /**
     * Response from a BTQL query. The data field contains the rows returned by the query, where
     * each row is a map of column names to values.
     */
    record BtqlQueryResponse(List<Map<String, Object>> data) {}
}
