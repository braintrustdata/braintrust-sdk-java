package dev.braintrust.prompt;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.api.BraintrustApiClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class BraintrustPromptTest {

    @Test
    void testRenderMessagesWithParameters() {
        // Create a test prompt object
        BraintrustApiClient.Prompt promptObject = createTestPrompt();

        BraintrustPrompt prompt = new BraintrustPrompt(promptObject);

        // Render messages with parameters
        Map<String, String> parameters = Map.of("name", "Alice");
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
    void testRenderMessagesWithMissingParameter() {
        BraintrustApiClient.Prompt promptObject = createTestPrompt();
        BraintrustPrompt prompt = new BraintrustPrompt(promptObject);

        // Try to render without providing the required parameter
        Map<String, String> parameters = Map.of();

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () -> {
                            prompt.renderMessages(parameters);
                        });

        assertTrue(exception.getMessage().contains("Missing parameter: name"));
    }

    @Test
    void testRenderMessagesWithUnusedParameter() {
        BraintrustApiClient.Prompt promptObject = createTestPrompt();
        BraintrustPrompt prompt = new BraintrustPrompt(promptObject);

        // Provide extra parameters that aren't used
        Map<String, String> parameters = Map.of("name", "Alice", "unused", "value");

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () -> {
                            prompt.renderMessages(parameters);
                        });

        assertTrue(exception.getMessage().contains("Unused parameters"));
        assertTrue(exception.getMessage().contains("unused"));
    }

    @Test
    void testGetOptions() {
        BraintrustApiClient.Prompt promptObject = createTestPrompt();
        BraintrustPrompt prompt = new BraintrustPrompt(promptObject);

        Map<String, Object> options = prompt.getOptions();

        // Verify that top-level options and params are merged
        assertEquals("gpt-4o-mini", options.get("model"));
        assertEquals(true, options.get("use_cache"));
        assertEquals(0, options.get("temperature"));

        Map<String, Object> responseFormat = (Map<String, Object>) options.get("response_format");
        assertNotNull(responseFormat);
        assertEquals("text", responseFormat.get("type"));

        // Verify that "params" itself is not in the result
        assertFalse(options.containsKey("params"));

        // Verify that "position" (a top-level option) is included
        assertEquals("0|hzzzzz:", options.get("position"));
    }

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
        Map<String, String> parameters = Map.of();
        List<Map<String, Object>> renderedMessages = prompt.renderMessages(parameters);

        // Verify the malformed mustache is left as-is (not treated as a parameter)
        assertEquals(2, renderedMessages.size());
        assertEquals(
                "Hello {{ whatever. This should not match.",
                renderedMessages.get(1).get("content"));
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
