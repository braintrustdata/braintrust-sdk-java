package dev.braintrust.devserver;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.TestUtils;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.eval.Scorer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CorsTest {
    private static Devserver server;
    private static Thread serverThread;
    private static final int TEST_PORT = TestUtils.getRandomOpenPort();

    @BeforeAll
    static void setup() throws Exception {
        RemoteEval<String, String> testEval =
                RemoteEval.<String, String>builder()
                        .name("test-eval")
                        .projectName("test-project")
                        .config(BraintrustConfig.of("BRAINTRUST_API_KEY", "bogus"))
                        .taskFunction(String::toUpperCase)
                        .scorer(
                                Scorer.of(
                                        "length",
                                        (expected, result) -> (double) result.length() / 10.0))
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
                                throw new RuntimeException(e);
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
    void testCorsPreflightRequest() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + TEST_PORT + "/"))
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .header("Origin", "https://www.braintrust.dev")
                        .header("Access-Control-Request-Method", "POST")
                        .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(204, response.statusCode());
        assertEquals(
                "https://www.braintrust.dev",
                response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertEquals(
                "true",
                response.headers()
                        .firstValue("Access-Control-Allow-Credentials")
                        .orElse("")
                        .toLowerCase());
        assertTrue(
                response.headers()
                        .firstValue("Access-Control-Allow-Methods")
                        .orElse("")
                        .toLowerCase()
                        .contains("post"));
        assertTrue(
                response.headers()
                        .firstValue("Access-Control-Allow-Headers")
                        .orElse("")
                        .toLowerCase()
                        .contains("x-bt-auth-token"));
    }

    @Test
    void testCorsActualRequest() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + TEST_PORT + "/"))
                        .GET()
                        .header("Origin", "https://www.braintrust.dev")
                        .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("Hello, world!", response.body());
        assertEquals(
                "https://www.braintrust.dev",
                response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertEquals(
                "true",
                response.headers()
                        .firstValue("Access-Control-Allow-Credentials")
                        .orElse("")
                        .toLowerCase());
    }

    @Test
    void testCorsPreviewDomain() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + TEST_PORT + "/"))
                        .GET()
                        .header("Origin", "https://pr-123.preview.braintrust.dev")
                        .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(
                "https://pr-123.preview.braintrust.dev",
                response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
    }

    @Test
    void testCorsUnauthorizedOrigin() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + TEST_PORT + "/"))
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .header("Origin", "https://evil.com")
                        .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(403, response.statusCode());
    }

    @Test
    void testPrivateNetworkAccess() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + TEST_PORT + "/"))
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .header("Origin", "https://www.braintrust.dev")
                        .header("Access-Control-Request-Private-Network", "true")
                        .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(204, response.statusCode());
        assertEquals(
                "true",
                response.headers()
                        .firstValue("Access-Control-Allow-Private-Network")
                        .orElse("")
                        .toLowerCase());
    }
}
