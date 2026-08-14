package dev.braintrust.examples;

import dev.braintrust.api.BraintrustOpenApiClient;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.openapi.api.DatasetsApi;
import dev.braintrust.openapi.api.ExperimentsApi;
import dev.braintrust.openapi.api.ProjectsApi;
import dev.braintrust.openapi.api.PromptsApi;
import dev.braintrust.openapi.model.Dataset;
import dev.braintrust.openapi.model.Experiment;
import dev.braintrust.openapi.model.Prompt;

/**
 * Demonstrates the low-level, OpenAPI-generated Braintrust API client for raw REST access beyond
 * what the {@code Eval} and {@code BraintrustTracing} helpers cover. See docs/api-client.md for the
 * full walkthrough.
 *
 * <p>Run with:
 *
 * <pre>
 *   BRAINTRUST_API_KEY=sk-... ./gradlew :examples:api-client:run
 * </pre>
 */
public class ApiClientExample {
    // Cap each listing so the example prints a manageable amount.
    private static final int LIMIT = 5;

    public static void main(String[] args) {
        // BraintrustOpenApiClient is an ApiClient with the base URL, bearer auth, and TLS
        // wired up from the config. Every *Api class takes it in its constructor.
        var client = BraintrustOpenApiClient.of(BraintrustConfig.fromEnvironment());

        // Resolve the org name (login() is a Braintrust helper on top of the generated client)
        // and grab the first project to read from.
        var orgName = client.login().orgInfo().get(0).name();
        var project =
                new ProjectsApi(client)
                        .getProject(1, null, null, null, null, null)
                        .getObjects()
                        .get(0);
        var projectId = project.getId();
        System.out.println("Reading project " + project.getName() + " from org " + orgName);

        // List endpoints share the leading pagination/filter args and return a page wrapper
        // whose getObjects() holds the results. Pass null for filters you don't need; the
        // first arg is the page-size limit, and here we scope each list to projectId.

        // ── Experiments ───────────────────────────────────────────────────────────
        var experiments = new ExperimentsApi(client);
        var experimentPage =
                experiments.getExperiment(LIMIT, null, null, null, null, null, projectId, null);
        System.out.println("\nExperiments:");
        for (Experiment e : experimentPage.getObjects()) {
            System.out.println("  " + e.getName() + " (" + e.getId() + ")");
        }

        // ── Prompts ───────────────────────────────────────────────────────────────
        var prompts = new PromptsApi(client);
        var promptPage =
                prompts.getPrompt(
                        LIMIT, null, null, null, null, null, projectId, null, null, null, null);
        System.out.println("\nPrompts:");
        for (Prompt p : promptPage.getObjects()) {
            System.out.println("  " + p.getName() + " (" + p.getId() + ")");
        }

        // ── Datasets ──────────────────────────────────────────────────────────────
        var datasets = new DatasetsApi(client);
        var datasetPage = datasets.getDataset(LIMIT, null, null, null, null, null, projectId, null);
        System.out.println("\nDatasets:");
        for (Dataset d : datasetPage.getObjects()) {
            System.out.println("  " + d.getName() + " (" + d.getId() + ")");
        }
    }
}
