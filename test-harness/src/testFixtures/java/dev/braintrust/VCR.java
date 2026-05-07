package dev.braintrust;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.recording.RecordSpecBuilder;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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

    private static final String DEFAULT_CASSETTES_ROOT =
            "test-harness/src/testFixtures/resources/cassettes/";

    /**
     * URL path prefixes that are excluded from cassette recording on the Braintrust target. In
     * RECORD mode these paths still proxy to the real backend but are not persisted as cassettes.
     * In REPLAY mode they receive catch-all stubs so the SDK's calls succeed without real backends.
     *
     * <p>Includes:
     *
     * <ul>
     *   <li>{@code /otel/} -- OTEL trace/log exports (binary protobuf with dynamic data)
     *   <li>{@code /attachment} -- S3 attachment signed-URL requests and status updates
     * </ul>
     */
    private static final Set<String> PASSTHROUGH_PATH_PREFIXES = Set.of("/otel/", "/attachment");

    private final String cassettesRoot;
    private final Map<String, WireMockServer> proxyMap;
    @Getter private final VcrMode mode;
    private final Map<String, String> targetUrlToMappingsDir;
    private final List<String> textToNeverRecord;
    private boolean recordingStarted = false;

    public VCR(Map<String, String> targetUrlToCassettesDir) {
        this(targetUrlToCassettesDir, List.of());
    }

    public VCR(Map<String, String> targetUrlToCassettesDir, List<String> textToNeverRecord) {
        this(DEFAULT_CASSETTES_ROOT, targetUrlToCassettesDir, textToNeverRecord);
    }

    public VCR(
            String cassettesRoot,
            Map<String, String> targetUrlToCassettesDir,
            List<String> textToNeverRecord) {
        this(
                cassettesRoot,
                VcrMode.valueOf(System.getenv().getOrDefault("VCR_MODE", "replay").toUpperCase()),
                targetUrlToCassettesDir,
                textToNeverRecord);
    }

    private VCR(
            String cassettesRoot,
            VcrMode mode,
            Map<String, String> targetUrlToCassettesDir,
            List<String> textToNeverRecord) {
        this.cassettesRoot = cassettesRoot;
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
            String cassettesDir = cassettesRoot + mappingsDir;

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

        // For the braintrust target, exclude passthrough paths from cassette recording.
        // These paths (OTEL exports, attachment uploads) contain dynamic data that doesn't
        // replay well. The recording proxy still forwards them to the real backend, but
        // stopRecording() won't persist them as cassette files.
        // In REPLAY mode, catch-all stubs handle these paths instead.
        if ("braintrust".equals(mappingsDir)) {
            String exclusionRegex = buildPassthroughExclusionRegex();
            recordSpec.onlyRequestsMatching(
                    RequestPatternBuilder.newRequestPattern(
                            RequestMethod.ANY, urlPathMatching(exclusionRegex)));
            log.info(
                    "Excluding passthrough paths from recording (filter regex: {})",
                    exclusionRegex);
        }

        wireMock.startRecording(recordSpec);
    }

    /**
     * Build a regex that matches any URL path that does NOT start with any of the passthrough path
     * prefixes. Uses negative lookahead, e.g.: {@code ^(?!/otel/|/attachment).*$}
     */
    private static String buildPassthroughExclusionRegex() {
        var alternatives =
                PASSTHROUGH_PATH_PREFIXES.stream()
                        .map(Pattern::quote)
                        .collect(Collectors.joining("|"));
        return "^(?!" + alternatives + ").*$";
    }

    /**
     * Register catch-all stubs on the Braintrust WireMock server for paths that are excluded from
     * cassette recording. These stubs allow the SDK's background calls (OTEL export, attachment
     * upload) to succeed in REPLAY mode without real backends.
     */
    private static void addBraintrustPassthroughStubs(WireMockServer braintrustWireMock) {
        // OTEL trace and log exports -- return 200 OK.
        // Actual span/log content is validated via UnitTestSpanExporter in-memory.
        braintrustWireMock.stubFor(
                post(urlEqualTo("/otel/v1/traces"))
                        .atPriority(Integer.MAX_VALUE)
                        .willReturn(aResponse().withStatus(200)));
        braintrustWireMock.stubFor(
                post(urlEqualTo("/otel/v1/logs"))
                        .atPriority(Integer.MAX_VALUE)
                        .willReturn(aResponse().withStatus(200)));

        // Attachment upload flow:
        //   1. POST /attachment  -> return a fake signed URL pointing back to this WireMock
        //   2. PUT  /s3-upload-stub -> accept the upload with 200 OK
        //   3. POST /attachment/status -> acknowledge with 200 OK
        // The fake signed URL routes the S3 PUT back through WireMock so it succeeds
        // without reaching a real object store.
        String fakeSignedUrl = braintrustWireMock.baseUrl() + "/s3-upload-stub";
        String attachmentResponse = "{\"signedUrl\": \"" + fakeSignedUrl + "\", \"headers\": {}}";
        braintrustWireMock.stubFor(
                post(urlEqualTo("/attachment"))
                        .atPriority(Integer.MAX_VALUE)
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(attachmentResponse)));
        braintrustWireMock.stubFor(
                put(urlEqualTo("/s3-upload-stub"))
                        .atPriority(Integer.MAX_VALUE)
                        .willReturn(aResponse().withStatus(200)));
        braintrustWireMock.stubFor(
                post(urlEqualTo("/attachment/status"))
                        .atPriority(Integer.MAX_VALUE)
                        .willReturn(aResponse().withStatus(200)));

        log.info(
                "Added passthrough stubs for OTEL endpoints and attachment upload flow"
                        + " (fake S3 URL: {})",
                fakeSignedUrl);
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
                Path mappingsDirPath = Paths.get(cassettesRoot, mappingsDir, "mappings");
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

        // Add catch-all stubs for passthrough paths on the Braintrust target.
        // These endpoints are not recorded as cassettes -- in REPLAY mode we stub them
        // so the SDK's background calls succeed without real backends.
        // Look up by mappings dir name ("braintrust") rather than target URL, since the
        // Braintrust API URL may vary (e.g., staging vs production).
        for (Map.Entry<String, String> btEntry : targetUrlToMappingsDir.entrySet()) {
            if ("braintrust".equals(btEntry.getValue())) {
                WireMockServer braintrustWireMock = proxyMap.get(btEntry.getKey());
                if (braintrustWireMock != null) {
                    addBraintrustPassthroughStubs(braintrustWireMock);
                }
                break;
            }
        }
    }

    private void createProgrammaticStubFromMapping(
            Path mappingPath, String mappingsDir, WireMockServer wireMock) throws Exception {
        String json = Files.readString(mappingPath);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode mapping = mapper.readTree(json);

        // Extract request matching criteria
        String url = mapping.at("/request/url").asText();
        String method = mapping.at("/request/method").asText();

        // Extract request body pattern for matching
        JsonNode bodyPatterns = mapping.at("/request/bodyPatterns");

        // Check if this is an SSE response or binary AWS event-stream response
        JsonNode contentType = mapping.at("/response/headers/Content-Type");
        boolean isSse =
                contentType.isTextual() && contentType.asText().contains("text/event-stream");
        boolean isEventStream =
                contentType.isTextual()
                        && contentType.asText().contains("application/vnd.amazon.eventstream");

        // Check if this is a function invoke request with dynamic OTEL parent info
        // Only handle invoke requests that have parent.row_ids (which contains dynamic trace IDs)
        boolean isFunctionInvokeWithParent = false;
        if (url.matches("/v1/function/.*/invoke")
                && bodyPatterns.isArray()
                && !bodyPatterns.isEmpty()) {
            JsonNode firstPattern = bodyPatterns.get(0);
            if (firstPattern.has("equalToJson")) {
                String bodyJson = firstPattern.get("equalToJson").asText();
                // Only create programmatic stub if body contains parent.row_ids
                isFunctionInvokeWithParent = bodyJson.contains("\"row_ids\"");
            }
        }

        // Check if this is a query-parameter request with a "version" param.
        // Version-specific fetches must take priority over general fetches for the same
        // endpoint, because general stubs (which don't constrain on "version") will also
        // match version-specific requests. Without explicit priority, WireMock may serve
        // a general (scenario-based) stub instead of the version-specific one.
        JsonNode queryParams = mapping.at("/request/queryParameters");
        boolean isVersionSpecificQuery = queryParams.isObject() && queryParams.has("version");

        if (!isSse && !isEventStream && !isFunctionInvokeWithParent && !isVersionSpecificQuery) {
            return; // Let WireMock handle other responses normally
        }

        if (isSse) {
            log.info("Creating programmatic stub for SSE response: " + mappingPath.getFileName());
        } else if (isEventStream) {
            log.info(
                    "Creating programmatic stub for binary event-stream response: "
                            + mappingPath.getFileName());
        } else if (isVersionSpecificQuery) {
            log.info(
                    "Creating high-priority programmatic stub for version-specific query: "
                            + mappingPath.getFileName());
        } else {
            log.info(
                    "Creating programmatic stub for function invoke with parent: "
                            + mappingPath.getFileName());
        }

        // Extract response
        int status = mapping.at("/response/status").asInt(200);
        String responseContentType =
                contentType.isTextual() ? contentType.asText() : "application/json";

        // Create programmatic stub.
        // Version-specific queries use urlPath + queryParameters instead of a full url.
        String urlPath = mapping.at("/request/urlPath").asText(null);
        com.github.tomakehurst.wiremock.client.MappingBuilder stub;
        if (isVersionSpecificQuery && urlPath != null) {
            stub =
                    com.github.tomakehurst.wiremock.client.WireMock.request(
                                    method,
                                    com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                                            urlPath))
                            // Higher priority (lower number) so this matches before general stubs
                            .atPriority(1);

            // Add all query parameter matchers from the cassette
            Iterator<Map.Entry<String, JsonNode>> qpFields = queryParams.fields();
            while (qpFields.hasNext()) {
                Map.Entry<String, JsonNode> qp = qpFields.next();
                // Extract the equalTo value from hasExactly[0].equalTo
                JsonNode hasExactly = qp.getValue().get("hasExactly");
                if (hasExactly != null && hasExactly.isArray() && !hasExactly.isEmpty()) {
                    String value = hasExactly.get(0).get("equalTo").asText();
                    stub.withQueryParam(
                            qp.getKey(),
                            com.github.tomakehurst.wiremock.client.WireMock.equalTo(value));
                }
            }
        } else {
            stub =
                    com.github.tomakehurst.wiremock.client.WireMock.request(
                            method,
                            com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo(url));
        }

        // Add body pattern matching if present
        if (bodyPatterns.isArray() && !bodyPatterns.isEmpty()) {
            JsonNode firstPattern = bodyPatterns.get(0);
            if (firstPattern.has("equalToJson")) {
                String expectedJson = firstPattern.get("equalToJson").asText();

                // For function invoke requests with parent, remove dynamic OTEL trace IDs
                if (isFunctionInvokeWithParent) {
                    expectedJson = removeDynamicFieldsFromJson(expectedJson, mapper);
                }

                stub.withRequestBody(
                        com.github.tomakehurst.wiremock.client.WireMock.equalToJson(
                                expectedJson, true, true));
            }
        }

        com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response =
                com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", responseContentType);

        // Binary event-stream bodies must be served as raw bytes to avoid UTF-8 corruption
        if (isEventStream) {
            if (mapping.at("/response/bodyFileName").isTextual()) {
                String bodyFileName = mapping.at("/response/bodyFileName").asText();
                Path bodyPath = Paths.get(cassettesRoot, mappingsDir, "__files", bodyFileName);
                response.withBody(Files.readAllBytes(bodyPath));
            } else {
                return;
            }
        } else {
            if (mapping.at("/response/body").isTextual()) {
                response.withBody(mapping.at("/response/body").asText());
            } else if (mapping.at("/response/bodyFileName").isTextual()) {
                String bodyFileName = mapping.at("/response/bodyFileName").asText();
                Path bodyPath = Paths.get(cassettesRoot, mappingsDir, "__files", bodyFileName);
                response.withBody(Files.readString(bodyPath));
            } else {
                return;
            }
        }

        wireMock.stubFor(stub.willReturn(response));
    }

    /**
     * Remove dynamic fields from JSON that change between test runs. Specifically removes
     * parent.row_ids.span_id and parent.row_ids.root_span_id which are generated by OTEL.
     */
    private String removeDynamicFieldsFromJson(String json, ObjectMapper mapper) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root.isObject()) {
                ObjectNode objNode = (ObjectNode) root;

                // Check if parent.row_ids exists and remove the dynamic fields
                JsonNode parent = objNode.get("parent");
                if (parent != null && parent.isObject()) {
                    ObjectNode parentObj = (ObjectNode) parent;
                    JsonNode rowIds = parentObj.get("row_ids");
                    if (rowIds != null && rowIds.isObject()) {
                        ObjectNode rowIdsObj = (ObjectNode) rowIds;
                        rowIdsObj.remove("span_id");
                        rowIdsObj.remove("root_span_id");
                        log.debug("Removed dynamic OTEL trace IDs from request body matching");
                    }
                }
                return mapper.writeValueAsString(objNode);
            }
        } catch (Exception e) {
            log.warn("Failed to remove dynamic fields from JSON: {}", e.getMessage());
        }
        return json;
    }

    private void stopRecording() {
        if (mode == VcrMode.RECORD && recordingStarted) {
            for (Map.Entry<String, WireMockServer> entry : proxyMap.entrySet()) {
                String targetUrl = entry.getKey();
                String mappingsDir = targetUrlToMappingsDir.get(targetUrl);
                WireMockServer wireMock = entry.getValue();
                var result = wireMock.stopRecording();
                renameWithDeterministicHashes(mappingsDir, result.getStubMappings());
                validateNoForbiddenText(mappingsDir);
                log.info("Recording saved for {}", targetUrl);
            }
            recordingStarted = false;
        }
        // Note: We don't stop the WireMock servers here because they're shared across tests.
        // The servers will be stopped when the JVM shuts down via the shutdown hook.
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Deterministic cassette file naming
    // ──────────────────────────────────────────────────────────────────────────

    /** ObjectMapper configured for canonical (sorted-key) JSON serialization. */
    private static final ObjectMapper CANONICAL_MAPPER =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** Number of hex characters to use from the SHA-256 hash (48 bits → ~280 trillion combos). */
    private static final int HASH_LENGTH = 12;

    /**
     * Rename cassette files from WireMock's random-UUID naming to deterministic content-based
     * hashes. Only processes files freshly recorded by WireMock in this session (identified via the
     * {@code StubMapping} list from {@code stopRecording()}), leaving any previously-renamed files
     * on disk untouched.
     *
     * <p>For each mapping file: computes a SHA-256 hash of the canonicalized {@code request} field,
     * then renames the mapping file and any associated body file in {@code __files/} to use that
     * hash instead of the random UUID. Also updates internal JSON references ({@code id}, {@code
     * bodyFileName}).
     */
    private void renameWithDeterministicHashes(
            String mappingsDir, List<StubMapping> freshMappings) {
        if (freshMappings.isEmpty()) {
            return;
        }

        Path mappingsDirPath = Paths.get(cassettesRoot, mappingsDir, "mappings");
        Path filesDirPath = Paths.get(cassettesRoot, mappingsDir, "__files");

        if (!Files.exists(mappingsDirPath)) {
            return;
        }

        // Build the list of freshly-written file paths from the StubMapping list.
        // WireMock writes filenames as: sanitise("{name}-{id}") + ".json"
        // where name is the sanitized URL path and id is the random UUID.
        List<Path> freshFiles =
                freshMappings.stream()
                        .map(
                                stub -> {
                                    String filename =
                                            sanitiseWireMockFilename(
                                                    stub.getName() + "-" + stub.getId() + ".json");
                                    return mappingsDirPath.resolve(filename);
                                })
                        .sorted() // deterministic processing order
                        .toList();

        // Track seen hashes to drop duplicates (same request → same hash → redundant cassette)
        Set<String> seenHashes = new HashSet<>();
        int renamed = 0;
        int dropped = 0;

        for (Path mappingFile : freshFiles) {
            if (!Files.exists(mappingFile)) {
                log.warn(
                        "Expected freshly-recorded cassette not found: {}",
                        mappingFile.getFileName());
                continue;
            }
            try {
                switch (renameCassetteFile(mappingFile, filesDirPath, seenHashes)) {
                    case RENAMED -> renamed++;
                    case DUPLICATE -> dropped++;
                    case SKIPPED -> {}
                }
            } catch (Exception e) {
                throw new RuntimeException(
                        "Failed to rename cassette " + mappingFile.getFileName(), e);
            }
        }

        if (renamed > 0 || dropped > 0) {
            log.info(
                    "Cassettes in {}: renamed {} with deterministic hashes, dropped {} duplicates"
                            + " (of {} freshly recorded)",
                    mappingsDir,
                    renamed,
                    dropped,
                    freshFiles.size());
        }
    }

    /**
     * Replicate WireMock's {@code FilenameMaker.sanitise()} logic: replace spaces with dashes,
     * strip characters that aren't word chars, dashes, or dots, then lowercase.
     */
    private static final Pattern WIREMOCK_NON_ALPHANUMERIC = Pattern.compile("[^\\w-.]");

    private static String sanitiseWireMockFilename(String s) {
        String decorated = String.join("-", s.split(" "));
        return WIREMOCK_NON_ALPHANUMERIC.matcher(decorated).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private enum RenameResult {
        RENAMED,
        DUPLICATE,
        SKIPPED
    }

    /**
     * Rename a single cassette mapping file (and its body file) to use a deterministic hash. If
     * another cassette with the same request hash was already processed, the duplicate is deleted
     * since WireMock matches on request content and would only ever use one of them.
     */
    private RenameResult renameCassetteFile(
            Path mappingFile, Path filesDirPath, Set<String> seenHashes) throws IOException {
        ObjectMapper mapper = CANONICAL_MAPPER;
        JsonNode root = mapper.readTree(Files.readString(mappingFile));

        if (!root.isObject() || !root.has("request")) {
            return RenameResult.SKIPPED;
        }

        // Extract the stub name (sanitized URL path, e.g. "chat_completions")
        String name = root.has("name") ? root.get("name").asText() : null;
        if (name == null || name.isEmpty()) {
            return RenameResult.SKIPPED;
        }

        // Canonicalize the request and compute hash.
        // Include scenario fields in the hash because WireMock scenarios use multiple stubs
        // with identical request patterns but different scenario states to return different
        // responses in sequence. Without these, scenario steps would hash identically and
        // the dedup logic would incorrectly drop them.
        JsonNode request = root.get("request");
        String canonicalRequest = canonicalizeJson(request, mapper);
        String scenarioKey = "";
        if (root.has("scenarioName")) {
            scenarioKey += "|scenario=" + root.get("scenarioName").asText();
        }
        if (root.has("requiredScenarioState")) {
            scenarioKey += "|state=" + root.get("requiredScenarioState").asText();
        }
        String hash = sha256Hex(canonicalRequest + scenarioKey, HASH_LENGTH);
        String hashKey = name + "-" + hash;

        // Drop duplicates: same request + same scenario state → truly redundant cassette.
        // But don't delete a file that already has the target name -- that's the canonical
        // copy we want to keep (e.g., from a previous recording session).
        String targetMappingFileName = hashKey + ".json";
        if (!seenHashes.add(hashKey)) {
            if (mappingFile.getFileName().toString().equals(targetMappingFileName)) {
                // This file already has the correct name -- keep it
                return RenameResult.SKIPPED;
            }
            log.debug(
                    "Dropping duplicate cassette: {} (hash: {})", mappingFile.getFileName(), hash);
            // Delete the mapping file and its body file
            String oldBodyFileName = root.at("/response/bodyFileName").asText(null);
            if (oldBodyFileName != null && !oldBodyFileName.isEmpty()) {
                Files.deleteIfExists(filesDirPath.resolve(oldBodyFileName));
            }
            Files.deleteIfExists(mappingFile);
            return RenameResult.DUPLICATE;
        }

        String newBaseName = hashKey;

        // Generate a deterministic UUID from the hash key
        UUID deterministicId = uuidV5(hashKey);

        // Update the mapping JSON (both "id" and "uuid" map to the same WireMock field;
        // update both so the file is internally consistent)
        ObjectNode rootObj = (ObjectNode) root;
        rootObj.put("id", deterministicId.toString());
        if (rootObj.has("uuid")) {
            rootObj.put("uuid", deterministicId.toString());
        }

        // Rename body file if present
        String oldBodyFileName = root.at("/response/bodyFileName").asText(null);
        if (oldBodyFileName != null && !oldBodyFileName.isEmpty()) {
            String bodyExtension = getExtension(oldBodyFileName);
            String newBodyFileName = newBaseName + bodyExtension;

            // Update the reference in the mapping JSON
            ((ObjectNode) root.get("response")).put("bodyFileName", newBodyFileName);

            // Rename the actual body file on disk (REPLACE_EXISTING handles re-records
            // where the deterministic name already exists from a previous recording session)
            Path oldBodyPath = filesDirPath.resolve(oldBodyFileName);
            if (Files.exists(oldBodyPath)) {
                Path newBodyPath = filesDirPath.resolve(newBodyFileName);
                Files.move(oldBodyPath, newBodyPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // Write the updated mapping JSON to the new filename
        String newMappingFileName = newBaseName + ".json";
        Path newMappingPath = mappingFile.getParent().resolve(newMappingFileName);
        String updatedJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootObj);
        Files.writeString(newMappingPath, updatedJson);

        // Delete the old file (if the name actually changed)
        if (!mappingFile.equals(newMappingPath)) {
            Files.deleteIfExists(mappingFile);
        }

        return RenameResult.RENAMED;
    }

    /**
     * Canonicalize a JSON node for hashing. Object keys are sorted recursively, and {@code
     * equalToJson} string values are parsed and re-serialized with sorted keys so that logically
     * identical JSON bodies produce the same canonical form regardless of original key ordering.
     */
    private static String canonicalizeJson(JsonNode node, ObjectMapper mapper) {
        try {
            Object canonical = toCanonicalObject(node, mapper);
            return mapper.writeValueAsString(canonical);
        } catch (Exception e) {
            // Fallback: use the raw toString if canonicalization fails
            return node.toString();
        }
    }

    /**
     * Convert a JsonNode tree into a canonical Java object tree where all objects use sorted-key
     * TreeMaps. Recursively parses {@code equalToJson} string values as JSON so their key ordering
     * is also normalized.
     */
    private static Object toCanonicalObject(JsonNode node, ObjectMapper mapper) {
        if (node.isObject()) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                sorted.put(field.getKey(), toCanonicalObject(field.getValue(), mapper));
            }
            return sorted;
        } else if (node.isArray()) {
            List<Object> list = new java.util.ArrayList<>();
            for (JsonNode element : node) {
                list.add(toCanonicalObject(element, mapper));
            }
            return list;
        } else if (node.isTextual()) {
            String text = node.asText();
            // Try to parse JSON strings (like equalToJson values) for canonical re-serialization
            if (text.startsWith("{") || text.startsWith("[")) {
                try {
                    JsonNode parsed = mapper.readTree(text);
                    return toCanonicalObject(parsed, mapper);
                } catch (Exception ignored) {
                    // Not valid JSON, return as plain string
                }
            }
            return text;
        } else if (node.isNumber()) {
            return node.numberValue();
        } else if (node.isBoolean()) {
            return node.booleanValue();
        } else if (node.isNull()) {
            return null;
        }
        return node.toString();
    }

    /** Compute the first {@code length} hex characters of the SHA-256 hash of a string. */
    private static String sha256Hex(String input, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
                if (hex.length() >= length) break;
            }
            return hex.substring(0, length);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Generate a deterministic UUID v5 (name-based, SHA-1) in the URL namespace. This produces the
     * same UUID for the same input string across JVM runs.
     */
    private static UUID uuidV5(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    /** Extract the file extension including the dot, e.g. ".json" from "foo-bar.json". */
    private static String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Cassette validation
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Validate that no forbidden text (e.g., API keys) appears in recorded cassettes. Throws an
     * exception if any forbidden text is found, preventing accidental commit of secrets.
     */
    private void validateNoForbiddenText(String mappingsDir) {
        if (textToNeverRecord.isEmpty()) {
            return;
        }

        Path cassettesPath = Paths.get(cassettesRoot, mappingsDir);
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
