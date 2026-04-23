package dev.braintrust.prompt;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.openapi.JSON;
import dev.braintrust.openapi.model.PromptDataNullish;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class BraintrustPromptTest {

    private static final ObjectMapper MAPPER = new JSON().getMapper();

    /**
     * Build a PromptDataNullish from a plain Java map that mirrors the JSON structure the
     * Braintrust API returns. This lets test cases stay readable without constructing the full
     * generated type hierarchy by hand.
     */
    private static PromptDataNullish promptData(
            Map<String, Object> prompt, Map<String, Object> options) {
        Map<String, Object> raw = Map.of("prompt", prompt, "options", options);
        return MAPPER.convertValue(raw, PromptDataNullish.class);
    }

    private static PromptDataNullish createTestPromptData() {
        Map<String, Object> prompt =
                Map.of(
                        "type",
                        "chat",
                        "messages",
                        List.of(
                                Map.of(
                                        "role",
                                        "system",
                                        "content",
                                        "You are a kind chatbot who briefly greets people"),
                                Map.of(
                                        "role", "user",
                                        "content", "What's up my friend? My name is {{name}}")));

        Map<String, Object> options =
                Map.of(
                        "model",
                        "gpt-4o-mini",
                        "params",
                        Map.of(
                                "use_cache",
                                true,
                                "temperature",
                                0,
                                "response_format",
                                Map.of("type", "text")),
                        "position",
                        "0|hzzzzz:");

        return promptData(prompt, options);
    }

    @Test
    void testGetOptionsWithDefaults() {
        Map<String, String> defaults =
                Map.of(
                        "max_tokens", "1000",
                        "temperature", "0.7", // should be ignored — temperature is already set to 0
                        "top_p", "0.9");

        BraintrustPrompt prompt = new BraintrustPrompt(createTestPromptData(), defaults);
        Map<String, Object> options = prompt.getOptions();

        assertEquals("1000", options.get("max_tokens")); // applied from defaults
        assertEquals("0.9", options.get("top_p")); // applied from defaults
        // temperature already present — default must not override it
        assertNotNull(options.get("temperature"));
        assertNotEquals("0.7", options.get("temperature").toString());

        assertEquals("gpt-4o-mini", options.get("model"));
        assertEquals(true, options.get("use_cache"));
    }

    @Test
    void testRenderMessagesWithMalformedMustache() {
        Map<String, Object> prompt =
                Map.of(
                        "type",
                        "chat",
                        "messages",
                        List.of(
                                Map.of("role", "system", "content", "You are a helpful assistant"),
                                Map.of(
                                        "role",
                                        "user",
                                        "content",
                                        "Hello {{ whatever. This should not match.")));

        BraintrustPrompt braintrustPrompt =
                new BraintrustPrompt(promptData(prompt, Map.of("model", "gpt-4o-mini")));

        List<Map<String, Object>> rendered = braintrustPrompt.renderMessages(Map.of());

        assertEquals(2, rendered.size());
        assertEquals("Hello {{ whatever. This should not match.", rendered.get(1).get("content"));
    }

    @Test
    void testRenderMessagesWithParameters() {
        BraintrustPrompt prompt = new BraintrustPrompt(createTestPromptData());

        List<Map<String, Object>> rendered = prompt.renderMessages(Map.of("name", "Alice"));

        assertEquals(2, rendered.size());
        assertEquals("system", rendered.get(0).get("role"));
        assertEquals(
                "You are a kind chatbot who briefly greets people", rendered.get(0).get("content"));
        assertEquals("user", rendered.get(1).get("role"));
        assertEquals("What's up my friend? My name is Alice", rendered.get(1).get("content"));
    }

    @Test
    void testRenderMessagesWithList() {
        Map<String, Object> prompt =
                Map.of(
                        "type",
                        "chat",
                        "messages",
                        List.of(
                                Map.of("role", "system", "content", "You are a helpful assistant"),
                                Map.of(
                                        "role",
                                        "user",
                                        "content",
                                        "Here are the items:\n"
                                                + "{{#items}}- {{name}}: {{description}}\n"
                                                + "{{/items}}")));

        BraintrustPrompt braintrustPrompt =
                new BraintrustPrompt(promptData(prompt, Map.of("model", "gpt-4o-mini")));

        Map<String, Object> parameters =
                Map.of(
                        "items",
                        List.of(
                                Map.of("name", "Apple", "description", "A red fruit"),
                                Map.of("name", "Banana", "description", "A yellow fruit"),
                                Map.of("name", "Cherry", "description", "A small red fruit")));

        List<Map<String, Object>> rendered = braintrustPrompt.renderMessages(parameters);

        assertEquals(2, rendered.size());
        assertEquals(
                "Here are the items:\n"
                        + "- Apple: A red fruit\n"
                        + "- Banana: A yellow fruit\n"
                        + "- Cherry: A small red fruit\n",
                rendered.get(1).get("content"));
    }

    @Test
    void testRenderMessagesWithEmptyList() {
        Map<String, Object> prompt =
                Map.of(
                        "type",
                        "chat",
                        "messages",
                        List.of(
                                Map.of(
                                        "role",
                                        "user",
                                        "content",
                                        "Items: {{#items}}{{name}} {{/items}}{{^items}}No items"
                                                + " found{{/items}}")));

        BraintrustPrompt braintrustPrompt =
                new BraintrustPrompt(promptData(prompt, Map.of("model", "gpt-4o-mini")));

        List<Map<String, Object>> rendered =
                braintrustPrompt.renderMessages(Map.of("items", List.of()));

        assertEquals(1, rendered.size());
        assertEquals("Items: No items found", rendered.get(0).get("content"));
    }

    @Test
    void testRenderMessagesWithConditional() {
        Map<String, Object> prompt =
                Map.of(
                        "type",
                        "chat",
                        "messages",
                        List.of(
                                Map.of("role", "system", "content", "You are a helpful assistant"),
                                Map.of(
                                        "role",
                                        "user",
                                        "content",
                                        "Hello {{name}}!{{#isAdmin}} You have admin"
                                                + " privileges.{{/isAdmin}}{{^isAdmin}} You are a"
                                                + " regular user.{{/isAdmin}}")));

        BraintrustPrompt braintrustPrompt =
                new BraintrustPrompt(promptData(prompt, Map.of("model", "gpt-4o-mini")));

        List<Map<String, Object>> adminMessages =
                braintrustPrompt.renderMessages(Map.of("name", "Alice", "isAdmin", true));
        assertEquals(
                "Hello Alice! You have admin privileges.", adminMessages.get(1).get("content"));

        List<Map<String, Object>> regularMessages =
                braintrustPrompt.renderMessages(Map.of("name", "Bob", "isAdmin", false));
        assertEquals("Hello Bob! You are a regular user.", regularMessages.get(1).get("content"));
    }

    @Test
    void testRenderMessagesWithNestedObjects() {
        Map<String, Object> prompt =
                Map.of(
                        "type",
                        "chat",
                        "messages",
                        List.of(
                                Map.of(
                                        "role",
                                        "user",
                                        "content",
                                        "User: {{user.firstName}} {{user.lastName}}\n"
                                                + "Email: {{user.contact.email}}\n"
                                                + "Phone: {{user.contact.phone}}")));

        BraintrustPrompt braintrustPrompt =
                new BraintrustPrompt(promptData(prompt, Map.of("model", "gpt-4o-mini")));

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

        List<Map<String, Object>> rendered = braintrustPrompt.renderMessages(parameters);

        assertEquals(1, rendered.size());
        assertEquals(
                "User: John Doe\nEmail: john@example.com\nPhone: 555-1234",
                rendered.get(0).get("content"));
    }

    @Test
    void testRenderMessagesWithInvertedSection() {
        Map<String, Object> prompt =
                Map.of(
                        "type",
                        "chat",
                        "messages",
                        List.of(
                                Map.of(
                                        "role",
                                        "user",
                                        "content",
                                        "{{#hasError}}Error:"
                                                + " {{errorMessage}}{{/hasError}}{{^hasError}}All"
                                                + " systems operational{{/hasError}}")));

        BraintrustPrompt braintrustPrompt =
                new BraintrustPrompt(promptData(prompt, Map.of("model", "gpt-4o-mini")));

        assertEquals(
                "All systems operational",
                braintrustPrompt.renderMessages(Map.of("hasError", false)).get(0).get("content"));
        assertEquals(
                "Error: Database connection failed",
                braintrustPrompt
                        .renderMessages(
                                Map.of(
                                        "hasError",
                                        true,
                                        "errorMessage",
                                        "Database connection failed"))
                        .get(0)
                        .get("content"));
    }

    @Test
    void testRenderMessagesWithComplexTypes() {
        Map<String, Object> prompt =
                Map.of(
                        "type",
                        "chat",
                        "messages",
                        List.of(
                                Map.of(
                                        "role",
                                        "user",
                                        "content",
                                        "Count: {{count}}\n"
                                                + "Price: ${{price}}\n"
                                                + "Enabled: {{enabled}}")));

        BraintrustPrompt braintrustPrompt =
                new BraintrustPrompt(promptData(prompt, Map.of("model", "gpt-4o-mini")));

        List<Map<String, Object>> rendered =
                braintrustPrompt.renderMessages(
                        Map.of("count", 42, "price", 19.99, "enabled", true));

        assertEquals(1, rendered.size());
        assertEquals("Count: 42\nPrice: $19.99\nEnabled: true", rendered.get(0).get("content"));
    }
}
