package dev.braintrust.config;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
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
        final Map<String, List<String>> builderMethodOverrides =
                Map.of("spanCustomizers", List.of("addSpanCustomizer"));
        // Get all fields from BraintrustConfig
        Field[] configFields = BraintrustConfig.class.getDeclaredFields();
        Set<String> configFieldNames =
                Arrays.stream(configFields).map(Field::getName).collect(Collectors.toSet());

        // Get all methods from Builder
        Method[] builderMethods = BraintrustConfig.Builder.class.getDeclaredMethods();
        Set<String> builderMethodNames =
                Arrays.stream(builderMethods).map(Method::getName).collect(Collectors.toSet());

        // Validate overrides even when the field is skipped or its method list is empty.
        builderMethodOverrides.forEach(
                (fieldName, methodNames) -> {
                    assertTrue(
                            configFieldNames.contains(fieldName),
                            "Builder override references unknown field: " + fieldName);
                    for (String methodName : methodNames) {
                        assertTrue(
                                builderMethodNames.contains(methodName),
                                "Builder override for field "
                                        + fieldName
                                        + " references unknown method: "
                                        + methodName);
                    }
                });

        // For each field, verify there's a corresponding builder method
        for (Field field : configFields) {
            String configFieldName = field.getName();
            // An explicit empty list exempts a field from requiring builder methods.
            for (String builderMethodName :
                    builderMethodOverrides.getOrDefault(
                            configFieldName, List.of(configFieldName))) {
                assertTrue(
                        builderMethodNames.contains(builderMethodName),
                        "Builder is missing method "
                                + builderMethodName
                                + " for field: "
                                + configFieldName);
            }
        }
    }

    @Test
    void attachmentUploaderSettingsKeepDefaultsAndAllowOverrides() {
        var defaults = BraintrustConfig.builder().build();
        assertEquals(1024, defaults.attachmentUploaderQueueSize());
        assertEquals(Duration.ofSeconds(60), defaults.attachmentUploaderRequestTimeout());
        assertEquals(8, defaults.attachmentUploaderMaxRetries());
        assertEquals(Duration.ofMillis(500), defaults.attachmentUploaderInitialRetryDelay());

        var configured =
                BraintrustConfig.builder()
                        .attachmentUploaderQueueSize(64)
                        .attachmentUploaderRequestTimeout(Duration.ofSeconds(5))
                        .attachmentUploaderMaxRetries(2)
                        .attachmentUploaderInitialRetryDelay(Duration.ofMillis(25))
                        .build();
        assertEquals(64, configured.attachmentUploaderQueueSize());
        assertEquals(Duration.ofSeconds(5), configured.attachmentUploaderRequestTimeout());
        assertEquals(2, configured.attachmentUploaderMaxRetries());
        assertEquals(Duration.ofMillis(25), configured.attachmentUploaderInitialRetryDelay());
    }

    @Test
    void rejectsOtelExportBatchSizeLargerThanQueue() {
        var thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                BraintrustConfig.builder()
                                        .otelMaxQueueSize(512)
                                        .otelMaxExportBatchSize(1024)
                                        .build());

        assertTrue(thrown.getMessage().contains("must not exceed"));
    }

    @Test
    void testDefaultSslContextWhenNotProvided() throws Exception {
        // Create config without custom SSL context
        var config =
                BraintrustConfig.builder()
                        .apiKey("test-key")
                        .apiUrl("https://api.braintrust.dev")
                        .defaultProjectName("test-project")
                        .build();

        // Verify config has default SSL context and trust manager
        assertNotNull(config.sslContext());
        assertNotNull(config.x509TrustManager());

        // Should be the system defaults
        assertEquals(SSLContext.getDefault(), config.sslContext());
    }

    @Test
    void explicitNullSpanOriginEnvironmentDisablesDetection() {
        var config =
                BraintrustConfig.of(
                        "BRAINTRUST_ENVIRONMENT_TYPE",
                        BaseConfig.NULL_OVERRIDE,
                        "BRAINTRUST_ENVIRONMENT_NAME",
                        BaseConfig.NULL_OVERRIDE,
                        "CI",
                        "true",
                        "AWS_EXECUTION_ENV",
                        "AWS_ECS_FARGATE");

        assertTrue(config.spanOriginEnvironment().isEmpty());
    }

    @Test
    void explicitEnvironmentNameWithoutTypeIsPreserved() {
        var config =
                BraintrustConfig.of(
                        "BRAINTRUST_ENVIRONMENT_TYPE",
                        BaseConfig.NULL_OVERRIDE,
                        "BRAINTRUST_ENVIRONMENT_NAME",
                        "staging",
                        "CI",
                        "true");

        var environment = config.spanOriginEnvironment().orElseThrow();
        assertNull(environment.type());
        assertEquals("staging", environment.name());
    }

    @Test
    void awsExecutionEnvClassifiesEcsBeforeLambda() {
        var config =
                BraintrustConfig.of(
                        "GITHUB_ACTIONS", "true", "AWS_EXECUTION_ENV", "AWS_ECS_FARGATE");

        var environment = config.spanOriginEnvironment().orElseThrow();
        assertEquals("server", environment.type());
        assertEquals("ecs", environment.name());
    }

    @Test
    void awsExecutionEnvClassifiesLambdaWhenLambdaSpecific() {
        var config =
                BraintrustConfig.of(
                        "GITHUB_ACTIONS", "true", "AWS_EXECUTION_ENV", "AWS_Lambda_java17");

        var environment = config.spanOriginEnvironment().orElseThrow();
        assertEquals("server", environment.type());
        assertEquals("aws_lambda", environment.name());
    }
}
