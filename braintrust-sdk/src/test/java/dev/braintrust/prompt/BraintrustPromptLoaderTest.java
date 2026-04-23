package dev.braintrust.prompt;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.TestHarness;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BraintrustPromptLoaderTest {
    private TestHarness testHarness;

    @BeforeEach
    void beforeEach() {
        testHarness = TestHarness.setup();
    }

    @Test
    void testLoadPromptBySlug() {
        BraintrustPromptLoader loader = testHarness.braintrust().promptLoader();

        BraintrustPrompt prompt = loader.load("kind-greeter-0bd1");

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
    void testLoadPromptWithDefaults() {
        BraintrustPromptLoader loader = testHarness.braintrust().promptLoader();

        BraintrustPrompt prompt =
                loader.load(
                        BraintrustPromptLoader.PromptLoadRequest.builder()
                                .promptSlug("kind-greeter-0bd1")
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
                                .promptSlug("kind-greeter-0bd1")
                                .projectName(TestHarness.defaultProjectName())
                                .build());

        assertNotNull(prompt);
    }
}
