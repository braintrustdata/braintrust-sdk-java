package dev.braintrust.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BraintrustApiKeyTest {
    @TempDir private Path tempDir;

    @Test
    void explicitApiKeyWinsOverEnvironmentAndEnvFile() throws Exception {
        writeBraintrustEnv(tempDir, "BRAINTRUST_API_KEY=file-key\n");

        try (var ignored = useLookup(Map.of("BRAINTRUST_API_KEY", "env-key"), tempDir)) {
            var config = BraintrustConfig.builder().apiKey("explicit-key").build();

            assertEquals("explicit-key", config.apiKey());
        }
    }

    @Test
    void nonblankEnvironmentApiKeyWinsOverEnvFile() throws Exception {
        writeBraintrustEnv(tempDir, "BRAINTRUST_API_KEY=file-key\n");

        try (var ignored = useLookup(Map.of("BRAINTRUST_API_KEY", "env-key"), tempDir)) {
            var config = BraintrustConfig.fromEnvironment();

            assertEquals("env-key", config.apiKey());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankEnvironmentApiKeyFallsBackToEnvFile(String envValue) throws Exception {
        writeBraintrustEnv(tempDir, "BRAINTRUST_API_KEY=file-key\n");

        try (var ignored = useLookup(Map.of("BRAINTRUST_API_KEY", envValue), tempDir)) {
            var config = BraintrustConfig.fromEnvironment();

            assertEquals("file-key", config.apiKey());
        }
    }

    @Test
    void findsNearestParentEnvFile() throws Exception {
        var packageDir = tempDir.resolve("packages");
        var appDir = packageDir.resolve("app");
        Files.createDirectories(appDir);
        writeBraintrustEnv(tempDir, "BRAINTRUST_API_KEY=root-key\n");
        writeBraintrustEnv(packageDir, "BRAINTRUST_API_KEY=package-key\n");

        try (var ignored = useLookup(Map.of(), appDir)) {
            var config = BraintrustConfig.fromEnvironment();

            assertEquals("package-key", config.apiKey());
        }
    }

    @Test
    void envFileLookupUsesCwdWhenApiKeyIsRequested() throws Exception {
        var config = BraintrustConfig.fromEnvironment();
        writeBraintrustEnv(tempDir, "BRAINTRUST_API_KEY=file-key\n");

        try (var ignored = useLookup(Map.of(), tempDir)) {
            assertEquals("file-key", config.apiKey());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"OTHER=value\n", "BRAINTRUST_API_KEY=\"   \"\n"})
    void nearestEnvFileWithoutNonblankApiKeyStopsSearch(String nearestContents) throws Exception {
        var packageDir = tempDir.resolve("packages");
        var appDir = packageDir.resolve("app");
        Files.createDirectories(appDir);
        writeBraintrustEnv(tempDir, "BRAINTRUST_API_KEY=root-key\n");
        writeBraintrustEnv(packageDir, nearestContents);

        try (var ignored = useLookup(Map.of(), appDir)) {
            var config = BraintrustConfig.fromEnvironment();

            assertThrows(RuntimeException.class, config::apiKey);
        }
    }

    @Test
    void unreadableNearestEnvFileStopsSearch() throws Exception {
        var packageDir = tempDir.resolve("packages");
        var appDir = packageDir.resolve("app");
        Files.createDirectories(appDir);
        writeBraintrustEnv(tempDir, "BRAINTRUST_API_KEY=root-key\n");
        Files.createDirectory(packageDir.resolve(".env.braintrust"));

        try (var ignored = useLookup(Map.of(), appDir)) {
            var config = BraintrustConfig.fromEnvironment();

            assertThrows(RuntimeException.class, config::apiKey);
        }
    }

    @Test
    void searchesCwdAndAtMost64Parents() throws Exception {
        var nested = tempDir;
        for (int i = 0; i < 65; i++) {
            nested = nested.resolve("d" + i);
        }
        Files.createDirectories(nested);
        writeBraintrustEnv(tempDir, "BRAINTRUST_API_KEY=too-high\n");

        try (var ignored = useLookup(Map.of(), nested)) {
            var config = BraintrustConfig.fromEnvironment();

            assertThrows(RuntimeException.class, config::apiKey);
        }

        writeBraintrustEnv(tempDir.resolve("d0"), "BRAINTRUST_API_KEY=boundary-key\n");
        try (var ignored = useLookup(Map.of(), nested)) {
            var config = BraintrustConfig.fromEnvironment();

            assertEquals("boundary-key", config.apiKey());
        }
    }

    @Test
    void supportsDotenvSyntaxAndIgnoresOtherVariables() throws Exception {
        writeBraintrustEnv(
                tempDir,
                """
                BRAINTRUST_DOTENV_ONLY_TEST_VAR=value
                export BRAINTRUST_API_KEY="quoted-key" # comment
                """);

        try (var ignored = useLookup(Map.of(), tempDir)) {
            var config = BraintrustConfig.fromEnvironment();

            assertEquals("quoted-key", config.apiKey());
            assertNull(config.getEnvValue("BRAINTRUST_DOTENV_ONLY_TEST_VAR"));
        }
    }

    private void writeBraintrustEnv(Path dir, String contents) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(".env.braintrust"), contents);
    }

    private AutoCloseable useLookup(Map<String, String> env, Path cwd) {
        return BraintrustApiKey.useTestLookup(env::get, () -> cwd);
    }
}
