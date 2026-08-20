package dev.braintrust.examples;

import dev.braintrust.api.BraintrustOpenApiClient;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.openapi.api.DatasetsApi;
import dev.braintrust.openapi.api.ExperimentsApi;
import dev.braintrust.openapi.api.ProjectAutomationsApi;
import dev.braintrust.openapi.api.ProjectsApi;
import dev.braintrust.openapi.api.PromptsApi;
import dev.braintrust.openapi.model.CreateProjectAutomation;
import dev.braintrust.openapi.model.Dataset;
import dev.braintrust.openapi.model.Experiment;
import dev.braintrust.openapi.model.PatchProjectAutomation;
import dev.braintrust.openapi.model.PatchProjectAutomationConfig;
import dev.braintrust.openapi.model.Project;
import dev.braintrust.openapi.model.ProjectAutomation;
import dev.braintrust.openapi.model.ProjectAutomationConfig;
import dev.braintrust.openapi.model.ProjectAutomationConfigOneOf;
import dev.braintrust.openapi.model.ProjectAutomationConfigOneOfAction;
import dev.braintrust.openapi.model.Prompt;
import dev.braintrust.openapi.model.WindowedAutomationConfigActionsInnerOneOf;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

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
 *
 * NOTE: this example is safe to run. It only reads resources by default. Methods which mutate state
 * are included as an example, but they are not invoked.
 */
public class ApiClientExample {
    // Cap each listing so the example prints a manageable amount.
    private static final int LIMIT = 5;

    // Name of the automation this example creates and then deletes.
    private static final String AUTOMATION_NAME = "java-example-low-factuality-alert";

    // How far to scan when checking whether AUTOMATION_NAME is already taken. The server rejects
    // the spec's project_automation_name filter, so this has to be done client-side.
    private static final int AUTOMATION_SCAN_LIMIT = 100;

    public static void main(String[] args) {
        // BraintrustOpenApiClient is an ApiClient with the base URL, bearer auth, and TLS
        // wired up from the config. Every *Api class takes it in its constructor.
        var client = BraintrustOpenApiClient.of(BraintrustConfig.fromEnvironment());

        // Resolve the org name (login() is a Braintrust helper on top of the generated client)
        // and pick the project to read from.
        var orgName = client.login().orgInfo().get(0).name();
        var project = resolveProject(new ProjectsApi(client));
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

        // ── Project automations ───────────────────────────────────────────────────
        // Automations are the alert and export rules attached to a project.
        listAutomations(client);
        // automationLifecycle(client, projectId);
    }

    /**
     * Picks the project to read from, preferring explicit configuration over whatever happens to be
     * first in the org: {@code BRAINTRUST_DEFAULT_PROJECT_NAME}, then {@code BRAINTRUST_PROJECT},
     * then the org's first project.
     *
     * <p>Read straight from the environment rather than via {@link BraintrustConfig} because {@code
     * defaultProjectName()} falls back to a built-in default, so it can't express "unset" and would
     * never let the later options apply.
     */
    private static Project resolveProject(ProjectsApi projects) {
        for (var envVar : List.of("BRAINTRUST_DEFAULT_PROJECT_NAME", "BRAINTRUST_PROJECT")) {
            var name = System.getenv(envVar);
            if (name == null || name.isBlank()) {
                continue;
            }
            // projectName is a server-side filter, so a match comes back as the only object.
            var matches = projects.getProject(1, null, null, null, name, null).getObjects();
            if (matches.isEmpty()) {
                throw new IllegalStateException(
                        "%s is set to \"%s\" but no project by that name exists"
                                .formatted(envVar, name));
            }
            System.out.println("Selected project via " + envVar);
            return matches.get(0);
        }

        var firstProject = projects.getProject(1, null, null, null, null, null).getObjects();
        if (firstProject.isEmpty()) {
            throw new IllegalStateException("this org has no projects to read from");
        }
        return firstProject.get(0);
    }

    /**
     * Lists the org's project automations.
     *
     * <p>{@code config} is a oneOf, and a oneOf that matches no variant throws, so an automation
     * kind missing from the pinned spec fails the whole page rather than just that row. Keep {@code
     * braintrustOpenApiRef} current if a listing starts failing with {@code 0 classes match
     * result}. See docs/api-client.md.
     */
    private static void listAutomations(BraintrustOpenApiClient client) {
        var automations = new ProjectAutomationsApi(client);
        System.out.println("\nAutomations:");
        var page = automations.getProjectAutomation(LIMIT, null, null, null, null, null);
        for (ProjectAutomation a : page.getObjects()) {
            System.out.println("  " + a.getName() + " (" + a.getId() + ")");
        }
    }

    /**
     * Full create / read / update / delete round trip for a log-alert automation, which POSTs to a
     * webhook whenever a low-scoring row lands.
     *
     * <p>Not called by default -- it mutates the project. Call it from {@code main} to try the
     * write path; it deletes what it creates, so it leaves the project as it found it.
     *
     * <p>Note the {@code dev.braintrust.openapi.model} classes are imported by name rather than
     * with a wildcard: that package contains a class named {@code System}, which shadows {@code
     * java.lang.System} under {@code import ...model.*}.
     */
    private static void automationLifecycle(BraintrustOpenApiClient client, UUID projectId) {
        var automations = new ProjectAutomationsApi(client);

        // Config is a oneOf over the automation kinds. ProjectAutomationConfigOneOf is the
        // "logs" variant; the OneOf1/2/3 siblings are btql_export, retention, and
        // environment_update respectively.
        // The webhook/slack action variants are shared with windowed automations, hence the
        // WindowedAutomationConfigActionsInner* class names.
        var action =
                new ProjectAutomationConfigOneOfAction(
                        new WindowedAutomationConfigActionsInnerOneOf()
                                .type(WindowedAutomationConfigActionsInnerOneOf.TypeEnum.WEBHOOK)
                                .url("https://example.com/braintrust-hook"));
        var logsConfig =
                new ProjectAutomationConfigOneOf()
                        .eventType(ProjectAutomationConfigOneOf.EventTypeEnum.LOGS)
                        // Fire at most once every 5 minutes for matching rows.
                        .btqlFilter("scores.Factuality < 0.5")
                        .intervalSeconds(new BigDecimal(300))
                        .action(action);

        // postProjectAutomation is create-or-return: if an automation with this name already
        // exists it comes back unmodified, and this method would then update and delete something
        // it did not create. Bail out rather than touching pre-existing state.
        var existing =
                automations
                        .getProjectAutomation(AUTOMATION_SCAN_LIMIT, null, null, null, null, null)
                        .getObjects()
                        .stream()
                        .filter(a -> AUTOMATION_NAME.equals(a.getName()))
                        .findFirst();
        if (existing.isPresent()) {
            System.out.println(
                    "\nSkipping lifecycle: an automation named "
                            + AUTOMATION_NAME
                            + " already exists ("
                            + existing.get().getId()
                            + ")");
            return;
        }

        ProjectAutomation created =
                automations.postProjectAutomation(
                        new CreateProjectAutomation()
                                .projectId(projectId)
                                .name(AUTOMATION_NAME)
                                .description("created by examples/api-client")
                                .config(new ProjectAutomationConfig(logsConfig)));
        System.out.println(
                "\nCreated automation: " + created.getName() + " (" + created.getId() + ")");

        // Read back by id. Prefer this over the list endpoint, which can throw -- see
        // listAutomations above.
        ProjectAutomation fetched = automations.getProjectAutomationId(created.getId());
        System.out.println("Fetched back: " + fetched.getDescription());

        // Update: tighten the filter and relabel.
        ProjectAutomation updated =
                automations.patchProjectAutomationId(
                        created.getId(),
                        new PatchProjectAutomation()
                                .description("updated by examples/api-client")
                                .config(
                                        new PatchProjectAutomationConfig(
                                                logsConfig.btqlFilter(
                                                        "scores.Factuality < 0.25"))));
        System.out.println("Updated: " + updated.getDescription());

        // Delete, so the example leaves nothing behind.
        automations.deleteProjectAutomationId(created.getId());
        System.out.println("Deleted automation " + created.getId());
    }
}
