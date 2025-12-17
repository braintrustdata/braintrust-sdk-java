package dev.braintrust.devserver;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.TestUtils;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.eval.Scorer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class EvalEndpointTest {
    private static Devserver server;
    private static Thread serverThread;
    private static final int TEST_PORT = TestUtils.getRandomOpenPort();
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void setup() throws Exception {
        // Create a test eval
        RemoteEval<String, String> testEval =
                RemoteEval.<String, String>builder()
                        .name("uppercase-eval")
                        .taskFunction(String::toUpperCase)
                        .scorer(
                                Scorer.of(
                                        "length",
                                        (expected, result) ->
                                                Math.min((double) result.length() / 10.0, 1.0)))
                        .scorer(
                                Scorer.of(
                                        "has_hello",
                                        (expected, result) -> result.contains("HELLO") ? 1.0 : 0.0))
                        .build();

        server =
                Devserver.builder()
                        .config(BraintrustConfig.of("BRAINTRUST_API_KEY", "bogus"))
                        .registerEval(testEval)
                        .host("localhost")
                        .port(TEST_PORT)
                        .build();

        serverThread =
                new Thread(
                        () -> {
                            try {
                                server.start();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        });
        serverThread.start();
        Thread.sleep(1000); // Give server time to start
    }

    @AfterAll
    static void teardown() {
        server.stop();
        serverThread.interrupt();
    }

    @Test
    @Disabled
    void testEvalEndpointWithInlineData() throws Exception {
        // Build request
        EvalRequest request = new EvalRequest();
        request.setName("uppercase-eval");

        EvalRequest.DataSpec dataSpec = new EvalRequest.DataSpec();

        EvalRequest.EvalCaseData case1 = new EvalRequest.EvalCaseData();
        case1.setInput("hello");
        case1.setExpected("HELLO");

        EvalRequest.EvalCaseData case2 = new EvalRequest.EvalCaseData();
        case2.setInput("world");
        case2.setExpected("WORLD");

        dataSpec.setData(List.of(case1, case2));
        request.setData(dataSpec);

        String requestJson = mapper.writeValueAsString(request);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest httpRequest =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + TEST_PORT + "/eval"))
                        .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                        .header("Content-Type", "application/json")
                        .build();

        HttpResponse<String> response =
                client.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(
                "application/json", response.headers().firstValue("Content-Type").orElse(null));

        // Parse and validate JSON response
        JsonNode root = mapper.readTree(response.body());

        assertTrue(root.has("experimentName"));
        assertTrue(root.has("projectName"));
        assertTrue(root.has("projectId"));
        assertTrue(root.has("experimentId"));
        assertTrue(root.has("experimentUrl"));
        assertTrue(root.has("projectUrl"));
        assertTrue(root.has("scores"));

        JsonNode scores = root.get("scores");
        assertTrue(scores.has("length"));
        assertTrue(scores.has("has_hello"));

        // Check length scorer (average of 5/10 and 5/10 = 0.5)
        JsonNode lengthScore = scores.get("length");
        assertEquals("length", lengthScore.get("name").asText());
        assertEquals(0.5, lengthScore.get("score").asDouble(), 0.01);

        // Check has_hello scorer (1.0 for "hello", 0.0 for "world" = 0.5 average)
        JsonNode helloScore = scores.get("has_hello");
        assertEquals("has_hello", helloScore.get("name").asText());
        assertEquals(0.5, helloScore.get("score").asDouble(), 0.01);
    }

    @Test
    void testEvalEndpointEvaluatorNotFound() throws Exception {
        EvalRequest request = new EvalRequest();
        request.setName("non-existent-eval");

        EvalRequest.DataSpec dataSpec = new EvalRequest.DataSpec();
        EvalRequest.EvalCaseData case1 = new EvalRequest.EvalCaseData();
        case1.setInput("test");
        dataSpec.setData(List.of(case1));
        request.setData(dataSpec);

        String requestJson = mapper.writeValueAsString(request);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest httpRequest =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + TEST_PORT + "/eval"))
                        .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                        .header("Content-Type", "application/json")
                        .header("x-bt-auth-token", "test-token")
                        .header("x-bt-project-id", "test-project-id")
                        .header("x-bt-org-name", "test-org")
                        .build();

        HttpResponse<String> response =
                client.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("Evaluator not found"));
    }

    @Test
    void testEvalEndpointMethodNotAllowed() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + TEST_PORT + "/eval"))
                        .GET()
                        .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
    }
}
