package dev.braintrust;

import static com.github.tomakehurst.wiremock.client.WireMock.recordSpec;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultipartRequestMatchingTransformerTest {
    @TempDir Path directory;

    @Test
    void recordedMultipartMatchesNewBoundaryButRejectsChangedFieldsAndFile() throws Exception {
        var backend = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        backend.createContext(
                "/upload",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(200, 2);
                    try (var body = exchange.getResponseBody()) {
                        body.write("OK".getBytes(StandardCharsets.UTF_8));
                    }
                });
        backend.start();
        var serverReference = new AtomicReference<WireMockServer>();
        var recorder =
                new WireMockServer(
                        wireMockConfig()
                                .dynamicPort()
                                .usingFilesUnderDirectory(directory.toString())
                                .extensions(
                                        new MultipartRequestMatchingTransformer(
                                                () -> serverReference.get().getAllServeEvents())));
        serverReference.set(recorder);
        recorder.start();
        try {
            recorder.startRecording(
                    recordSpec()
                            .forTarget("http://localhost:" + backend.getAddress().getPort())
                            .captureHeader("Content-Type")
                            .makeStubsPersistent(false)
                            .chooseBodyMatchTypeAutomatically(true, false, false)
                            .transformers(MultipartRequestMatchingTransformer.NAME));
            byte[] audio = {0, 1, 2, (byte) 0xff};
            assertEquals(200, upload(recorder.baseUrl(), "record-boundary", "whisper-1", audio));
            var recording = recorder.stopRecording().getStubMappings().get(0);
            // Round-trip the generated mapping exactly as a saved cassette is loaded for replay.
            var replay = StubMapping.buildFrom(recording.toString());
            recorder.resetAll();
            recorder.addStubMapping(replay);
            backend.stop(0);

            assertEquals(200, upload(recorder.baseUrl(), "replay-boundary", "whisper-1", audio));
            assertEquals(404, upload(recorder.baseUrl(), "replay-boundary", "other-model", audio));
            assertEquals(
                    404,
                    upload(
                            recorder.baseUrl(),
                            "replay-boundary",
                            "whisper-1",
                            new byte[] {0, 1, 3}));
        } finally {
            recorder.stop();
            backend.stop(0);
        }
    }

    private static int upload(String baseUrl, String boundary, String model, byte[] audio)
            throws Exception {
        var body = new ByteArrayOutputStream();
        body.write(
                ("--"
                                + boundary
                                + "\r\n"
                                + "Content-Disposition: form-data; name=\"model\"\r\n\r\n"
                                + model
                                + "\r\n--"
                                + boundary
                                + "\r\n"
                                + "Content-Disposition: form-data; name=\"file\";"
                                + " filename=\"speech.wav\"\r\n"
                                + "Content-Type: audio/wav\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        body.write(audio);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request =
                HttpRequest.newBuilder(URI.create(baseUrl + "/upload"))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                        .build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }
}
