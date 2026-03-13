package dev.braintrust.smoketest.springboot;

import dev.braintrust.InstrumentationReflection;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Smoke test that starts a Spring Boot app (embedded Tomcat) with the Braintrust
 * agent attached, hits an endpoint over HTTP, and verifies instrumentation works.
 *
 * <p>Run via: {@code ./gradlew :braintrust-java-agent:smoke-test:spring-boot:smokeTest}
 */
@SpringBootApplication
@RestController
public class SpringBootSmokeTest {

    @GetMapping("/instrumented")
    public String instrumented() {
        return String.valueOf(InstrumentationReflection.isInstrumented());
    }

    public static void main(String[] args) throws Exception {
        System.out.println("[smoke-test] Starting Spring Boot smoke test");

        // Start Spring Boot on a random port
        var app = new SpringApplication(SpringBootSmokeTest.class);
        app.setDefaultProperties(java.util.Map.of(
                "server.port", "0",      // random port
                "logging.level.root", "WARN"
        ));
        var ctx = (ServletWebServerApplicationContext) app.run(args);

        try {
            int port = ctx.getWebServer().getPort();
            System.out.println("[smoke-test] Server started on port " + port);

            // Hit the endpoint
            var client = HttpClient.newHttpClient();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/instrumented"))
                    .GET()
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("[smoke-test] GET /instrumented -> " + response.statusCode() + " " + response.body());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Expected 200, got " + response.statusCode());
            }
            if (!"true".equals(response.body())) {
                throw new RuntimeException(
                        "Expected InstrumentationReflection.isInstrumented() to return true via HTTP, " +
                        "but got: " + response.body());
            }

            System.out.println("=== Smoke test passed ===");
        } finally {
            SpringApplication.exit(ctx);
        }
    }
}
