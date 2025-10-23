package dev.braintrust.prompt;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.config.BraintrustConfig;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class BraintrustPromptLoaderTest {

    @Test
    void testLoadPromptBySlug() {
        // Create test data
        BraintrustApiClient.OrganizationInfo orgInfo =
                new BraintrustApiClient.OrganizationInfo("org-123", "Test Org");
        BraintrustApiClient.Project project =
                new BraintrustApiClient.Project(
                        "proj-456", "test-project", "org-123", "2025-01-01", "2025-01-01");
        BraintrustApiClient.OrganizationAndProjectInfo orgAndProject =
                new BraintrustApiClient.OrganizationAndProjectInfo(orgInfo, project);

        // Create a test prompt
        BraintrustApiClient.Prompt testPrompt = createTestPrompt(project.id());

        // Create in-memory API client with the test prompt
        BraintrustApiClient apiClient =
                new BraintrustApiClient.InMemoryImpl(List.of(orgAndProject), List.of(testPrompt));

        // Create config
        BraintrustConfig config =
                BraintrustConfig.of(
                        "BRAINTRUST_API_KEY",
                        "doesntmatter",
                        "BRAINTRUST_DEFAULT_PROJECT_NAME",
                        "test-project");

        // Create loader
        BraintrustPromptLoader loader = BraintrustPromptLoader.of(config, apiClient);

        // Load the prompt
        BraintrustPrompt prompt = loader.load("kind-greeter");

        // Verify the prompt was loaded correctly
        assertNotNull(prompt);

        // Test rendering
        Map<String, Object> parameters = Map.of("name", "Bob");
        List<Map<String, Object>> renderedMessages = prompt.renderMessages(parameters);

        assertEquals(2, renderedMessages.size());
        assertEquals("What's up my friend? My name is Bob", renderedMessages.get(1).get("content"));
    }

    @Test
    void testLoadPromptWithDefaults() {
        // Create test data
        BraintrustApiClient.OrganizationInfo orgInfo =
                new BraintrustApiClient.OrganizationInfo("org-123", "Test Org");
        BraintrustApiClient.Project project =
                new BraintrustApiClient.Project(
                        "proj-456", "test-project", "org-123", "2025-01-01", "2025-01-01");
        BraintrustApiClient.OrganizationAndProjectInfo orgAndProject =
                new BraintrustApiClient.OrganizationAndProjectInfo(orgInfo, project);

        // Create a test prompt
        BraintrustApiClient.Prompt testPrompt = createTestPrompt(project.id());

        // Create in-memory API client with the test prompt
        BraintrustApiClient apiClient =
                new BraintrustApiClient.InMemoryImpl(List.of(orgAndProject), List.of(testPrompt));

        // Create config
        BraintrustConfig config =
                BraintrustConfig.of(
                        "BRAINTRUST_API_KEY",
                        "doesntmatter",
                        "BRAINTRUST_DEFAULT_PROJECT_NAME",
                        "test-project");

        // Create loader
        BraintrustPromptLoader loader = BraintrustPromptLoader.of(config, apiClient);

        // Load the prompt with defaults
        BraintrustPrompt prompt =
                loader.load(
                        BraintrustPromptLoader.PromptLoadRequest.builder()
                                .promptSlug("kind-greeter")
                                .defaults("max_tokens", "2000", "top_p", "0.95")
                                .build());

        // Verify defaults are applied
        Map<String, Object> options = prompt.getOptions();
        assertEquals("2000", options.get("max_tokens"));
        assertEquals("0.95", options.get("top_p"));

        // Verify original options are preserved
        assertEquals("gpt-4o-mini", options.get("model"));
        assertEquals(0, options.get("temperature"));
    }

    @Test
    void testLoadPromptWithProjectName() {
        // Create test data
        BraintrustApiClient.OrganizationInfo orgInfo =
                new BraintrustApiClient.OrganizationInfo("org-123", "Test Org");
        BraintrustApiClient.Project project =
                new BraintrustApiClient.Project(
                        "proj-456", "my-project", "org-123", "2025-01-01", "2025-01-01");
        BraintrustApiClient.OrganizationAndProjectInfo orgAndProject =
                new BraintrustApiClient.OrganizationAndProjectInfo(orgInfo, project);

        // Create a test prompt
        BraintrustApiClient.Prompt testPrompt = createTestPrompt(project.id());

        // Create in-memory API client with the test prompt
        BraintrustApiClient apiClient =
                new BraintrustApiClient.InMemoryImpl(List.of(orgAndProject), List.of(testPrompt));

        // Create config without default project name
        BraintrustConfig config = BraintrustConfig.of("BRAINTRUST_API_KEY", "test-key");

        // Create loader
        BraintrustPromptLoader loader = BraintrustPromptLoader.of(config, apiClient);

        // Load the prompt with explicit project name
        BraintrustPrompt prompt =
                loader.load(
                        BraintrustPromptLoader.PromptLoadRequest.builder()
                                .promptSlug("kind-greeter")
                                .projectName("my-project")
                                .build());

        // Verify the prompt was loaded correctly
        assertNotNull(prompt);
    }

    private BraintrustApiClient.Prompt createTestPrompt(String projectId) {
        // Create the prompt data structure matching the example JSON
        Map<String, Object> messages =
                Map.of(
                        "messages",
                        List.of(
                                Map.of(
                                        "role", "system",
                                        "content",
                                                "You are a kind chatbot who briefly greets people"),
                                Map.of(
                                        "role", "user",
                                        "content", "What's up my friend? My name is {{name}}")));

        Map<String, Object> options =
                Map.of(
                        "model", "gpt-4o-mini",
                        "params",
                                Map.of(
                                        "use_cache",
                                        true,
                                        "temperature",
                                        0,
                                        "response_format",
                                        Map.of("type", "text")),
                        "position", "0|hzzzzz:");

        BraintrustApiClient.PromptData promptData =
                new BraintrustApiClient.PromptData(messages, options);

        return new BraintrustApiClient.Prompt(
                "e2a4fb20-e97e-4e8a-be07-b226d55047b2",
                projectId,
                "e8d257dd-944c-479a-9916-40a9fa09f120",
                "kind-greeter",
                "kind-greeter",
                Optional.of("A very good boi"),
                "2025-10-21T21:35:18.287Z",
                promptData,
                Optional.empty(),
                Optional.empty());
    }
}
