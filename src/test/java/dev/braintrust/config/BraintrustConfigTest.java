package dev.braintrust.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BraintrustConfigTest {
    @Test
    void parentDefaultsToProjectName() {
        var defaultConfig =
                BraintrustConfig.of(
                        "BRAINTRUST_API_KEY",
                        "foobar",
                        "BRAINTRUST_DEFAULT_PROJECT_NAME",
                        "proj-name");
        assertEquals(
                "project_name:proj-name", defaultConfig.getBraintrustParentValue().orElseThrow());
    }

    @Test
    void parentUsesProjectId() {
        var defaultConfig =
                BraintrustConfig.of(
                        "BRAINTRUST_API_KEY", "foobar",
                        "BRAINTRUST_DEFAULT_PROJECT_NAME", "proj-name",
                        "BRAINTRUST_DEFAULT_PROJECT_ID", "12345");
        assertEquals(
                "project_id:" + defaultConfig.defaultProjectId().orElseThrow(),
                defaultConfig.getBraintrustParentValue().orElseThrow());
    }
}
