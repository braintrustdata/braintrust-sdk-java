package dev.braintrust.devserver;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.eval.Scorer;
import org.junit.jupiter.api.Test;

class RemoteEvalTest {

    @Test
    void testProjectNameFallbackToDefault() {
        BraintrustConfig config =
                BraintrustConfig.builder()
                        .apiKey("test-key")
                        .defaultProjectName("default-project")
                        .build();

        RemoteEval<String, String> eval =
                RemoteEval.<String, String>builder()
                        .name("test-eval")
                        .config(config)
                        .taskFunction(input -> input)
                        .scorer(Scorer.of("test", result -> 1.0))
                        .build();

        assertEquals("default-project", eval.getProjectName());
    }

    @Test
    void testProjectNameExplicitOverridesDefault() {
        BraintrustConfig config =
                BraintrustConfig.builder()
                        .apiKey("test-key")
                        .defaultProjectName("default-project")
                        .build();

        RemoteEval<String, String> eval =
                RemoteEval.<String, String>builder()
                        .name("test-eval")
                        .projectName("explicit-project")
                        .config(config)
                        .taskFunction(input -> input)
                        .scorer(Scorer.of("test", result -> 1.0))
                        .build();

        assertEquals("explicit-project", eval.getProjectName());
    }

    @Test
    void testProjectNameRequiredWhenNoDefault() {
        // Create a minimal config without defaults
        // Note: This test may pass if environment variables provide defaults
        BraintrustConfig config = BraintrustConfig.builder().apiKey("test-key").build();

        // Only throw if config truly has no defaults
        if (config.defaultProjectName().isEmpty() && config.defaultProjectId().isEmpty()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            RemoteEval.<String, String>builder()
                                    .name("test-eval")
                                    .config(config)
                                    .taskFunction(input -> input)
                                    .scorer(Scorer.of("test", result -> 1.0))
                                    .build());
        } else {
            // If environment provides defaults, just verify build succeeds
            RemoteEval<String, String> eval =
                    RemoteEval.<String, String>builder()
                            .name("test-eval")
                            .config(config)
                            .taskFunction(input -> input)
                            .scorer(Scorer.of("test", result -> 1.0))
                            .build();
            assertNotNull(eval.getProjectName());
        }
    }

    @Test
    void testProjectNameFallsBackToProjectId() {
        BraintrustConfig config =
                BraintrustConfig.builder()
                        .apiKey("test-key")
                        .defaultProjectId("project-id-123")
                        .build();

        RemoteEval<String, String> eval =
                RemoteEval.<String, String>builder()
                        .name("test-eval")
                        .config(config)
                        .taskFunction(input -> input)
                        .scorer(Scorer.of("test", result -> 1.0))
                        .build();

        // Project name should be either the explicitly set project ID or from environment
        // If environment has a project name, it takes precedence
        assertNotNull(eval.getProjectName());
        // Only assert exact match if environment doesn't override
        if (config.defaultProjectName().isEmpty()) {
            assertEquals("project-id-123", eval.getProjectName());
        }
    }
}
