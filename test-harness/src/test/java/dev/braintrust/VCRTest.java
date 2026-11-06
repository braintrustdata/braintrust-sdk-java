package dev.braintrust;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VCRTest {
    @TempDir Path tempDir;

    @Test
    void offForwardsWithoutCreatingCassettes() throws Exception {
        var upstream = new WireMockServer(wireMockConfig().dynamicPort());
        upstream.start();
        try {
            upstream.stubFor(
                    post(urlEqualTo("/v1/chat?stream=true"))
                            .withHeader("Authorization", equalTo("Bearer test-token"))
                            .withRequestBody(equalToJson("{\"input\":\"hello\"}"))
                            .willReturn(aResponse().withStatus(201).withBody("live")));
            Path root = tempDir.resolve("absent");
            assertEquals("live", request(root, VCR.VcrMode.OFF, upstream.baseUrl()));
            assertFalse(Files.exists(root), "OFF must not create cassette directories");
            upstream.verify(1, postRequestedFor(urlEqualTo("/v1/chat?stream=true")));
        } finally {
            upstream.stop();
        }
    }

    @Test
    void offIgnoresCassettesWithoutChangingRecordings() throws Exception {
        var upstream = new WireMockServer(wireMockConfig().dynamicPort());
        upstream.start();
        String baseUrl = upstream.baseUrl();
        try {
            upstream.stubFor(
                    post(anyUrl()).willReturn(aResponse().withStatus(201).withBody("recorded")));
            assertEquals("recorded", request(tempDir, VCR.VcrMode.RECORD, baseUrl));
            Map<String, String> recordedFiles = snapshot(tempDir);
            upstream.resetMappings();
            upstream.stubFor(
                    post(anyUrl()).willReturn(aResponse().withStatus(201).withBody("fresh")));
            assertEquals("fresh", request(tempDir, VCR.VcrMode.OFF, baseUrl));
            assertEquals(
                    recordedFiles,
                    snapshot(tempDir),
                    "OFF must not change cassette bytes or paths");
            upstream.stop();
            assertEquals("recorded", request(tempDir, VCR.VcrMode.REPLAY, baseUrl));
        } finally {
            upstream.stop();
        }
    }

    private String request(Path root, VCR.VcrMode mode, String upstream) throws Exception {
        String target = upstream + "/v1";
        var vcr = new VCR(root + "/", mode, Map.of(target, "provider"), List.of());
        try {
            vcr.start();
            var request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            vcr.getUrlForTargetBase(target) + "/chat?stream=true"))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer test-token")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"input\":\"hello\"}"))
                            .build();
            var response =
                    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(201, response.statusCode(), response.body());
            return response.body();
        } finally {
            vcr.stop();
        }
    }

    private Map<String, String> snapshot(Path root) throws Exception {
        var result = new TreeMap<String, String>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                result.put(
                        root.relativize(path).toString(),
                        Files.isDirectory(path)
                                ? "directory"
                                : Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
            }
        }
        return result;
    }
}
