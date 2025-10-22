package dev.braintrust;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.config.BraintrustConfig;
import io.opentelemetry.api.GlobalOpenTelemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BraintrustTest {

    @BeforeEach
    void setUp() {
        // Reset global OpenTelemetry before each test
        GlobalOpenTelemetry.resetForTest();
    }

    @AfterEach
    void tearDown() {
        // Clean up after tests
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void testOfCreatesNewInstance() {
        var config =
                BraintrustConfig.of(
                        "BRAINTRUST_DEFAULT_PROJECT_NAME",
                        "test-project",
                        "BRAINTRUST_API_KEY",
                        "test-key");
        var braintrust = Braintrust.of(config);

        assertNotNull(braintrust);
        assertNotNull(braintrust.config());
        assertNotNull(braintrust.apiClient());
        assertNotNull(braintrust.promptLoader());
        assertEquals(config, braintrust.config());
    }

    @Test
    void testGetCreatesGlobalInstance() {
        var config =
                BraintrustConfig.of(
                        "BRAINTRUST_DEFAULT_PROJECT_NAME",
                        "test-project",
                        "BRAINTRUST_API_KEY",
                        "test-key");
        var braintrust1 = Braintrust.get(config);
        var braintrust2 = Braintrust.get();

        // Should return the same instance
        assertSame(braintrust1, braintrust2);
    }

    @Test
    void testOpenTelemetryCreate() {
        var config =
                BraintrustConfig.of(
                        "BRAINTRUST_DEFAULT_PROJECT_NAME",
                        "test-project",
                        "BRAINTRUST_API_KEY",
                        "test-key");
        var braintrust = Braintrust.of(config);

        var openTelemetry = braintrust.openTelemetryCreate(false); // Don't register global
        assertNotNull(openTelemetry);
        assertNotNull(openTelemetry.getTracer("test"));
    }

    @Test
    void testOpenTelemetryCreateRegistersGlobal() {
        var config =
                BraintrustConfig.of(
                        "BRAINTRUST_DEFAULT_PROJECT_NAME",
                        "test-project",
                        "BRAINTRUST_API_KEY",
                        "test-key");
        var braintrust = Braintrust.of(config);

        var openTelemetry = braintrust.openTelemetryCreate(true); // Register global
        assertNotNull(openTelemetry);

        // Verify it was registered globally
        var globalOtel = GlobalOpenTelemetry.get();
        assertNotNull(globalOtel);
        assertNotNull(globalOtel.getTracer("test"));
    }

    @Test
    void testOpenTelemetryCreateDefaultRegistersGlobal() {
        var config =
                BraintrustConfig.of(
                        "BRAINTRUST_DEFAULT_PROJECT_NAME",
                        "test-project",
                        "BRAINTRUST_API_KEY",
                        "test-key");
        var braintrust = Braintrust.of(config);

        var openTelemetry = braintrust.openTelemetryCreate(); // Default should register global
        assertNotNull(openTelemetry);

        // Verify it was registered globally
        var globalOtel = GlobalOpenTelemetry.get();
        assertNotNull(globalOtel);
    }

    @Test
    void testEvalBuilder() {
        var config =
                BraintrustConfig.of(
                        "BRAINTRUST_DEFAULT_PROJECT_ID",
                        "test-project-id",
                        "BRAINTRUST_API_KEY",
                        "test-key");
        var braintrust = Braintrust.of(config);

        var evalBuilder = braintrust.<String, String>evalBuilder();
        assertNotNull(evalBuilder);
    }

    @Test
    void testMultipleOfCallsCreateIndependentInstances() {
        var config1 =
                BraintrustConfig.of(
                        "BRAINTRUST_DEFAULT_PROJECT_NAME",
                        "project-1",
                        "BRAINTRUST_API_KEY",
                        "test-key");
        var config2 =
                BraintrustConfig.of(
                        "BRAINTRUST_DEFAULT_PROJECT_NAME",
                        "project-2",
                        "BRAINTRUST_API_KEY",
                        "test-key");

        var braintrust1 = Braintrust.of(config1);
        var braintrust2 = Braintrust.of(config2);

        // Should be different instances
        assertNotSame(braintrust1, braintrust2);
        assertNotEquals(
                braintrust1.config().defaultProjectName().get(),
                braintrust2.config().defaultProjectName().get());
    }

    @Test
    void testGetReturnsSameInstanceAfterInitialization() {
        var config =
                BraintrustConfig.of(
                        "BRAINTRUST_DEFAULT_PROJECT_NAME",
                        "test-project",
                        "BRAINTRUST_API_KEY",
                        "test-key");

        // First call initializes
        var braintrust1 = Braintrust.get(config);

        // Subsequent calls return same instance
        var braintrust2 = Braintrust.get();
        var braintrust3 = Braintrust.get(config);

        assertSame(braintrust1, braintrust2);
        assertSame(braintrust1, braintrust3);
    }
}
