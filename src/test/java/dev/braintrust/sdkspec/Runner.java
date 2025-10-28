package dev.braintrust.sdkspec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import dev.braintrust.Braintrust;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.instrumentation.openai.BraintrustOpenAI;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import lombok.Data;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Test runner for cross-language SDK spec tests.
 *
 * <p>This runner: 1. Loads YAML test specifications 2. Executes SDK calls against vendor
 * endpoints 3. Validates Braintrust API spans
 */
public class Runner {

    private static final String DEFAULT_SPEC_PATH =
            "/home/ark/braintrust/sdk/sdkspec/test/test-openai.yaml";
    private static final String PROJECT_NAME = "sdk-spec-test";
    private static final int INITIAL_WAIT_MS = 30_000; // 30 seconds initial wait
    private static final int BACKOFF_MS = 30_000; // 30 second backoff
    private static final int MAX_TOTAL_WAIT_MS = 150_000; // 150 seconds max wait

    private final ObjectMapper yamlMapper;
    private final ObjectMapper jsonMapper;
    private final HttpClient httpClient;
    private final String braintrustApiKey;
    private final String braintrustApiUrl;

    public Runner() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        // Configure to handle snake_case property names from YAML
        this.yamlMapper.setPropertyNamingStrategy(
                com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        this.jsonMapper = new ObjectMapper();
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        this.braintrustApiKey = System.getenv("BRAINTRUST_API_KEY");
        this.braintrustApiUrl =
                System.getenv().getOrDefault("BRAINTRUST_API_URL", "https://api.braintrust.dev");

        if (this.braintrustApiKey == null || this.braintrustApiKey.isEmpty()) {
            throw new IllegalStateException("BRAINTRUST_API_KEY environment variable not set");
        }
    }

    /**
     * Main entry point for running spec tests directly.
     *
     * @param args command line arguments (optional spec path)
     */
    public static void main(String[] args) throws Exception {
        String specPath = args.length > 0 ? args[0] : DEFAULT_SPEC_PATH;
        Runner runner = new Runner();
        TestSuite suite = runner.loadSpec(specPath);
        runner.runAllTests(suite);
    }

    /**
     * Load YAML test specification from file.
     *
     * @param specPath path to YAML spec file
     * @return parsed TestSuite
     */
    public TestSuite loadSpec(String specPath) throws IOException {
        File specFile = new File(specPath);
        return yamlMapper.readValue(specFile, TestSuite.class);
    }

    /**
     * Run all tests in a test suite.
     *
     * @param suite the test suite to run
     */
    public void runAllTests(TestSuite suite) throws Exception {
        String suiteName = suite.getName() != null ? suite.getName() : "Unknown";
        List<TestSpec> tests = suite.getTests() != null ? suite.getTests() : new ArrayList<>();

        System.out.println("Running test suite: " + suiteName);
        System.out.println("Found " + tests.size() + " test(s)");
        System.out.println("---- " + suiteName + " ----");

        for (TestSpec test : tests) {
            String testName = test.getName() != null ? test.getName() : "Unnamed test";
            System.out.println("  Running: " + testName);
            try {
                runTest(test, suiteName);
                System.out.println("    ✓ " + testName + " passed");
            } catch (AssertionError e) {
                System.err.println("    ✗ " + testName + " failed: " + e.getMessage());
                throw e;
            }
        }
    }

    /**
     * Run a single test from the specification.
     *
     * @param test the test specification
     * @param suiteName the name of the test suite
     */
    public void runTest(TestSpec test, String suiteName) throws Exception {
        String vendor = test.getVendor();
        String endpoint = test.getEndpoint();
        Map<String, Object> request = test.getRequest();

        // Initialize Braintrust and OpenTelemetry with the correct project
        BraintrustConfig config =
                BraintrustConfig.of("BRAINTRUST_DEFAULT_PROJECT_NAME", PROJECT_NAME);
        Braintrust braintrust = Braintrust.of(config);
        OpenTelemetry openTelemetry = braintrust.openTelemetryCreate();
        Tracer tracer = openTelemetry.getTracer("sdk-spec-test");

        // Execute the SDK call within a span
        String rootSpanId;
        Span rootSpan = tracer.spanBuilder(suiteName + "." + test.getName()).startSpan();
        try (Scope scope = rootSpan.makeCurrent()) {
            if ("OpenAI".equals(vendor) && "completions".equals(endpoint)) {
                executeOpenAICompletion(openTelemetry, request);
            } else {
                throw new UnsupportedOperationException(
                        "Vendor/endpoint not yet supported: " + vendor + "/" + endpoint);
            }
            rootSpanId = rootSpan.getSpanContext().getTraceId();
        } finally {
            rootSpan.end();
        }

        // Flush spans - wait for export to complete
        Thread.sleep(2000); // Give time for spans to be exported

        // Validate Braintrust spans if specified
        if (test.getBraintrustSpan() != null && rootSpanId != null) {
            String projectId = getProjectId(PROJECT_NAME);

            // Give the backend time to process
            System.out.println(
                    "    Waiting " + (INITIAL_WAIT_MS / 1000) + "s for backend processing...");
            Thread.sleep(INITIAL_WAIT_MS);

            JsonNode spanData = fetchBraintrustSpan(rootSpanId, projectId);
            System.out.println("    Got span: " + jsonMapper.writeValueAsString(spanData));

            validateBraintrustSpan(spanData, test.getBraintrustSpan());
        }
    }

    /**
     * Execute an OpenAI completion request.
     *
     * @param openTelemetry the OpenTelemetry instance
     * @param request the request parameters
     */
    private void executeOpenAICompletion(OpenTelemetry openTelemetry, Map<String, Object> request)
            throws Exception {
        OpenAIClient client =
                BraintrustOpenAI.wrapOpenAI(
                        openTelemetry,
                        OpenAIOkHttpClient.builder()
                                .apiKey(System.getenv("OPENAI_API_KEY"))
                                .build());

        // Build ChatCompletionCreateParams from the request map
        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder();

        // Set model
        if (request.containsKey("model")) {
            String modelStr = (String) request.get("model");
            // Map string to ChatModel enum
            ChatModel model =
                    switch (modelStr) {
                        case "gpt-4o-mini" -> ChatModel.GPT_4O_MINI;
                        case "gpt-4o" -> ChatModel.GPT_4O;
                        case "gpt-4" -> ChatModel.GPT_4;
                        case "gpt-3.5-turbo" -> ChatModel.GPT_3_5_TURBO;
                        default -> ChatModel.of(modelStr);
                    };
            paramsBuilder.model(model);
        }

        // Set messages
        if (request.containsKey("messages")) {
            @SuppressWarnings("unchecked")
            List<Map<String, String>> messages =
                    (List<Map<String, String>>) request.get("messages");
            for (Map<String, String> message : messages) {
                String role = message.get("role");
                String content = message.get("content");

                if ("system".equals(role)) {
                    paramsBuilder.addSystemMessage(content);
                } else if ("user".equals(role)) {
                    paramsBuilder.addUserMessage(content);
                }
            }
        }

        ChatCompletion completion = client.chat().completions().create(paramsBuilder.build());
        // Response is captured by Braintrust instrumentation
    }

    /**
     * Get project UUID from project name.
     *
     * @param projectName the project name
     * @return the project ID
     */
    private String getProjectId(String projectName) throws IOException, InterruptedException {
        String url = braintrustApiUrl + "/v1/project?project_name=" + projectName;

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + braintrustApiKey)
                        .header("Content-Type", "application/json")
                        .GET()
                        .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException(
                    "Failed to fetch project: HTTP "
                            + response.statusCode()
                            + " - "
                            + response.body());
        }

        JsonNode data = jsonMapper.readTree(response.body());
        JsonNode projects = data.get("objects");

        if (projects == null || !projects.isArray()) {
            throw new IOException("Invalid response from project API");
        }

        for (JsonNode project : projects) {
            if (projectName.equals(project.get("name").asText())) {
                return project.get("id").asText();
            }
        }

        throw new IOException("Project not found: " + projectName);
    }

    /**
     * Fetch span with exponential backoff retry logic.
     *
     * @param rootSpanId the root span ID
     * @param projectId the project ID
     * @return the span data
     */
    private JsonNode fetchBraintrustSpan(String rootSpanId, String projectId)
            throws IOException, InterruptedException {
        int backoffMs = BACKOFF_MS;
        int totalWaitMs = 0;
        IOException lastError = null;

        while (totalWaitMs < MAX_TOTAL_WAIT_MS) {
            try {
                return fetchBraintrustSpanImpl(rootSpanId, projectId);
            } catch (IOException e) {
                lastError = e;
                System.out.println(
                        "    Span not found yet, waiting "
                                + (backoffMs / 1000)
                                + "s before retry (total wait: "
                                + (totalWaitMs / 1000)
                                + "s)...");
                Thread.sleep(backoffMs);
                totalWaitMs += backoffMs;
            }
        }

        throw new IOException("Exceeded max wait time", lastError);
    }

    /**
     * Fetch span data from Braintrust API by root_span_id using BTQL.
     *
     * @param rootSpanId the root span ID
     * @param projectId the project ID
     * @return the child span (not the root span itself)
     */
    private JsonNode fetchBraintrustSpanImpl(String rootSpanId, String projectId)
            throws IOException, InterruptedException {
        String url = braintrustApiUrl + "/btql";

        String btqlQuery =
                String.format(
                        "select: *\n"
                                + "from: project_logs('%s')\n"
                                + "filter: root_span_id = '%s' and span_parents != null\n"
                                + "limit: 1000",
                        projectId, rootSpanId);

        Map<String, Object> btqlRequest = new HashMap<>();
        btqlRequest.put("query", btqlQuery);
        btqlRequest.put("use_columnstore", true);
        btqlRequest.put("use_brainstore", true);
        btqlRequest.put("brainstore_realtime", true);
        btqlRequest.put("api_version", 1);
        btqlRequest.put("fmt", "json");

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + braintrustApiKey)
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        jsonMapper.writeValueAsString(btqlRequest)))
                        .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException(
                    "Failed to fetch span: HTTP "
                            + response.statusCode()
                            + " - "
                            + response.body());
        }

        JsonNode data = jsonMapper.readTree(response.body());
        JsonNode childSpans = data.get("data");

        if (childSpans == null || !childSpans.isArray() || childSpans.size() == 0) {
            throw new IOException("No child spans found with root_span_id: " + rootSpanId);
        }

        if (childSpans.size() != 1) {
            throw new IOException("Expected exactly 1 child span, found " + childSpans.size());
        }

        return childSpans.get(0);
    }

    /**
     * Validate that Braintrust span data matches expected structure.
     *
     * @param spanData the actual span data
     * @param expected the expected structure
     */
    private void validateBraintrustSpan(JsonNode spanData, Map<String, Object> expected) {
        if (expected == null || expected.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            String key = entry.getKey();
            Object expectedVal = entry.getValue();

            if (!spanData.has(key)) {
                throw new AssertionError("Top-level key '" + key + "' not found in span data");
            }

            validateValue(spanData.get(key), expectedVal, key);
        }
    }

    /**
     * Recursively validate expected value matches actual.
     *
     * @param actual the actual value
     * @param expected the expected value
     * @param path the current path (for error messages)
     */
    private void validateValue(JsonNode actual, Object expected, String path) {
        if (expected instanceof Map) {
            // For maps, recursively validate each key
            if (!actual.isObject()) {
                throw new AssertionError(
                        "Path "
                                + path
                                + ": expected object, got "
                                + actual.getNodeType().name().toLowerCase());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> expectedMap = (Map<String, Object>) expected;
            for (Map.Entry<String, Object> entry : expectedMap.entrySet()) {
                String key = entry.getKey();
                if (!actual.has(key)) {
                    throw new AssertionError("Path " + path + "." + key + ": key not found");
                }
                validateValue(actual.get(key), entry.getValue(), path + "." + key);
            }
        } else if (expected instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> expectedList = (List<Object>) expected;

            if (actual.isArray()) {
                // Both are arrays - validate each element
                if (expectedList.size() != actual.size()) {
                    throw new AssertionError(
                            "Path "
                                    + path
                                    + ": list length mismatch, expected="
                                    + expectedList.size()
                                    + ", actual="
                                    + actual.size());
                }
                for (int i = 0; i < expectedList.size(); i++) {
                    validateValue(actual.get(i), expectedList.get(i), path + "[" + i + "]");
                }
            } else if (actual.isObject()) {
                // Expected is list of dicts (YAML format), actual is dict
                for (Object item : expectedList) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> itemMap = (Map<String, Object>) item;
                        for (Map.Entry<String, Object> entry : itemMap.entrySet()) {
                            String key = entry.getKey();
                            if (!actual.has(key)) {
                                throw new AssertionError(
                                        "Path " + path + "." + key + ": key not found");
                            }
                            validateValue(actual.get(key), entry.getValue(), path + "." + key);
                        }
                    }
                }
            } else {
                throw new AssertionError(
                        "Path "
                                + path
                                + ": expected list but actual is "
                                + actual.getNodeType().name().toLowerCase());
            }
        } else {
            // Scalar value - check equality or regex match
            if (expected instanceof String) {
                String expectedStr = (String) expected;
                if (expectedStr.startsWith("regex:")) {
                    // Regex pattern matching
                    String pattern = expectedStr.substring(6); // Remove "regex:" prefix
                    String actualStr = actual.isTextual() ? actual.asText() : actual.toString();
                    if (!Pattern.matches(pattern, actualStr)) {
                        throw new AssertionError(
                                "Path "
                                        + path
                                        + ": regex pattern '"
                                        + pattern
                                        + "' did not match actual='"
                                        + actualStr
                                        + "'");
                    }
                } else {
                    // Exact string match
                    if (!actual.isTextual() || !expectedStr.equals(actual.asText())) {
                        throw new AssertionError(
                                "Path "
                                        + path
                                        + ": expected='"
                                        + expectedStr
                                        + "', actual='"
                                        + actual
                                        + "'");
                    }
                }
            } else if (expected instanceof Number) {
                if (!actual.isNumber()) {
                    throw new AssertionError(
                            "Path " + path + ": expected number, got " + actual.getNodeType());
                }
                // Compare as doubles for flexibility
                double expectedNum = ((Number) expected).doubleValue();
                double actualNum = actual.asDouble();
                if (Math.abs(expectedNum - actualNum) > 0.0001) {
                    throw new AssertionError(
                            "Path "
                                    + path
                                    + ": expected="
                                    + expectedNum
                                    + ", actual="
                                    + actualNum);
                }
            } else if (expected instanceof Boolean) {
                if (!actual.isBoolean()) {
                    throw new AssertionError(
                            "Path " + path + ": expected boolean, got " + actual.getNodeType());
                }
                if (!expected.equals(actual.asBoolean())) {
                    throw new AssertionError(
                            "Path " + path + ": expected=" + expected + ", actual=" + actual);
                }
            } else {
                throw new AssertionError(
                        "Path " + path + ": unsupported expected type " + expected.getClass());
            }
        }
    }

    /** Test suite structure matching YAML format. */
    @Data
    public static class TestSuite {
        private String name;
        private List<TestSpec> tests;
    }

    /** Individual test specification. */
    @Data
    public static class TestSpec {
        private String name;
        private String vendor;
        private String endpoint;
        private Map<String, Object> request;
        private Map<String, Object> braintrustSpan;
    }
}
