package dev.braintrust.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import javax.annotation.Nonnull;
import org.junit.jupiter.api.Test;

public class BaseConfigTest {
    private static final String TEST_ENV_VAR = "BRAINTRUST_TEST_VAR";

    @Test
    void testGetConfigHierarchy() {
        // NOTE: gradle exports TEST_VAR1 and TEST_VAR2 into this test env
        TestConfig config = new TestConfig(Map.of("TEST_VAR1", "override"));

        // overrides take precedence
        assertEquals("override", config.getConfig("TEST_VAR1", "default"));
        // then envars
        assertEquals("fromenv2", config.getConfig("TEST_VAR2", "default"));
        // finally, defaults
        assertEquals("default", config.getConfig("NON_EXISTENT_VAR", "default"));
    }

    @Test
    void testGetConfigWithNullDefault() {
        TestConfig config = new TestConfig(Map.of());
        String result = config.getConfig("NON_EXISTENT_VAR", null, String.class);
        assertNull(result);
    }

    @Test
    void testGetRequiredConfigSuccess() {
        TestConfig config = new TestConfig(Map.of("REQUIRED_VAR", "required_value"));

        String result = config.getRequiredConfig("REQUIRED_VAR");
        assertEquals("required_value", result);
    }

    @Test
    void testGetRequiredConfigFailure() {
        TestConfig config = new TestConfig(Map.of());

        assertThrows(
                Exception.class,
                () -> {
                    config.getRequiredConfig("NON_EXISTENT_VAR");
                });
    }

    @Test
    void testGetRequiredConfigWithType() {
        TestConfig config = new TestConfig(Map.of("INT_VAR", "123"));

        Integer result = config.getRequiredConfig("INT_VAR", Integer.class);
        assertEquals(123, result);
    }

    @Test
    void testCastBoolean() {
        TestConfig config = new TestConfig(Map.of());

        Boolean result1 = config.cast("true", Boolean.class);
        assertTrue(result1);

        Boolean result2 = config.cast("false", Boolean.class);
        assertFalse(result2);

        boolean result3 = config.cast("true", boolean.class);
        assertTrue(result3);
    }

    @Test
    void testCastInteger() {
        TestConfig config = new TestConfig(Map.of());

        Integer result1 = config.cast("42", Integer.class);
        assertEquals(42, result1);

        int result2 = config.cast("123", int.class);
        assertEquals(123, result2);
    }

    @Test
    void testCastLong() {
        TestConfig config = new TestConfig(Map.of());

        Long result1 = config.cast("9223372036854775807", Long.class);
        assertEquals(9223372036854775807L, result1);

        long result2 = config.cast("456", long.class);
        assertEquals(456L, result2);
    }

    @Test
    void testCastFloat() {
        TestConfig config = new TestConfig(Map.of());

        Float result1 = config.cast("3.14", Float.class);
        assertEquals(3.14f, result1, 0.001f);

        float result2 = config.cast("2.71", float.class);
        assertEquals(2.71f, result2, 0.001f);
    }

    @Test
    void testCastDouble() {
        TestConfig config = new TestConfig(Map.of());

        Double result1 = config.cast("3.14159", Double.class);
        assertEquals(3.14159, result1, 0.00001);

        double result2 = config.cast("2.71828", double.class);
        assertEquals(2.71828, result2, 0.00001);
    }

    @Test
    void testCastUnsupportedType() {
        TestConfig config = new TestConfig(Map.of());

        assertThrows(
                Exception.class,
                () -> {
                    config.cast("test", Object.class);
                });
    }

    @Test
    void testGetEnvValueFromOverrides() {
        TestConfig config = new TestConfig(Map.of("OVERRIDE_VAR", "override_value"));

        String result = config.getEnvValue("OVERRIDE_VAR");
        assertEquals("override_value", result);
    }

    @Test
    void testGetEnvValueNonExistent() {
        TestConfig config = new TestConfig(Map.of());

        String result = config.getEnvValue("NON_EXISTENT_VAR_12345");
        assertNull(result);
    }

    @Test
    void testNullSentinalHandling() {
        // Test the NULL_OVERRIDE sentinel behavior
        TestConfig config = new TestConfig(Map.of("NULL_VAR", BaseConfig.NULL_OVERRIDE));

        String result = config.getEnvValue("NULL_VAR");
        assertNull(result);
    }

    @Test
    void testGetConfigWithNonNullDefaultThrowsOnNull() {
        TestConfig config = new TestConfig(Map.of());

        assertThrows(
                NullPointerException.class,
                () -> {
                    config.getConfig("TEST", (String) null);
                });
    }

    @Test
    void testIntegrationWithAllTypes() {
        Map<String, String> overrides =
                Map.of(
                        "STRING_VAR", "hello",
                        "BOOL_VAR", "true",
                        "INT_VAR", "42",
                        "LONG_VAR", "123456789",
                        "FLOAT_VAR", "3.14",
                        "DOUBLE_VAR", "2.71828");

        TestConfig config = new TestConfig(overrides);

        assertEquals("hello", config.getConfig("STRING_VAR", "default"));
        assertEquals(true, config.getConfig("BOOL_VAR", false));
        assertEquals(42, config.getConfig("INT_VAR", 0));
        assertEquals(123456789L, config.getConfig("LONG_VAR", 0L));
        assertEquals(3.14f, config.getConfig("FLOAT_VAR", 0.0f), 0.001f);
        assertEquals(2.71828, config.getConfig("DOUBLE_VAR", 0.0), 0.00001);
    }

    @Test
    void testEquals() {
        var cfg1 = new TestConfig(Map.of("FOO", "bar"));
        var cfg2 = new TestConfig(Map.of("FOO", "bar"));
        var cfg3 = new TestConfig(Map.of("FOO", "baz"));
        assertEquals(cfg1, cfg2);
        assertNotEquals(cfg1, cfg3);
    }

    static class TestConfig extends BaseConfig {
        TestConfig(Map<String, String> envOverrides) {
            super(envOverrides);
        }

        @Nonnull
        @Override
        public <T> T getConfig(@Nonnull String settingName, @Nonnull T defaultValue) {
            return super.getConfig(settingName, defaultValue);
        }

        @Override
        public <T> T getConfig(
                @Nonnull String settingName, T defaultValue, @Nonnull Class<T> settingClass) {
            return super.getConfig(settingName, defaultValue, settingClass);
        }

        @Nonnull
        @Override
        public String getRequiredConfig(String settingName) {
            return super.getRequiredConfig(settingName);
        }

        @Nonnull
        @Override
        public <T> T getRequiredConfig(String settingName, Class<T> settingClass) {
            return super.getRequiredConfig(settingName, settingClass);
        }
    }
}
