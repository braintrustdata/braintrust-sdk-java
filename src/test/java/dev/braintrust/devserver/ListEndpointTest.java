package dev.braintrust.devserver;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.eval.Scorer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ListEndpointTest {
    private static Devserver server;
    private static Thread serverThread;
    private static final int TEST_PORT = 18302;
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void setup() throws Exception {
        // Create a test eval with parameters
        RemoteEval<String, String> testEval =
                RemoteEval.<String, String>builder()
                        .name("test-classifier")
                        .projectName("test-project")
                        .config(BraintrustConfig.of("BRAINTRUST_API_KEY", "bogus"))
                        .taskFunction(input -> input.toUpperCase())
                        .scorer(Scorer.of("accuracy", result -> 0.95))
                        .scorer(
                                Scorer.of(
                                        "length",
                                        (expected, result) -> (double) result.length() / 10.0))
                        .parameter(
                                "model",
                                RemoteEval.Parameter.dataParameter(
                                        "The model to use",
                                        Map.of(
                                                "type",
                                                "string",
                                                "enum",
                                                new String[] {"gpt-4", "gpt-3.5"}),
                                        "gpt-4"))
                        .parameter(
                                "temperature",
                                RemoteEval.Parameter.dataParameter(
                                        "Temperature for sampling",
                                        Map.of("type", "number", "minimum", 0.0, "maximum", 2.0),
                                        0.7))
                        .build();

        server =
                Devserver.builder()
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
    void testListEndpoint() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + TEST_PORT + "/list"))
                        .GET()
                        .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(
                "application/json", response.headers().firstValue("Content-Type").orElse(null));

        // Parse and validate JSON response
        JsonNode root = mapper.readTree(response.body());

        // Should have one evaluator
        assertTrue(root.has("test-classifier"));

        JsonNode eval = root.get("test-classifier");

        // Check parameters
        assertTrue(eval.has("parameters"));
        JsonNode parameters = eval.get("parameters");

        assertTrue(parameters.has("model"));
        JsonNode modelParam = parameters.get("model");
        assertEquals("data", modelParam.get("type").asText());
        assertEquals("The model to use", modelParam.get("description").asText());
        assertEquals("gpt-4", modelParam.get("default").asText());
        assertTrue(modelParam.has("schema"));

        assertTrue(parameters.has("temperature"));
        JsonNode tempParam = parameters.get("temperature");
        assertEquals("data", tempParam.get("type").asText());
        assertEquals(0.7, tempParam.get("default").asDouble());

        // Check scores
        assertTrue(eval.has("scores"));
        JsonNode scores = eval.get("scores");
        assertEquals(2, scores.size());

        assertEquals("accuracy", scores.get(0).get("name").asText());
        assertEquals("length", scores.get(1).get("name").asText());
    }

    @Test
    void testListEndpointMethodNotAllowed() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + TEST_PORT + "/list"))
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
                        .uri(URI.create("http://localhost:" + TEST_PORT + "/list"))
                        .GET()
                        .header("Origin", "https://www.braintrust.dev")
                        .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(
                "https://www.braintrust.dev",
                response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
    }
}
