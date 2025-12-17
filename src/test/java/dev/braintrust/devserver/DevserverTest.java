package dev.braintrust.devserver;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.eval.Scorer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class DevserverTest {
    @Test
    void testHealthCheck() throws Exception {
        RemoteEval<String, String> testEval =
                RemoteEval.<String, String>builder()
                        .name("test-eval")
                        .projectName("test-project")
                        .config(BraintrustConfig.of("BRAINTRUST_API_KEY", "bogus"))
                        .taskFunction(input -> input.toUpperCase())
                        .scorer(
                                Scorer.of(
                                        "length",
                                        (expected, result) -> (double) result.length() / 10.0))
                        .build();

        Devserver server =
                Devserver.builder().registerEval(testEval).host("localhost").port(18300).build();

        // Start server in background thread
        Thread serverThread =
                new Thread(
                        () -> {
                            try {
                                server.start();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        });
        serverThread.start();

        // Give server time to start
        Thread.sleep(1000);

        try {
            // Test health check endpoint
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:18300/"))
                            .GET()
                            .build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals("Hello, world!", response.body());
            assertEquals("text/plain", response.headers().firstValue("Content-Type").orElse(""));
        } finally {
            server.stop();
            serverThread.interrupt();
        }
    }
}
