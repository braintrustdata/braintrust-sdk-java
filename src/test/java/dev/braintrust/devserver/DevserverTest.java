package dev.braintrust.devserver;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.BraintrustUtils;
import dev.braintrust.TestHarness;
import dev.braintrust.eval.Scorer;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;

@Slf4j
class DevserverTest {
    private static Devserver server;
    private static Thread serverThread;
    private static TestHarness testHarness;
    // private static final int TEST_PORT = TestUtils.getRandomOpenPort();
    private static final int TEST_PORT = 8301;
    private static final String TEST_URL = "http://localhost:" + TEST_PORT;
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static final String REMOTE_EVAL_NAME = "food-type-classifier";
    private static final BraintrustUtils.Parent PLAYGROUND_PARENT =
            new BraintrustUtils.Parent("playground_id", "ceea7422-3507-4d1c-a5f7-7acf41d9fac2");

    // Remote scorer from java-unit-test project (returns 1.0 for exact match, 0.0 otherwise)
    private static final String REMOTE_SCORER_FUNCTION_ID = "efa5f9c3-6ece-4726-a9d6-4ba792980b3f";
    private static final String REMOTE_SCORER_NAME = "typescript_exact_match";

    @BeforeAll
    static void setUp() throws Exception {
        // Set up test harness with VCR (records/replays HTTP interactions)
        testHarness = TestHarness.setup();

        // Create a shared eval for all tests
        RemoteEval<String, String> testEval =
                RemoteEval.<String, String>builder()
                        .name(REMOTE_EVAL_NAME)
                        .taskFunction(
                                input -> {
                                    // Create a span inside the task to test baggage propagation
                                    var tracer = dev.braintrust.trace.BraintrustTracing.getTracer();
                                    var span = tracer.spanBuilder("custom-task-span").startSpan();
                                    try (var scope =
                                            io.opentelemetry.context.Context.current()
                                                    .with(span)
                                                    .makeCurrent()) {
                                        // Do some work
                                        return "java-fruit";
                                    } finally {
                                        span.end();
                                    }
                                })
                        .scorer(Scorer.of("simple_scorer", (expected, result) -> 0.7))
                        .build();

        server =
                Devserver.builder()
                        .config(testHarness.braintrust().config())
                        .registerEval(testEval)
                        .host("localhost")
                        .port(TEST_PORT)
                        .build();

        // Start server in background thread
        serverThread =
                new Thread(
                        () -> {
                            try {
                                server.start();
                            } catch (Exception e) {
                                log.error("unable to start dev server", e);
                            }
                        });
        serverThread.start();

        // Give server time to start
        Thread.sleep(1000);
    }

    @AfterAll
    @SneakyThrows
    static void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (serverThread != null) {
            serverThread.join(30_000);
            if (serverThread.isAlive()) {
                serverThread.interrupt();
            }
        }
    }

    @Test
    void testHealthCheck() throws Exception {
        // Test health check endpoint using the shared devserver
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request =
                HttpRequest.newBuilder().uri(URI.create(TEST_URL + "/")).GET().build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("Hello, world!", response.body());
        assertEquals("text/plain", response.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void testStreamingEval() throws Exception {
        // Create eval request with inline data using EvalRequest types
        EvalRequest evalRequest = new EvalRequest();
        evalRequest.setName(REMOTE_EVAL_NAME);
        evalRequest.setStream(true);

        // Create inline data
        EvalRequest.DataSpec dataSpec = new EvalRequest.DataSpec();

        EvalRequest.EvalCaseData case1 = new EvalRequest.EvalCaseData();
        case1.setInput("apple");
        case1.setExpected("fruit");

        EvalRequest.EvalCaseData case2 = new EvalRequest.EvalCaseData();
        case2.setInput("carrot");
        case2.setExpected("vegetable");

        dataSpec.setData(List.of(case1, case2));
        evalRequest.setData(dataSpec);

        Map<String, Object> parentSpec =
                Map.of(
                        "object_type", PLAYGROUND_PARENT.type(),
                        "object_id", PLAYGROUND_PARENT.id(),
                        "propagated_event",
                                Map.of("span_attributes", Map.of("generation", "test-gen-1")));
        evalRequest.setParent(parentSpec);

        // Add remote scorer from Braintrust
        EvalRequest.RemoteScorer remoteScorer = new EvalRequest.RemoteScorer();
        remoteScorer.setName(REMOTE_SCORER_NAME);
        EvalRequest.FunctionId functionId = new EvalRequest.FunctionId();
        functionId.setFunctionId(REMOTE_SCORER_FUNCTION_ID);
        remoteScorer.setFunctionId(functionId);
        evalRequest.setScores(List.of(remoteScorer));

        String requestBody = JSON_MAPPER.writeValueAsString(evalRequest);

        // Make POST request to /eval with auth headers
        HttpURLConnection conn =
                (HttpURLConnection) new URI(TEST_URL + "/eval").toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-bt-auth-token", testHarness.braintrustApiKey());
        conn.setRequestProperty("x-bt-project-id", TestHarness.defaultProjectId());
        conn.setRequestProperty("x-bt-org-name", TestHarness.defaultOrgName());
        conn.setDoOutput(true);

        // Write request body
        conn.getOutputStream().write(requestBody.getBytes(StandardCharsets.UTF_8));
        conn.getOutputStream().flush();

        // Read SSE response
        assertEquals(200, conn.getResponseCode());
        assertEquals("text/event-stream", conn.getHeaderField("Content-Type"));

        BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));

        List<Map<String, String>> events = new ArrayList<>();
        String line;
        String currentEvent = null;
        StringBuilder currentData = new StringBuilder();

        while ((line = reader.readLine()) != null) {
            if (line.startsWith("event: ")) {
                currentEvent = line.substring(7);
            } else if (line.startsWith("data: ")) {
                currentData.append(line.substring(6));
            } else if (line.isEmpty() && currentEvent != null) {
                // End of event
                events.add(Map.of("event", currentEvent, "data", currentData.toString()));
                currentEvent = null;
                currentData = new StringBuilder();
            }
        }
        reader.close();

        // Assert event structure
        assertFalse(events.isEmpty(), "Should have received events");

        // Count events by type
        List<Map<String, String>> progressEvents =
                events.stream().filter(e -> "progress".equals(e.get("event"))).toList();
        List<Map<String, String>> summaryEvents =
                events.stream().filter(e -> "summary".equals(e.get("event"))).toList();
        List<Map<String, String>> doneEvents =
                events.stream().filter(e -> "done".equals(e.get("event"))).toList();

        // Should have 1 start event, 2 progress events (one per dataset case), 1 summary, 1 done
        assertEquals(2, progressEvents.size(), "Should have 2 progress events");
        assertEquals(1, summaryEvents.size(), "Should have 1 summary event");
        assertEquals(1, doneEvents.size(), "Should have 1 done event");

        // Verify progress events match expected structure
        for (Map<String, String> progressEvent : progressEvents) {
            String dataJson = progressEvent.get("data");
            JsonNode progressData = JSON_MAPPER.readTree(dataJson);

            // Assert expected fields in progress event
            assertTrue(progressData.has("id"), "Progress event should have id");
            assertEquals("task", progressData.get("object_type").asText());
            assertEquals(REMOTE_EVAL_NAME, progressData.get("name").asText());
            assertEquals("code", progressData.get("format").asText());
            assertEquals("completion", progressData.get("output_type").asText());
            assertEquals("json_delta", progressData.get("event").asText());

            // Assert data field contains the task result
            String taskResultJson = progressData.get("data").asText();
            assertEquals("\"java-fruit\"", taskResultJson);
        }

        { // Verify summary event
            Map<String, String> summaryEvent = summaryEvents.get(0);
            JsonNode summaryData = JSON_MAPPER.readTree(summaryEvent.get("data"));

            assertEquals(TestHarness.defaultProjectName(), summaryData.get("projectName").asText());
            assertTrue(summaryData.has("projectId"));
            assertEquals(REMOTE_EVAL_NAME, summaryData.get("experimentName").asText());

            // Verify scores in summary
            assertTrue(summaryData.has("scores"));
            JsonNode scores = summaryData.get("scores");

            // Verify local scorer
            assertTrue(scores.has("simple_scorer"), "Summary should have simple_scorer");
            JsonNode simpleScorer = scores.get("simple_scorer");
            assertEquals("simple_scorer", simpleScorer.get("name").asText());
            assertEquals(0.7, simpleScorer.get("score").asDouble(), 0.001);

            // Verify remote scorer (returns 0.0 because output "java-fruit" != expected
            // "fruit"/"vegetable")
            assertTrue(scores.has(REMOTE_SCORER_NAME), "Summary should have remote scorer");
            JsonNode remoteScorerResult = scores.get(REMOTE_SCORER_NAME);
            assertEquals(REMOTE_SCORER_NAME, remoteScorerResult.get("name").asText());
            assertEquals(0.0, remoteScorerResult.get("score").asDouble(), 0.001);
        }

        // Get exported spans from test harness (since devserver uses global tracer)
        List<SpanData> exportedSpans = testHarness.awaitExportedSpans();
        assertFalse(exportedSpans.isEmpty(), "Should have exported spans");

        // We should have 2 eval traces (one per dataset case), each with task, scores, and custom
        // spans
        // Each trace has: 1 eval span, 1 task span, 2 score spans (local + remote), 1
        // custom-task-span = 5 spans
        // per case
        // Total: 2 cases * 5 spans = 10 spans
        assertEquals(10, exportedSpans.size(), "Should have 10 spans (5 per dataset case)");

        // Verify span types
        var evalSpans = exportedSpans.stream().filter(s -> s.getName().equals("eval")).toList();
        var taskSpans = exportedSpans.stream().filter(s -> s.getName().equals("task")).toList();
        var scoreSpans = exportedSpans.stream().filter(s -> s.getName().equals("score")).toList();
        var customSpans =
                exportedSpans.stream().filter(s -> s.getName().equals("custom-task-span")).toList();

        assertEquals(2, evalSpans.size(), "Should have 2 eval spans");
        assertEquals(2, taskSpans.size(), "Should have 2 task spans");
        assertEquals(4, scoreSpans.size(), "Should have 4 score spans (2 scorers x 2 cases)");
        assertEquals(2, customSpans.size(), "Should have 2 custom-task-span spans");

        // Verify eval spans have all required attributes
        for (SpanData evalSpan : evalSpans) {
            // Verify braintrust.parent
            String parent =
                    evalSpan.getAttributes()
                            .get(
                                    io.opentelemetry.api.common.AttributeKey.stringKey(
                                            "braintrust.parent"));
            assertEquals(
                    PLAYGROUND_PARENT.toParentValue(),
                    parent,
                    "Eval span should have parent attribute");

            String spanAttrsJson =
                    evalSpan.getAttributes()
                            .get(
                                    io.opentelemetry.api.common.AttributeKey.stringKey(
                                            "braintrust.span_attributes"));
            assertNotNull(spanAttrsJson, "Eval span should have span_attributes");
            JsonNode spanAttrs = JSON_MAPPER.readTree(spanAttrsJson);
            assertEquals("eval", spanAttrs.get("type").asText());
            assertEquals("eval", spanAttrs.get("name").asText());
            assertEquals("test-gen-1", spanAttrs.get("generation").asText());

            String inputJson =
                    evalSpan.getAttributes()
                            .get(
                                    io.opentelemetry.api.common.AttributeKey.stringKey(
                                            "braintrust.input_json"));
            assertNotNull(inputJson, "Eval span should have input_json");

            String expectedJson =
                    evalSpan.getAttributes()
                            .get(
                                    io.opentelemetry.api.common.AttributeKey.stringKey(
                                            "braintrust.expected_json"));
            assertNotNull(expectedJson, "Eval span should have expected_json");

            String outputJson =
                    evalSpan.getAttributes()
                            .get(
                                    io.opentelemetry.api.common.AttributeKey.stringKey(
                                            "braintrust.output_json"));
            assertNotNull(outputJson, "Eval span should have output_json");
            JsonNode output = JSON_MAPPER.readTree(outputJson);
            assertEquals("java-fruit", output.get("output").asText());
        }

        for (SpanData taskSpan : taskSpans) {
            // Verify braintrust.parent
            String parent =
                    taskSpan.getAttributes()
                            .get(
                                    io.opentelemetry.api.common.AttributeKey.stringKey(
                                            "braintrust.parent"));
            assertEquals(PLAYGROUND_PARENT.toParentValue(), parent);

            String spanAttrsJson =
                    taskSpan.getAttributes()
                            .get(
                                    io.opentelemetry.api.common.AttributeKey.stringKey(
                                            "braintrust.span_attributes"));
            assertNotNull(spanAttrsJson, "Task span should have span_attributes");
            JsonNode spanAttrs = JSON_MAPPER.readTree(spanAttrsJson);
            assertEquals("task", spanAttrs.get("type").asText());
            assertEquals("task", spanAttrs.get("name").asText());
            assertEquals("test-gen-1", spanAttrs.get("generation").asText());

            String inputJson =
                    taskSpan.getAttributes()
                            .get(
                                    io.opentelemetry.api.common.AttributeKey.stringKey(
                                            "braintrust.input_json"));
            assertNotNull(inputJson, "Task span should have input_json");

            String outputJson =
                    taskSpan.getAttributes()
                            .get(
                                    io.opentelemetry.api.common.AttributeKey.stringKey(
                                            "braintrust.output_json"));
            assertNotNull(outputJson, "Task span should have output_json");
            JsonNode output = JSON_MAPPER.readTree(outputJson);
            assertEquals("java-fruit", output.get("output").asText());
        }

        for (SpanData scoreSpan : scoreSpans) {
            // Verify braintrust.parent
            String parent =
                    scoreSpan
                            .getAttributes()
                            .get(
                                    io.opentelemetry.api.common.AttributeKey.stringKey(
                                            "braintrust.parent"));
            assertEquals(PLAYGROUND_PARENT.toParentValue(), parent);

            // Verify braintrust.span_attributes
            String spanAttrsJson =
                    scoreSpan
                            .getAttributes()
                            .get(
                                    io.opentelemetry.api.common.AttributeKey.stringKey(
                                            "braintrust.span_attributes"));
            assertNotNull(spanAttrsJson, "Score span should have span_attributes");
            JsonNode spanAttrs = JSON_MAPPER.readTree(spanAttrsJson);
            assertEquals("score", spanAttrs.get("type").asText());
            assertEquals("test-gen-1", spanAttrs.get("generation").asText());

            // Scorer name should be either simple_scorer or the remote scorer
            String scorerName = spanAttrs.get("name").asText();
            assertTrue(
                    scorerName.contains("simple_scorer")
                            || scorerName.contains(REMOTE_SCORER_NAME.replaceAll("_", "")),
                    "Score span name should be simple_scorer or %s -- got: %s"
                            .formatted(REMOTE_EVAL_NAME, scorerName));

            // Verify braintrust.output_json contains scores
            String outputJson =
                    scoreSpan
                            .getAttributes()
                            .get(
                                    io.opentelemetry.api.common.AttributeKey.stringKey(
                                            "braintrust.output_json"));
            assertNotNull(outputJson, "Score span should have output_json");
            JsonNode output = JSON_MAPPER.readTree(outputJson);

            if (scorerName.equals("simple_scorer")) {
                assertTrue(
                        output.has("simple_scorer"), "Output should contain simple_scorer results");
                assertEquals(0.7, output.get("simple_scorer").asDouble(), 0.001);
            } else {
                assertTrue(
                        output.has(REMOTE_SCORER_NAME),
                        "Output should contain remote scorer results");
                assertEquals(0.0, output.get(REMOTE_SCORER_NAME).asDouble(), 0.001);
            }
        }

        for (SpanData customSpan : customSpans) {
            // Verify it has a parent span (is not a root span)
            assertTrue(
                    customSpan.getParentSpanContext().isValid(),
                    "Custom span should have a valid parent span context");
            assertNotEquals(
                    io.opentelemetry.api.trace.SpanId.getInvalid(),
                    customSpan.getParentSpanId(),
                    "Custom span should not be a root span (should have parent span ID)");

            // Verify it has braintrust.parent attribute from baggage propagation
            String parent =
                    customSpan
                            .getAttributes()
                            .get(
                                    io.opentelemetry.api.common.AttributeKey.stringKey(
                                            "braintrust.parent"));
            assertEquals(PLAYGROUND_PARENT.toParentValue(), parent);
        }
    }

    @Test
    void testEvaluatorNotFound() throws Exception {
        EvalRequest request = new EvalRequest();
        request.setName("non-existent-eval");

        EvalRequest.DataSpec dataSpec = new EvalRequest.DataSpec();
        EvalRequest.EvalCaseData case1 = new EvalRequest.EvalCaseData();
        case1.setInput("test");
        dataSpec.setData(List.of(case1));
        request.setData(dataSpec);

        String requestJson = JSON_MAPPER.writeValueAsString(request);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest httpRequest =
                HttpRequest.newBuilder()
                        .uri(URI.create(TEST_URL + "/eval"))
                        .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                        .header("Content-Type", "application/json")
                        .header("x-bt-auth-token", testHarness.braintrustApiKey())
                        .header("x-bt-project-id", TestHarness.defaultProjectId())
                        .header("x-bt-org-name", TestHarness.defaultOrgName())
                        .build();

        HttpResponse<String> response =
                client.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("Evaluator not found"));
    }

    @Test
    void testEvalMethodNotAllowed() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request =
                HttpRequest.newBuilder().uri(URI.create(TEST_URL + "/eval")).GET().build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
    }

    @Test
    void testListEndpoint() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(TEST_URL + "/list"))
                        .GET()
                        .header("x-bt-auth-token", testHarness.braintrustApiKey())
                        .header("x-bt-project-id", TestHarness.defaultProjectId())
                        .header("x-bt-org-name", TestHarness.defaultOrgName())
                        .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(
                "application/json", response.headers().firstValue("Content-Type").orElse(null));

        // Parse and validate JSON response
        JsonNode root = JSON_MAPPER.readTree(response.body());

        // Should have one evaluator
        assertTrue(root.has(REMOTE_EVAL_NAME));

        JsonNode eval = root.get(REMOTE_EVAL_NAME);

        // Check scores
        assertTrue(eval.has("scores"));
        JsonNode scores = eval.get("scores");
        assertEquals(1, scores.size());

        assertEquals("simple_scorer", scores.get(0).get("name").asText());
    }

    @Test
    void testListMethodNotAllowed() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(TEST_URL + "/list"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
    }

    @Test
    void testListEndpointWithCors() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(TEST_URL + "/list"))
                        .GET()
                        .header("Origin", "https://www.braintrust.dev")
                        .header("x-bt-auth-token", testHarness.braintrustApiKey())
                        .header("x-bt-project-id", TestHarness.defaultProjectId())
                        .header("x-bt-org-name", TestHarness.defaultOrgName())
                        .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(
                "https://www.braintrust.dev",
                response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
    }
}
