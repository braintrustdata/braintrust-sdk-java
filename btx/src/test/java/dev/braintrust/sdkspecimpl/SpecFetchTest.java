package dev.braintrust.sdkspecimpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the real fetch script, curl, and tar against a local archive server. */
class SpecFetchTest {
    private static final String SHA = "0123456789012345678901234567890123456789";
    private static final String CONTENT = "name: archive-fixture\nprovider: openai\n";
    @TempDir Path directory;
    private HttpServer server;
    private Path bin;
    private Path destination;

    @BeforeEach
    void setUp() throws Exception {
        Path source = directory.resolve("source/braintrust-spec-" + SHA);
        Path spec = source.resolve("test/llm_span/openai/fixture.yaml");
        Files.createDirectories(spec.getParent());
        Files.writeString(spec, CONTENT);
        Path archive = directory.resolve("archive.tar.gz");
        run(
                List.of(
                        "tar",
                        "-czf",
                        archive.toString(),
                        "-C",
                        source.getParent().toString(),
                        source.getFileName().toString()),
                Map.of(),
                0);
        byte[] bytes = Files.readAllBytes(archive);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    if (path.equals("/" + SHA + ".tar.gz") || path.equals("/v0.0.12.tar.gz")) {
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } else {
                        exchange.sendResponseHeaders(404, -1);
                    }
                    exchange.close();
                });
        server.start();
        destination = directory.resolve("downloaded specs");
        bin = Files.createDirectory(directory.resolve("bin"));
        executable(
                "curl",
                """
                #!/usr/bin/env bash
                args=()
                for arg in "$@"; do
                  case "$arg" in
                    https://github.com/braintrustdata/braintrust-spec/archive/*)
                      arg="$FIXTURE_URL/${arg##*/}" ;;
                  esac
                  args+=("$arg")
                done
                exec /usr/bin/curl --noproxy '*' "${args[@]}"
                """);
        executable(
                "git",
                """
                #!/usr/bin/env bash
                if [[ "$2" == "--exit-code" ]]; then
                  [[ "$4" == "v0.0.12" ]]
                  exit $?
                fi
                cat "$FIXTURE_REFS"
                """);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private void executable(String name, String content) throws Exception {
        Path file = Files.writeString(bin.resolve(name), content);
        assertTrue(file.toFile().setExecutable(true));
    }

    private int fetch(String ref, String advertisedRefs) throws Exception {
        Path refs = Files.writeString(directory.resolve("refs"), advertisedRefs);
        return run(
                List.of(
                        "bash",
                        Path.of("scripts/spec-fetch.sh").toAbsolutePath().toString(),
                        ref,
                        destination.toString()),
                Map.of(
                        "PATH", bin + ":" + System.getenv("PATH"),
                        "FIXTURE_URL", "http://127.0.0.1:" + server.getAddress().getPort(),
                        "FIXTURE_REFS", refs.toString()),
                null);
    }

    private int run(List<String> command, Map<String, String> environment, Integer expected)
            throws Exception {
        Path log = Files.createTempFile(directory, "process", ".log");
        ProcessBuilder builder =
                new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Process timed out: " + command);
            int status = process.exitValue();
            if (expected != null) assertEquals(expected.intValue(), status, Files.readString(log));
            return status;
        } finally {
            process.destroyForcibly();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {SHA, "v0.0.12"})
    void downloadsTagsAndCommitsNotAdvertisedAsBranchTips(String ref) throws Exception {
        assertEquals(0, fetch(ref, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\trefs/heads/main\n"));
        assertEquals(
                CONTENT,
                Files.readString(destination.resolve("test/llm_span/openai/fixture.yaml")));
    }

    @Test
    void downloadsCommitEvenWhenRefListingExceedsPipeCapacity() throws Exception {
        String refs =
                SHA
                        + "\tHEAD\n"
                        + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\trefs/heads/other\n"
                                .repeat(4096);
        assertEquals(0, fetch(SHA, refs));
        assertEquals(
                CONTENT,
                Files.readString(destination.resolve("test/llm_span/openai/fixture.yaml")));
    }

    @Test
    void unavailableArchiveFailsWithoutExtractingSpecs() throws Exception {
        assertNotEquals(0, fetch("missing-ref", ""));
        assertFalse(Files.exists(destination.resolve("test/llm_span/openai/fixture.yaml")));
    }
}
