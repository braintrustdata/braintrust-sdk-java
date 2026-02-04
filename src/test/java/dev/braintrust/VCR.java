package dev.braintrust;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.recording.RecordSpecBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.annotation.concurrent.ThreadSafe;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/** VCR (Video Cassette Recorder) for recording and replaying HTTP interactions. */
@Slf4j
@ThreadSafe // implementation locks under the instance lock
public class VCR {
    public enum VcrMode {
        /** Playback from recorded cassettes (default) */
        REPLAY,
        /** Record interactions to cassettes */
        RECORD,
        /** Disable VCR (proxy calls but do not record) */
        OFF
    }

    private static final String CASSETTES_ROOT = "src/test/resources/cassettes/";

    private final Map<String, WireMockServer> proxyMap;
    @Getter private final VcrMode mode;
    private final Map<String, String> targetUrlToMappingsDir;
    private final List<String> textToNeverRecord;
    private boolean recordingStarted = false;

    public VCR(Map<String, String> targetUrlToCassettesDir) {
        this(targetUrlToCassettesDir, List.of());
    }

    public VCR(Map<String, String> targetUrlToCassettesDir, List<String> textToNeverRecord) {
        this(
                VcrMode.valueOf(System.getenv().getOrDefault("VCR_MODE", "replay").toUpperCase()),
                targetUrlToCassettesDir,
                textToNeverRecord);
    }

    private VCR(
            VcrMode mode,
            Map<String, String> targetUrlToCassettesDir,
            List<String> textToNeverRecord) {
        this.mode = mode;
        this.targetUrlToMappingsDir = Map.copyOf(targetUrlToCassettesDir);
        // Filter out null/empty strings
        this.textToNeverRecord =
                textToNeverRecord.stream().filter(s -> s != null && !s.isEmpty()).toList();

        // Create a WireMockServer for each provider
        this.proxyMap = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : targetUrlToCassettesDir.entrySet()) {
            String targetUrl = entry.getKey();
            String mappingsDir = entry.getValue();
            String cassettesDir = CASSETTES_ROOT + mappingsDir;

            createDirectoryStructure(cassettesDir);

            WireMockServer wireMock =
                    new WireMockServer(
                            wireMockConfig()
                                    .dynamicPort()
                                    .usingFilesUnderDirectory(cassettesDir)
                                    .extensions(
                                            new LoginBodyRedactingTransformer(),
                                            new ForbiddenTextCheckingTransformer(
                                                    this.textToNeverRecord)));
            proxyMap.put(targetUrl, wireMock);
        }
    }

    private void createDirectoryStructure(String baseDir) {
        try {
            Path mappingsDir = Paths.get(baseDir, "mappings");
            Path filesDir = Paths.get(baseDir, "__files");
            Files.createDirectories(mappingsDir);
            Files.createDirectories(filesDir);
        } catch (Exception e) {
            log.warn("Failed to create directory structure: {}", e.getMessage());
        }
    }

    public synchronized void start() {
        for (WireMockServer wireMock : proxyMap.values()) {
            if (!wireMock.isRunning()) {
                wireMock.start();
            }
        }
        startRecording();
    }

    public synchronized void stop() {
        stopRecording();
        for (WireMockServer wireMock : proxyMap.values()) {
            if (wireMock.isRunning()) {
                wireMock.stop();
            }
        }
    }

    /**
     * Get the URL for the targetUrl
     *
     * <p>In REPLAY/RECORD modes: returns WireMock URL (e.g. https://api.openai.com/v1 -->
     * http://localhost:1234)
     *
     * <p>In OFF mode: returns the real target URL to bypass WireMock entirely
     */
    public synchronized String getUrlForTargetBase(String targetUrl) {
        assertStarted();
        if (mode == VcrMode.OFF) {
            return targetUrl;
        }
        WireMockServer wireMock = proxyMap.get(targetUrl);
        if (wireMock == null) {
            throw new IllegalArgumentException("Unknown target URL: " + targetUrl);
        }
        return wireMock.baseUrl();
    }

    private void startRecording() {
        if (mode == VcrMode.RECORD && !recordingStarted) {
            targetUrlToMappingsDir.keySet().forEach(this::startRecording);
            recordingStarted = true;
        } else if (mode == VcrMode.REPLAY) {
            log.info("️VCR_MODE=replay: Using recorded stubs from cassettes/");
            loadAndCreateProgrammaticStubs();
        } else if (mode == VcrMode.OFF) {
            targetUrlToMappingsDir
                    .keySet()
                    .forEach(url -> log.info("VCR_MODE=off: wiring clients directly to {}", url));
        }
    }

    private void startRecording(String targetBaseUrl) {
        WireMockServer wireMock = proxyMap.get(targetBaseUrl);
        if (wireMock == null) {
            throw new IllegalArgumentException("Unknown target URL: " + targetBaseUrl);
        }

        String mappingsDir = targetUrlToMappingsDir.get(targetBaseUrl);
        log.info("VCR_MODE=record: Proxying to {}", targetBaseUrl);
        log.info("Cassettes will be saved to: cassettes/{}", mappingsDir);

        RecordSpecBuilder recordSpec =
                new RecordSpecBuilder()
                        .forTarget(targetBaseUrl)
                        .captureHeader("Content-Type")
                        // .captureHeader("Authorization", true)
                        .extractTextBodiesOver(0) // Always extract bodies
                        .makeStubsPersistent(true) // Save to disk
                        // Auto-detect body matching based on Content-Type:
                        // - JSON (application/json) -> equalToJson with ignoreArrayOrder=true,
                        //   ignoreExtraElements=false
                        // - XML -> equalToXml
                        // - Binary (application/x-protobuf, etc.) -> equalTo (binary matching)
                        .chooseBodyMatchTypeAutomatically(true, false, false)
                        // Remove API keys from login endpoint recordings, then check for forbidden
                        // text
                        .transformers(
                                LoginBodyRedactingTransformer.NAME,
                                ForbiddenTextCheckingTransformer.NAME);

        wireMock.startRecording(recordSpec);
    }

    /**
     * Load recorded JSON mappings and create programmatic stubs for SSE responses. This avoids
     * WireMock's issues with SSE playback from JSON mappings.
     */
    private void loadAndCreateProgrammaticStubs() {
        for (Map.Entry<String, String> entry : targetUrlToMappingsDir.entrySet()) {
            String targetUrl = entry.getKey();
            String mappingsDir = entry.getValue();
            WireMockServer wireMock = proxyMap.get(targetUrl);

            try {
                Path mappingsDirPath = Paths.get(CASSETTES_ROOT, mappingsDir, "mappings");
                if (!Files.exists(mappingsDirPath)) {
                    continue;
                }

                Files.walk(mappingsDirPath)
                        .filter(path -> path.toString().endsWith(".json"))
                        .forEach(
                                path -> {
                                    try {
                                        createProgrammaticStubFromMapping(
                                                path, mappingsDir, wireMock);
                                    } catch (Exception e) {
                                        System.err.println(
                                                "Failed to load mapping "
                                                        + path
                                                        + ": "
                                                        + e.getMessage());
                                    }
                                });
            } catch (Exception e) {
                System.err.println(
                        "Failed to load programmatic stubs for "
                                + targetUrl
                                + ": "
                                + e.getMessage());
            }
        }

        // Add catch-all stub for OTLP trace exports.
        // OTLP requests contain timestamps and dynamic data that change between runs,
        // so we just return 200 OK without body matching. The actual span content
        // is validated via UnitTestSpanExporter, not VCR.
        WireMockServer braintrustWireMock = proxyMap.get("https://api.braintrust.dev");
        if (braintrustWireMock != null) {
            braintrustWireMock.stubFor(
                    post(urlEqualTo("/otel/v1/traces"))
                            .atPriority(Integer.MAX_VALUE) // lowest priority, fallback only
                            .willReturn(aResponse().withStatus(200)));
            log.info("Added catch-all stub for OTLP trace exports");
        }
    }

    private void createProgrammaticStubFromMapping(
            Path mappingPath, String mappingsDir, WireMockServer wireMock) throws Exception {
        String json = Files.readString(mappingPath);
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode mapping = mapper.readTree(json);

        // Check if this is an SSE response
        com.fasterxml.jackson.databind.JsonNode contentType =
                mapping.at("/response/headers/Content-Type");
        boolean isSse =
                contentType.isTextual() && contentType.asText().contains("text/event-stream");

        if (!isSse) {
            return; // Let WireMock handle non-SSE responses normally
        }

        log.info("Creating programmatic stub for SSE response: " + mappingPath.getFileName());

        // Extract request matching criteria
        String url = mapping.at("/request/url").asText();
        String method = mapping.at("/request/method").asText();

        // Extract request body pattern for matching
        com.fasterxml.jackson.databind.JsonNode bodyPatterns = mapping.at("/request/bodyPatterns");

        // Extract response body
        String body;
        if (mapping.at("/response/body").isTextual()) {
            body = mapping.at("/response/body").asText();
        } else if (mapping.at("/response/bodyFileName").isTextual()) {
            String bodyFileName = mapping.at("/response/bodyFileName").asText();
            Path bodyPath = Paths.get(CASSETTES_ROOT, mappingsDir, "__files", bodyFileName);
            body = Files.readString(bodyPath);
        } else {
            return;
        }

        // Create programmatic stub (like BraintrustOpenAITest does)
        com.github.tomakehurst.wiremock.client.MappingBuilder stub =
                com.github.tomakehurst.wiremock.client.WireMock.request(
                        method, com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo(url));

        // Add body pattern matching if present
        if (bodyPatterns.isArray() && !bodyPatterns.isEmpty()) {
            com.fasterxml.jackson.databind.JsonNode firstPattern = bodyPatterns.get(0);
            if (firstPattern.has("equalToJson")) {
                String expectedJson = firstPattern.get("equalToJson").asText();
                stub.withRequestBody(
                        com.github.tomakehurst.wiremock.client.WireMock.equalToJson(
                                expectedJson, true, true));
            }
        }

        com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response =
                com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(body);

        // Use instance method
        wireMock.stubFor(stub.willReturn(response));
    }

    private void stopRecording() {
        if (mode == VcrMode.RECORD && recordingStarted) {
            for (Map.Entry<String, WireMockServer> entry : proxyMap.entrySet()) {
                String targetUrl = entry.getKey();
                String mappingsDir = targetUrlToMappingsDir.get(targetUrl);
                WireMockServer wireMock = entry.getValue();
                wireMock.stopRecording();
                validateNoForbiddenText(mappingsDir);
                log.info("Recording saved for {}", targetUrl);
            }
            recordingStarted = false;
        }
        // Note: We don't stop the WireMock servers here because they're shared across tests.
        // The servers will be stopped when the JVM shuts down via the shutdown hook.
    }

    /**
     * Validate that no forbidden text (e.g., API keys) appears in recorded cassettes. Throws an
     * exception if any forbidden text is found, preventing accidental commit of secrets.
     */
    private void validateNoForbiddenText(String mappingsDir) {
        if (textToNeverRecord.isEmpty()) {
            return;
        }

        Path cassettesPath = Paths.get(CASSETTES_ROOT, mappingsDir);
        if (!Files.exists(cassettesPath)) {
            return;
        }

        try (Stream<Path> files = Files.walk(cassettesPath)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".json"))
                    .forEach(this::validateFileContainsNoForbiddenText);
        } catch (IOException e) {
            log.warn("Failed to validate cassettes for forbidden text: {}", e.getMessage());
        }
    }

    private void validateFileContainsNoForbiddenText(Path file) {
        try {
            String content = Files.readString(file);
            for (String forbidden : textToNeverRecord) {
                if (content.contains(forbidden)) {
                    throw new IllegalStateException(
                            "SECURITY: Cassette file contains forbidden text (likely an API key). "
                                    + "File: "
                                    + file
                                    + ". This cassette should not be committed. Please delete it"
                                    + " and ensure the LoginBodyRedactingTransformer is working.");
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read cassette file {}: {}", file, e.getMessage());
        }
    }

    private void assertStarted() {
        for (WireMockServer wireMock : proxyMap.values()) {
            if (!wireMock.isRunning()) {
                throw new IllegalStateException("VCR not started. See start() method");
            }
        }
    }
}
