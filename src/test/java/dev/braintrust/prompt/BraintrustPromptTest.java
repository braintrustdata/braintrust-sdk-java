package dev.braintrust.prompt;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.api.BraintrustApiClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class BraintrustPromptTest {

    @Test
    void testGetOptionsWithDefaults() {
        BraintrustApiClient.Prompt promptObject = createTestPrompt();

        // Create a prompt with defaults
        Map<String, String> defaults =
                Map.of(
                        "max_tokens", "1000",
                        "temperature",
                                "0.7", // This should be ignored as temperature is already set to 0
                        "top_p", "0.9");
        BraintrustPrompt prompt = new BraintrustPrompt(promptObject, defaults);

        Map<String, Object> options = prompt.getOptions();

        // Verify that defaults are applied only when not already set
        assertEquals("1000", options.get("max_tokens")); // Applied from defaults
        assertEquals("0.9", options.get("top_p")); // Applied from defaults
        assertEquals(
                0,
                options.get(
                        "temperature")); // NOT overridden by defaults (original value preserved)

        // Verify original options are still present
        assertEquals("gpt-4o-mini", options.get("model"));
        assertEquals(true, options.get("use_cache"));
    }

    @Test
    void testRenderMessagesWithMalformedMustache() {
        // Create a prompt with malformed mustache syntax
        Map<String, Object> messages =
                Map.of(
                        "messages",
                        List.of(
                                Map.of(
                                        "role", "system",
                                        "content", "You are a helpful assistant"),
                                Map.of(
                                        "role", "user",
                                        "content", "Hello {{ whatever. This should not match.")));

        Map<String, Object> options = Map.of("model", "gpt-4o-mini");

        BraintrustApiClient.PromptData promptData =
                new BraintrustApiClient.PromptData(messages, options);

        BraintrustApiClient.Prompt promptObject =
                new BraintrustApiClient.Prompt(
                        "test-id",
                        "proj-id",
                        "org-id",
                        "test-prompt",
                        "test-slug",
                        Optional.empty(),
                        "2025-01-01T00:00:00Z",
                        promptData,
                        Optional.empty(),
                        Optional.empty());

        BraintrustPrompt prompt = new BraintrustPrompt(promptObject);

        // Render with empty parameters - malformed mustache should be ignored
        Map<String, Object> parameters = Map.of();
        List<Map<String, Object>> renderedMessages = prompt.renderMessages(parameters);

        // Verify the malformed mustache is left as-is (not treated as a parameter)
        assertEquals(2, renderedMessages.size());
        assertEquals(
                "Hello {{ whatever. This should not match.",
                renderedMessages.get(1).get("content"));
    }

    @Test
    void testRenderMessagesWithParameters() {
        // Create a test prompt object
        BraintrustApiClient.Prompt promptObject = createTestPrompt();

        BraintrustPrompt prompt = new BraintrustPrompt(promptObject);

        // Render messages with parameters
        Map<String, Object> parameters = Map.of("name", "Alice");
        List<Map<String, Object>> renderedMessages = prompt.renderMessages(parameters);

        // Verify the messages were rendered correctly
        assertEquals(2, renderedMessages.size());

        Map<String, Object> systemMessage = renderedMessages.get(0);
        assertEquals("system", systemMessage.get("role"));
        assertEquals(
                "You are a kind chatbot who briefly greets people", systemMessage.get("content"));

        Map<String, Object> userMessage = renderedMessages.get(1);
        assertEquals("user", userMessage.get("role"));
        assertEquals("What's up my friend? My name is Alice", userMessage.get("content"));
    }

    @Test
    void testRenderMessagesWithList() {
        // Create a prompt that uses Mustache list iteration
        Map<String, Object> messages =
                Map.of(
                        "messages",
                        List.of(
                                Map.of(
                                        "role", "system",
                                        "content", "You are a helpful assistant"),
                                Map.of(
                                        "role",
                                        "user",
                                        "content",
                                        "Here are the items:\n"
                                                + "{{#items}}- {{name}}: {{description}}\n"
                                                + "{{/items}}")));

        Map<String, Object> options = Map.of("model", "gpt-4o-mini");

        BraintrustApiClient.PromptData promptData =
                new BraintrustApiClient.PromptData(messages, options);

        BraintrustApiClient.Prompt promptObject =
                new BraintrustApiClient.Prompt(
                        "test-id",
                        "proj-id",
                        "org-id",
                        "test-prompt",
                        "test-slug",
                        Optional.empty(),
                        "2025-01-01T00:00:00Z",
                        promptData,
                        Optional.empty(),
                        Optional.empty());

        BraintrustPrompt prompt = new BraintrustPrompt(promptObject);

        // Render with list parameters
        Map<String, Object> parameters =
                Map.of(
                        "items",
                        List.of(
                                Map.of("name", "Apple", "description", "A red fruit"),
                                Map.of("name", "Banana", "description", "A yellow fruit"),
                                Map.of("name", "Cherry", "description", "A small red fruit")));

        List<Map<String, Object>> renderedMessages = prompt.renderMessages(parameters);

        assertEquals(2, renderedMessages.size());
        String expectedContent =
                "Here are the items:\n"
                        + "- Apple: A red fruit\n"
                        + "- Banana: A yellow fruit\n"
                        + "- Cherry: A small red fruit\n";
        assertEquals(expectedContent, renderedMessages.get(1).get("content"));
    }

    @Test
    void testRenderMessagesWithEmptyList() {
        // Create a prompt that uses Mustache list iteration
        Map<String, Object> messages =
                Map.of(
                        "messages",
                        List.of(
                                Map.of(
                                        "role",
                                        "user",
                                        "content",
                                        "Items: {{#items}}{{name}} {{/items}}{{^items}}No items"
                                                + " found{{/items}}")));

        Map<String, Object> options = Map.of("model", "gpt-4o-mini");

        BraintrustApiClient.PromptData promptData =
                new BraintrustApiClient.PromptData(messages, options);

        BraintrustApiClient.Prompt promptObject =
                new BraintrustApiClient.Prompt(
                        "test-id",
                        "proj-id",
                        "org-id",
                        "test-prompt",
                        "test-slug",
                        Optional.empty(),
                        "2025-01-01T00:00:00Z",
                        promptData,
                        Optional.empty(),
                        Optional.empty());

        BraintrustPrompt prompt = new BraintrustPrompt(promptObject);

        // Render with empty list
        Map<String, Object> parameters = Map.of("items", List.of());
        List<Map<String, Object>> renderedMessages = prompt.renderMessages(parameters);

        assertEquals(1, renderedMessages.size());
        assertEquals("Items: No items found", renderedMessages.get(0).get("content"));
    }

    @Test
    void testRenderMessagesWithConditional() {
        // Create a prompt that uses Mustache conditionals
        Map<String, Object> messages =
                Map.of(
                        "messages",
                        List.of(
                                Map.of(
                                        "role", "system",
                                        "content", "You are a helpful assistant"),
                                Map.of(
                                        "role",
                                        "user",
                                        "content",
                                        "Hello {{name}}!{{#isAdmin}} You have admin"
                                                + " privileges.{{/isAdmin}}{{^isAdmin}} You are a"
                                                + " regular user.{{/isAdmin}}")));

        Map<String, Object> options = Map.of("model", "gpt-4o-mini");

        BraintrustApiClient.PromptData promptData =
                new BraintrustApiClient.PromptData(messages, options);

        BraintrustApiClient.Prompt promptObject =
                new BraintrustApiClient.Prompt(
                        "test-id",
                        "proj-id",
                        "org-id",
                        "test-prompt",
                        "test-slug",
                        Optional.empty(),
                        "2025-01-01T00:00:00Z",
                        promptData,
                        Optional.empty(),
                        Optional.empty());

        BraintrustPrompt prompt = new BraintrustPrompt(promptObject);

        // Test with admin user
        Map<String, Object> adminParameters = Map.of("name", "Alice", "isAdmin", true);
        List<Map<String, Object>> adminMessages = prompt.renderMessages(adminParameters);
        assertEquals(
                "Hello Alice! You have admin privileges.", adminMessages.get(1).get("content"));

        // Test with regular user
        Map<String, Object> regularParameters = Map.of("name", "Bob", "isAdmin", false);
        List<Map<String, Object>> regularMessages = prompt.renderMessages(regularParameters);
        assertEquals("Hello Bob! You are a regular user.", regularMessages.get(1).get("content"));
    }

    @Test
    void testRenderMessagesWithNestedObjects() {
        // Create a prompt that uses nested object properties
        Map<String, Object> messages =
                Map.of(
                        "messages",
                        List.of(
                                Map.of(
                                        "role",
                                        "user",
                                        "content",
                                        "User: {{user.firstName}} {{user.lastName}}\n"
                                                + "Email: {{user.contact.email}}\n"
                                                + "Phone: {{user.contact.phone}}")));

        Map<String, Object> options = Map.of("model", "gpt-4o-mini");

        BraintrustApiClient.PromptData promptData =
                new BraintrustApiClient.PromptData(messages, options);

        BraintrustApiClient.Prompt promptObject =
                new BraintrustApiClient.Prompt(
                        "test-id",
                        "proj-id",
                        "org-id",
                        "test-prompt",
                        "test-slug",
                        Optional.empty(),
                        "2025-01-01T00:00:00Z",
                        promptData,
                        Optional.empty(),
                        Optional.empty());

        BraintrustPrompt prompt = new BraintrustPrompt(promptObject);

        // Render with nested object
        Map<String, Object> parameters =
                Map.of(
                        "user",
                        Map.of(
                                "firstName",
                                "John",
                                "lastName",
                                "Doe",
                                "contact",
                                Map.of("email", "john@example.com", "phone", "555-1234")));

        List<Map<String, Object>> renderedMessages = prompt.renderMessages(parameters);

        assertEquals(1, renderedMessages.size());
        String expectedContent = "User: John Doe\nEmail: john@example.com\nPhone: " + "555-1234";
        assertEquals(expectedContent, renderedMessages.get(0).get("content"));
    }

    @Test
    void testRenderMessagesWithInvertedSection() {
        // Create a prompt that uses inverted sections (renders when value is false/empty)
        Map<String, Object> messages =
                Map.of(
                        "messages",
                        List.of(
                                Map.of(
                                        "role",
                                        "user",
                                        "content",
                                        "{{#hasError}}Error:"
                                                + " {{errorMessage}}{{/hasError}}{{^hasError}}All"
                                                + " systems operational{{/hasError}}")));

        Map<String, Object> options = Map.of("model", "gpt-4o-mini");

        BraintrustApiClient.PromptData promptData =
                new BraintrustApiClient.PromptData(messages, options);

        BraintrustApiClient.Prompt promptObject =
                new BraintrustApiClient.Prompt(
                        "test-id",
                        "proj-id",
                        "org-id",
                        "test-prompt",
                        "test-slug",
                        Optional.empty(),
                        "2025-01-01T00:00:00Z",
                        promptData,
                        Optional.empty(),
                        Optional.empty());

        BraintrustPrompt prompt = new BraintrustPrompt(promptObject);

        // Test without error
        Map<String, Object> noErrorParams = Map.of("hasError", false);
        List<Map<String, Object>> noErrorMessages = prompt.renderMessages(noErrorParams);
        assertEquals("All systems operational", noErrorMessages.get(0).get("content"));

        // Test with error
        Map<String, Object> errorParams =
                Map.of("hasError", true, "errorMessage", "Database connection failed");
        List<Map<String, Object>> errorMessages = prompt.renderMessages(errorParams);
        assertEquals("Error: Database connection failed", errorMessages.get(0).get("content"));
    }

    @Test
    void testRenderMessagesWithComplexTypes() {
        // Test that non-string types are properly rendered
        Map<String, Object> messages =
                Map.of(
                        "messages",
                        List.of(
                                Map.of(
                                        "role",
                                        "user",
                                        "content",
                                        "Count: {{count}}\n"
                                                + "Price: ${{price}}\n"
                                                + "Enabled: {{enabled}}")));

        Map<String, Object> options = Map.of("model", "gpt-4o-mini");

        BraintrustApiClient.PromptData promptData =
                new BraintrustApiClient.PromptData(messages, options);

        BraintrustApiClient.Prompt promptObject =
                new BraintrustApiClient.Prompt(
                        "test-id",
                        "proj-id",
                        "org-id",
                        "test-prompt",
                        "test-slug",
                        Optional.empty(),
                        "2025-01-01T00:00:00Z",
                        promptData,
                        Optional.empty(),
                        Optional.empty());

        BraintrustPrompt prompt = new BraintrustPrompt(promptObject);

        // Test with various data types
        Map<String, Object> parameters = Map.of("count", 42, "price", 19.99, "enabled", true);

        List<Map<String, Object>> renderedMessages = prompt.renderMessages(parameters);

        assertEquals(1, renderedMessages.size());
        assertEquals(
                "Count: 42\nPrice: $19.99\nEnabled: true", renderedMessages.get(0).get("content"));
    }

    private BraintrustApiClient.Prompt createTestPrompt() {
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
                "e8d257dd-944c-479a-9916-40a9fa09f120",
                "5d7c97d7-fef1-4cb7-bda6-7e3756a0ca8e",
                "kind-greeter",
                "kind-greeter-69d2",
                Optional.of("A very good boi"),
                "2025-10-21T21:35:18.287Z",
                promptData,
                Optional.empty(),
                Optional.empty());
    }
}
