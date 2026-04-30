package dev.braintrust.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.BraintrustUtils;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.openapi.ApiClient;
import dev.braintrust.openapi.ApiException;
import dev.braintrust.openapi.JSON;
import dev.braintrust.openapi.api.ProjectsApi;
import dev.braintrust.openapi.model.CreateProject;
import dev.braintrust.openapi.model.Project;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Provides the necessary API calls for the Braintrust SDK. Users of the SDK should favor using
 * {@link dev.braintrust.eval.Eval} or {@link dev.braintrust.trace.BraintrustTracing}
 */
public class BraintrustOpenApiClient extends ApiClient {
    private static final ObjectMapper MAPPER = new JSON().getMapper();

    private final BraintrustConfig config;

    private BraintrustOpenApiClient(BraintrustConfig config) {
        super();
        this.config = config;
        this.updateBaseUri(config.apiUrl());
        this.setHttpClientBuilder(HttpClient.newBuilder().sslContext(config.sslContext()));
        this.setRequestInterceptor(req -> req.header("Authorization", "Bearer " + config.apiKey()));
    }

    public static BraintrustOpenApiClient of(BraintrustConfig config) {
        return new BraintrustOpenApiClient(config);
    }

    /**
     * Calls {@code POST /api/apikey/login} to retrieve organization info for the current API key.
     * This endpoint is not in the OpenAPI spec so it is implemented here as a custom method.
     */
    public LoginResponse login() {
        try {
            var body = MAPPER.writeValueAsString(new LoginRequest(config.apiKey()));
            var requestBuilder =
                    HttpRequest.newBuilder()
                            .uri(URI.create(config.apiUrl() + "/api/apikey/login"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body));

            if (getRequestInterceptor() != null) {
                getRequestInterceptor().accept(requestBuilder);
            }

            HttpResponse<String> response =
                    getHttpClient()
                            .send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                throw new LoginException(
                        "Login failed with status "
                                + response.statusCode()
                                + ": "
                                + response.body());
            }
            return MAPPER.readValue(response.body(), LoginResponse.class);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Look up or create the project from config, resolve the org name via login, and return the
     * Braintrust app URI for the project.
     */
    public URI fetchProjectUri() {
        var project = fetchOrCreateProject(config);
        var orgName = fetchOrgInfo(project.getOrgId().toString()).name();
        return BraintrustUtils.createProjectURI(config.appUrl(), orgName, project.getName());
    }

    public OrgInfo fetchOrgInfo(String orgId) {
        return login().orgInfo().stream()
                .filter(o -> o.id().equalsIgnoreCase(orgId))
                .findFirst()
                .orElseThrow(() -> new ApiException("Unable to find org for project: " + orgId));
    }

    public Project fetchOrCreateProject(BraintrustConfig config) {
        return fetchOrCreateProject(
                config.defaultProjectId().orElse(null), config.defaultProjectName().orElse(null));
    }

    public Project fetchOrCreateProject(@Nullable String projectId, @Nullable String projectName) {
        if (projectId == null && projectName == null) {
            throw new IllegalArgumentException("must provide project id or project name");
        }
        var projectsApi = new ProjectsApi(this);

        Project project;
        if (projectId != null) {
            project = projectsApi.getProjectId(UUID.fromString(projectId));
        } else {
            var existing =
                    projectsApi.getProject(null, null, null, null, projectName, null).getObjects();
            if (existing.isEmpty()) {
                project = projectsApi.postProject(new CreateProject().name(projectName));
            } else if (existing.size() == 1) {
                project = existing.get(0);
            } else {
                var projectIds = existing.stream().map(p -> p.getId().toString()).toList();
                throw new RuntimeException(
                        "Multiple projects with the same name already exists. This should not happen. project name: %s project ids: %s"
                                .formatted(projectName, projectIds));
            }
        }
        return project;
    }

    /**
     * Calls {@code POST /btql} to run an arbitrary BTQL query. This endpoint is not in the OpenAPI
     * spec so it is implemented here as a custom method.
     */
    public BtqlQueryResponse btqlQuery(String query) {
        try {
            var body = MAPPER.writeValueAsString(new BtqlQueryRequest(query));
            var requestBuilder =
                    HttpRequest.newBuilder()
                            .uri(URI.create(config.apiUrl() + "/btql"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body));

            if (getRequestInterceptor() != null) {
                getRequestInterceptor().accept(requestBuilder);
            }

            HttpResponse<String> response =
                    getHttpClient()
                            .send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                throw new BtqlRateLimitException(
                        response.statusCode(),
                        "BTQL rate limit exceeded",
                        response.headers(),
                        response.body());
            } else if (response.statusCode() / 100 != 2) {
                throw new ApiException(
                        response.statusCode(),
                        "BTQL query failed",
                        response.headers(),
                        response.body());
            }

            return MAPPER.readValue(response.body(), BtqlQueryResponse.class);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("BTQL query failed", e);
        }
    }

    public record OrgInfo(String id, String name) {}

    /**
     * Response from a {@code POST /btql} query.
     *
     * <p>Freshness is determined by comparing {@link FreshnessState#lastProcessedXactId()} to
     * {@link FreshnessState#lastConsideredXactId()}: when both are non-null and equal, the query
     * has caught up to all ingested data and the result is fresh.
     *
     * <p>The {@link RealtimeState#type()} field indicates whether realtime indexing is still active
     * ({@code "on"}) or has timed out ({@code "exhausted_timeout"}).
     */
    public record BtqlQueryResponse(
            List<Map<String, Object>> data,
            @JsonProperty("freshness_state") FreshnessState freshnessState,
            @JsonProperty("realtime_state") RealtimeState realtimeState) {

        /** Returns {@code true} when the query result has caught up to all ingested data. */
        public boolean isFresh() {
            if (freshnessState == null) {
                return false;
            }
            var processed = freshnessState.lastProcessedXactId();
            var considered = freshnessState.lastConsideredXactId();
            return processed != null && processed.equals(considered);
        }
    }

    public record FreshnessState(
            @JsonProperty("last_processed_xact_id") String lastProcessedXactId,
            @JsonProperty("last_considered_xact_id") String lastConsideredXactId) {}

    /** Real-time indexing state for a BTQL query. */
    public record RealtimeState(@JsonProperty("type") String type) {}

    private record LoginRequest(String token) {}

    private record BtqlQueryRequest(String query) {}

    public record LoginResponse(@JsonProperty("org_info") List<OrgInfo> orgInfo) {}
}
