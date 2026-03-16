package dev.braintrust.smoketest.tomcat;

import dev.braintrust.InstrumentationReflection;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;

/**
 * Smoke test that starts an embedded Tomcat with the Braintrust agent attached, deploys a servlet,
 * hits it over HTTP, and verifies instrumentation works.
 *
 * <p>Run via: {@code ./gradlew :braintrust-java-agent:smoke-test:tomcat:smokeTest}
 *
 * <p>This tests the agent against Tomcat's classloader hierarchy directly, without any framework
 * abstraction.
 */
public class TomcatSmokeTest {

    public static class InstrumentedServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("text/plain");
            resp.getWriter().print(InstrumentationReflection.isInstrumented());
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("[smoke-test] Starting Tomcat smoke test");

        var baseDir = Files.createTempDirectory("tomcat-smoke-test");
        var tomcat = new Tomcat();
        tomcat.setPort(0);
        tomcat.setBaseDir(baseDir.toString());
        tomcat.getConnector(); // trigger connector init

        Context ctx = tomcat.addContext("", baseDir.toString());
        Tomcat.addServlet(ctx, "instrumented", new InstrumentedServlet());
        ctx.addServletMappingDecoded("/instrumented", "instrumented");

        tomcat.start();

        try {
            int port = tomcat.getConnector().getLocalPort();
            System.out.println("[smoke-test] Tomcat started on port " + port);

            var client = HttpClient.newHttpClient();
            var request =
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + port + "/instrumented"))
                            .GET()
                            .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println(
                    "[smoke-test] GET /instrumented -> "
                            + response.statusCode()
                            + " "
                            + response.body());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Expected 200, got " + response.statusCode());
            }
            if (!"true".equals(response.body())) {
                throw new RuntimeException(
                        "Expected InstrumentationReflection.isInstrumented() to return true via"
                                + " HTTP, but got: "
                                + response.body());
            }

            System.out.println("=== Smoke test passed ===");
        } finally {
            tomcat.stop();
            tomcat.destroy();
        }
    }
}
