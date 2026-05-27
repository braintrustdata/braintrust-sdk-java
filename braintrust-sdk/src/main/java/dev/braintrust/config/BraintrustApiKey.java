package dev.braintrust.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;

final class BraintrustApiKey {
    static final String ENV_VAR = "BRAINTRUST_API_KEY";
    private static final String ENV_FILE = ".env.braintrust";
    private static final int SEARCH_PARENT_LIMIT = 64;

    private static volatile Function<String, String> getenv = System::getenv;
    private static volatile Supplier<Path> cwd =
            () -> Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();

    private final Map<String, String> envOverrides;
    private final AtomicReference<CompletableFuture<Optional<String>>> envFileApiKey =
            new AtomicReference<>();

    BraintrustApiKey(Map<String, String> envOverrides) {
        this.envOverrides = envOverrides;
    }

    String getRequired() {
        var apiKey = get();
        if (apiKey == null) {
            throw new RuntimeException("%s is required".formatted(ENV_VAR));
        }
        return apiKey;
    }

    private @Nullable String get() {
        if (envOverrides.containsKey(ENV_VAR)) {
            var override = envOverrides.get(ENV_VAR);
            return BaseConfig.NULL_OVERRIDE.equals(override) ? null : override;
        }

        var envValue = getEnvironmentValue();
        if (envValue != null && !envValue.trim().isEmpty()) {
            return envValue;
        }

        return getEnvFileApiKeyFuture().join().orElse(null);
    }

    private static @Nullable String getEnvironmentValue() {
        try {
            return getenv.apply(ENV_VAR);
        } catch (Exception e) {
            return null;
        }
    }

    private CompletableFuture<Optional<String>> getEnvFileApiKeyFuture() {
        var existing = envFileApiKey.get();
        if (existing != null) {
            return existing;
        }

        var created = findEnvFileApiKeyAsync();
        if (envFileApiKey.compareAndSet(null, created)) {
            return created;
        }
        var winner = envFileApiKey.get();
        return winner != null ? winner : created;
    }

    private static CompletableFuture<Optional<String>> findEnvFileApiKeyAsync() {
        Path start;
        try {
            start = cwd.get();
        } catch (Exception e) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        var candidates = envFileCandidates(start);
        if (candidates.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        var result = new CompletableFuture<Optional<String>>();
        var readResults = new AtomicReferenceArray<ReadResult>(candidates.size());
        var lock = new Object();

        for (int i = 0; i < candidates.size(); i++) {
            var index = i;
            var candidate = candidates.get(i);
            CompletableFuture.supplyAsync(() -> readEnvFile(candidate, index))
                    .whenComplete(
                            (readResult, error) -> {
                                var normalizedResult =
                                        error == null
                                                ? readResult
                                                : new ReadResult(index, null, false);
                                synchronized (lock) {
                                    if (result.isDone()) {
                                        return;
                                    }
                                    readResults.set(index, normalizedResult);
                                    completeIfResolved(readResults, result);
                                }
                            });
        }

        return result;
    }

    private static ArrayList<Path> envFileCandidates(Path start) {
        var candidates = new ArrayList<Path>();
        var dir = start;
        for (int depth = 0; depth <= SEARCH_PARENT_LIMIT && dir != null; depth++) {
            candidates.add(dir.resolve(ENV_FILE));
            var parent = dir.getParent();
            if (parent == null || parent.equals(dir)) {
                break;
            }
            dir = parent;
        }
        return candidates;
    }

    private static ReadResult readEnvFile(Path envFile, int index) {
        try {
            return new ReadResult(index, Files.readString(envFile, StandardCharsets.UTF_8), false);
        } catch (NoSuchFileException e) {
            return new ReadResult(index, null, true);
        } catch (IOException | RuntimeException e) {
            return new ReadResult(index, null, false);
        }
    }

    private static void completeIfResolved(
            AtomicReferenceArray<ReadResult> readResults,
            CompletableFuture<Optional<String>> result) {
        for (int i = 0; i < readResults.length(); i++) {
            var readResult = readResults.get(i);
            if (readResult == null) {
                return;
            }
            if (readResult.missing()) {
                continue;
            }
            if (readResult.contents() != null) {
                result.complete(parseApiKey(readResult.contents()));
            } else {
                result.complete(Optional.empty());
            }
            return;
        }
        result.complete(Optional.empty());
    }

    static Optional<String> parseApiKey(String contents) {
        String value = null;
        for (var line : contents.split("\\R", -1)) {
            var parsed = parseLine(line);
            if (parsed != null) {
                value = parsed;
            }
        }
        return value != null && !value.trim().isEmpty() ? Optional.of(value) : Optional.empty();
    }

    private static @Nullable String parseLine(String line) {
        var text = line.strip();
        if (text.isEmpty() || text.startsWith("#")) {
            return null;
        }

        if (text.startsWith("export")
                && text.length() > "export".length()
                && Character.isWhitespace(text.charAt("export".length()))) {
            text = text.substring("export".length()).stripLeading();
        }

        var equalsIndex = text.indexOf('=');
        if (equalsIndex <= 0) {
            return null;
        }

        var key = text.substring(0, equalsIndex).trim();
        if (!ENV_VAR.equals(key)) {
            return null;
        }

        return parseValue(text.substring(equalsIndex + 1).stripLeading());
    }

    private static String parseValue(String rawValue) {
        if (rawValue.isEmpty()) {
            return "";
        }

        var quote = rawValue.charAt(0);
        if (quote == '"' || quote == '\'') {
            return parseQuotedValue(rawValue, quote);
        }

        var commentIndex = rawValue.indexOf('#');
        var value = commentIndex >= 0 ? rawValue.substring(0, commentIndex) : rawValue;
        return value.trim();
    }

    private static String parseQuotedValue(String rawValue, char quote) {
        var value = new StringBuilder();
        for (int i = 1; i < rawValue.length(); i++) {
            var c = rawValue.charAt(i);
            if (c == quote) {
                return value.toString();
            }
            if (quote == '"' && c == '\\' && i + 1 < rawValue.length()) {
                var next = rawValue.charAt(++i);
                switch (next) {
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    default -> value.append(next);
                }
            } else {
                value.append(c);
            }
        }
        return value.toString();
    }

    static AutoCloseable useTestLookup(
            Function<String, String> testGetenv, Supplier<Path> testCwd) {
        var previousGetenv = getenv;
        var previousCwd = cwd;
        getenv = testGetenv;
        cwd = testCwd;
        return () -> {
            getenv = previousGetenv;
            cwd = previousCwd;
        };
    }

    private record ReadResult(int index, String contents, boolean missing) {}
}
