package dev.braintrust.prompt;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.TestHarness;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BraintrustPromptLoaderTest {
    private static final String PROMPT_NAME = "kind-greeter";

    private static TestHarness.PromptInfo PROMPT_INFO;

    private TestHarness testHarness;

    @BeforeAll
    static void beforeAll() {
        var harness = TestHarness.setup();
        PROMPT_INFO =
                harness.ensureRemotePrompt(
                        PROMPT_NAME,
                        List.of(
                                // oldest version: simple system message
                                new TestHarness.PromptVersionDef(
                                        List.of(
                                                Map.of(
                                                        "role",
                                                        "system",
                                                        "content",
                                                        "this is an old version")),
                                        null),
                                // latest version: user message with template + model
                                new TestHarness.PromptVersionDef(
                                        List.of(
                                                Map.of(
                                                        "role",
                                                        "user",
                                                        "content",
                                                        "Hello {{name}}, be kind!")),
                                        "gpt-4o-mini")));
    }

    @BeforeEach
    void beforeEach() {
        testHarness = TestHarness.setup();
    }

    @Test
    void testLoadPromptBySlug() {
        BraintrustPromptLoader loader = testHarness.braintrust().promptLoader();

        BraintrustPrompt prompt = loader.load(PROMPT_INFO.slug());

        assertNotNull(prompt);

        // Render with the template variable the prompt expects
        Map<String, Object> parameters = Map.of("name", "Bob");
        List<Map<String, Object>> renderedMessages = prompt.renderMessages(parameters);

        assertFalse(renderedMessages.isEmpty());
        // The user message should contain the rendered name
        boolean nameRendered =
                renderedMessages.stream()
                        .anyMatch(
                                msg -> {
                                    Object content = msg.get("content");
                                    return content instanceof String
                                            && ((String) content).contains("Bob");
                                });
        assertTrue(nameRendered, "Expected rendered messages to contain the substituted name");
    }

    @Test
    void testLoadPromptBySlugWithVersion() {
        BraintrustPromptLoader loader = testHarness.braintrust().promptLoader();

        // Fetch the oldest version (index 0) by its version ID
        String oldVersion = PROMPT_INFO.versionIds().get(0);
        BraintrustPrompt prompt =
                loader.load(
                        BraintrustPromptLoader.PromptLoadRequest.builder()
                                .promptSlug(PROMPT_INFO.slug())
                                .version(oldVersion)
                                .build());

        assertNotNull(prompt);

        List<Map<String, Object>> messages = prompt.renderMessages(Map.of("name", "Bob"));
        assertEquals("system", messages.get(0).get("role"));
        assertEquals("this is an old version", messages.get(0).get("content"));
    }

    @Test
    void testLoadPromptWithDefaults() {
        BraintrustPromptLoader loader = testHarness.braintrust().promptLoader();

        BraintrustPrompt prompt =
                loader.load(
                        BraintrustPromptLoader.PromptLoadRequest.builder()
                                .promptSlug(PROMPT_INFO.slug())
                                .defaults("max_tokens", "2000", "top_p", "0.95")
                                .build());

        assertNotNull(prompt);

        // Verify defaults are applied
        Map<String, Object> options = prompt.getOptions();
        assertEquals("2000", options.get("max_tokens"));
        assertEquals("0.95", options.get("top_p"));

        // Verify existing options from the real prompt are preserved (not clobbered by defaults)
        assertTrue(options.containsKey("model"), "Expected 'model' option to be present");
    }

    @Test
    void testLoadPromptWithProjectName() {
        BraintrustPromptLoader loader = testHarness.braintrust().promptLoader();

        // Load the prompt with an explicit project name (not relying on the config default)
        BraintrustPrompt prompt =
                loader.load(
                        BraintrustPromptLoader.PromptLoadRequest.builder()
                                .promptSlug(PROMPT_INFO.slug())
                                .projectName(TestHarness.defaultProjectName())
                                .build());

        assertNotNull(prompt);
    }
}
