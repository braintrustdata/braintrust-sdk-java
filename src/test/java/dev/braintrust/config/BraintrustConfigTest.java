package dev.braintrust.config;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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

    @Test
    public void testBuilderEqualsEnv() {
        var fromEnv =
                BraintrustConfig.of(
                        "BRAINTRUST_API_KEY", "testkey",
                        "BRAINTRUST_DEFAULT_PROJECT_ID", "unit-test");
        var fromBuilder =
                BraintrustConfig.builder().apiKey("testkey").defaultProjectId("unit-test").build();
        var otherBuilder =
                BraintrustConfig.builder().apiKey("otherkey").defaultProjectId("unit-test").build();
        assertEquals(fromEnv, fromBuilder);
        assertNotEquals(fromEnv, otherBuilder);
    }

    @Test
    public void testBuilderHasMethodForEveryField() {
        List<String> fieldsToSkip = List.of("envOverrides");
        // Get all fields from BraintrustConfig
        Field[] configFields = BraintrustConfig.class.getDeclaredFields();

        // Get all methods from Builder
        Method[] builderMethods = BraintrustConfig.Builder.class.getDeclaredMethods();
        Set<String> builderMethodNames =
                Arrays.stream(builderMethods).map(Method::getName).collect(Collectors.toSet());

        // For each field, verify there's a corresponding builder method
        for (Field field : configFields) {
            String configFieldName = field.getName();
            // Skip internal fields
            if (fieldsToSkip.contains(configFieldName)) {
                continue;
            }
            assertTrue(
                    builderMethodNames.contains(configFieldName),
                    "Builder is missing method for field: " + configFieldName);
        }
    }
}
