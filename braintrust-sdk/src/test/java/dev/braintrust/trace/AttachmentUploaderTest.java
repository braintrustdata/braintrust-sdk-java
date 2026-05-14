package dev.braintrust.trace;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.braintrust.api.BraintrustOpenApiClient;
import dev.braintrust.config.BraintrustConfig;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@WireMockTest
public class AttachmentUploaderTest {
    private AttachmentUploader.S3AttachmentUploader uploader;
    private String baseUrl;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        baseUrl = wmRuntimeInfo.getHttpBaseUrl();
        var config = BraintrustConfig.builder().apiKey("test-api-key").apiUrl(baseUrl).build();
        var apiClient = BraintrustOpenApiClient.of(config);
        uploader =
                new AttachmentUploader.S3AttachmentUploader(
                        apiClient, Duration.ofMillis(10_000), 1, Duration.ofMillis(50));
    }

    @AfterEach
    void tearDown() {
        uploader.shutdown(Duration.ofSeconds(0));
    }

    private void stubLoginAndUploadFlow() {
        stubFor(
                post(urlEqualTo("/api/apikey/login"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"org_info\":[{\"id\":\"org-123\",\"name\":\"test-org\"}]}")));

        stubFor(
                post(urlEqualTo("/attachment"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"signedUrl\":\""
                                                        + baseUrl
                                                        + "/upload\",\"headers\":{}}")));

        stubFor(put(urlEqualTo("/upload")).willReturn(aResponse().withStatus(200)));

        stubFor(post(urlEqualTo("/attachment/status")).willReturn(aResponse().withStatus(200)));
    }

    // ── Worker / queue integration tests ──────────────────────────────

    @Test
    void enqueueUploadsSuccessfully() throws Exception {
        stubLoginAndUploadFlow();

        var ref = AttachmentReference.create("test.json", "application/json");
        uploader.enqueue(ref, "{\"key\":\"value\"}".getBytes());
        uploader.forceFlush();

        verify(
                postRequestedFor(urlEqualTo("/attachment"))
                        .withRequestBody(containing("\"key\":\"" + ref.key() + "\"")));
        verify(putRequestedFor(urlEqualTo("/upload")));
        verify(
                postRequestedFor(urlEqualTo("/attachment/status"))
                        .withRequestBody(containing("\"upload_status\":\"done\"")));
    }

    @Test
    void enqueueRejectsAfterShutdown() {
        assertDoesNotThrow(() -> uploader.shutdown());

        var ref = AttachmentReference.create("test.json", "application/json");
        assertFalse(uploader.enqueue(ref, "data".getBytes()));
    }

    @Test
    void uploadFailureShutsDownWorker() throws Exception {
        stubFor(
                post(urlEqualTo("/api/apikey/login"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"org_info\":[{\"id\":\"org-123\",\"name\":\"test-org\"}]}")));

        stubFor(
                post(urlEqualTo("/attachment"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"signedUrl\":\""
                                                        + baseUrl
                                                        + "/upload\",\"headers\":{}}")));

        stubFor(
                put(urlEqualTo("/upload"))
                        .willReturn(aResponse().withStatus(500).withBody("Failed")));

        stubFor(post(urlEqualTo("/attachment/status")).willReturn(aResponse().withStatus(200)));

        var ref = AttachmentReference.create("test.json", "application/json");
        assertTrue(uploader.enqueue(ref, "data".getBytes()));
        // even errors should notify completion
        uploader.forceFlush(Duration.ofSeconds(5));
        assertFalse(uploader.enqueue(ref, "data".getBytes()));

        verify(
                postRequestedFor(urlEqualTo("/attachment/status"))
                        .withRequestBody(containing("\"upload_status\":\"error\""))
                        .withRequestBody(containing("\"error_message\"")));
    }

    // ── S3 HTTP-level tests ───────────────────────────────────────────

    @Nested
    class S3HttpOperations {

        @Test
        void requestUploadUrlSendsCorrectRequest() throws Exception {
            stubFor(
                    post(urlEqualTo("/attachment"))
                            .withHeader("Authorization", equalTo("Bearer test-api-key"))
                            .withHeader("Content-Type", equalTo("application/json"))
                            .willReturn(
                                    aResponse()
                                            .withStatus(200)
                                            .withHeader("Content-Type", "application/json")
                                            .withBody(
                                                    "{\"signedUrl\":\""
                                                            + baseUrl
                                                            + "/upload\",\"headers\":{\"X-Custom\":\"value\"}}")));

            var response =
                    uploader.requestUploadUrl("org-123", "key-1", "test.json", "application/json");

            assertEquals(baseUrl + "/upload", response.signedUrl());
            assertEquals("value", response.headers().get("X-Custom"));

            var requests = findAll(postRequestedFor(urlEqualTo("/attachment")));
            assertEquals(1, requests.size());
        }

        @Test
        void requestUploadUrlThrowsOnMissingSignedUrl() {
            stubFor(
                    post(urlEqualTo("/attachment"))
                            .willReturn(aResponse().withStatus(200).withBody("{\"headers\":{}}")));

            assertThrows(
                    java.io.IOException.class,
                    () ->
                            uploader.requestUploadUrl(
                                    "org-123", "key-1", "test.json", "application/json"));
        }

        @Test
        void requestUploadUrlThrowsOnApiError() {
            stubFor(
                    post(urlEqualTo("/attachment"))
                            .willReturn(aResponse().withStatus(500).withBody("Internal error")));

            assertThrows(
                    java.io.IOException.class,
                    () ->
                            uploader.requestUploadUrl(
                                    "org-123", "key-1", "test.json", "application/json"));
        }

        @Test
        void uploadToSignedUrlIncludesHeaders() throws Exception {
            byte[] testData = "test data".getBytes();

            stubFor(
                    put(urlEqualTo("/upload"))
                            .withHeader("X-Custom-Header", equalTo("custom-value"))
                            .willReturn(aResponse().withStatus(200)));

            uploader.uploadToSignedUrl(
                    baseUrl + "/upload",
                    Map.of("X-Custom-Header", "custom-value"),
                    "application/octet-stream",
                    testData);
        }

        @Test
        void uploadToSignedUrlThrowsOnUploadError() {
            byte[] testData = "test data".getBytes();

            stubFor(
                    put(urlEqualTo("/upload"))
                            .willReturn(aResponse().withStatus(500).withBody("Upload failed")));

            assertThrows(
                    java.io.IOException.class,
                    () ->
                            uploader.uploadToSignedUrl(
                                    baseUrl + "/upload",
                                    Map.of(),
                                    "application/octet-stream",
                                    testData));
        }

        @Test
        void updateUploadStatusSendsCorrectRequest() throws Exception {
            stubFor(
                    post(urlEqualTo("/attachment/status"))
                            .withHeader("Authorization", equalTo("Bearer test-api-key"))
                            .withHeader("Content-Type", equalTo("application/json"))
                            .willReturn(aResponse().withStatus(200)));

            uploader.updateUploadStatus("org-123", "key-1", Map.of("upload_status", "done"));

            verify(
                    postRequestedFor(urlEqualTo("/attachment/status"))
                            .withRequestBody(containing("\"key\":\"key-1\""))
                            .withRequestBody(containing("\"org_id\":\"org-123\""))
                            .withRequestBody(containing("\"upload_status\":\"done\"")));
        }

        @Test
        void updateUploadStatusIncludesErrorMessage() throws Exception {
            stubFor(post(urlEqualTo("/attachment/status")).willReturn(aResponse().withStatus(200)));

            uploader.updateUploadStatus(
                    "org-123",
                    "key-1",
                    Map.of("upload_status", "error", "error_message", "something went wrong"));

            verify(
                    postRequestedFor(urlEqualTo("/attachment/status"))
                            .withRequestBody(
                                    containing("\"error_message\":\"something went wrong\"")));
        }

        @Test
        void updateUploadStatusThrowsOnApiError() {
            stubFor(
                    post(urlEqualTo("/attachment/status"))
                            .willReturn(aResponse().withStatus(500).withBody("Error")));

            assertThrows(
                    java.io.IOException.class,
                    () ->
                            uploader.updateUploadStatus(
                                    "org-123", "key-1", Map.of("upload_status", "done")));
        }

        @Test
        void uploadToNonAzureUrlDoesNotAddBlobHeader() throws Exception {
            byte[] testData = "azure test".getBytes();

            stubFor(put(urlEqualTo("/non-azure-upload")).willReturn(aResponse().withStatus(200)));

            uploader.uploadToSignedUrl(
                    baseUrl + "/non-azure-upload", Map.of(), "application/octet-stream", testData);

            // Non-Azure URL should NOT have the x-ms-blob-type header
            verify(
                    putRequestedFor(urlEqualTo("/non-azure-upload"))
                            .withoutHeader("x-ms-blob-type"));
        }

        @Test
        void uploadUrlResponseDefaultsNullHeadersToEmptyMap() {
            var response =
                    new AttachmentUploader.S3AttachmentUploader.UploadUrlResponse(
                            "https://example.com/upload", null);

            assertNotNull(response.headers());
            assertTrue(response.headers().isEmpty());
        }

        @Test
        void requestUploadUrlDefaultsNullHeadersToEmptyMap() throws Exception {
            stubFor(
                    post(urlEqualTo("/attachment"))
                            .willReturn(
                                    aResponse()
                                            .withStatus(200)
                                            .withHeader("Content-Type", "application/json")
                                            .withBody(
                                                    "{\"signedUrl\":\"" + baseUrl + "/upload\"}")));

            var response =
                    uploader.requestUploadUrl("org-123", "key-1", "test.json", "application/json");

            assertNotNull(response.headers());
            assertTrue(response.headers().isEmpty());
        }

        @Test
        void retryOnServerError() throws Exception {
            var config = BraintrustConfig.builder().apiKey("test-api-key").apiUrl(baseUrl).build();
            var apiClient = BraintrustOpenApiClient.of(config);
            var retryUploader =
                    new AttachmentUploader.S3AttachmentUploader(
                            apiClient, Duration.ofSeconds(30), 2, Duration.ofMillis(100));

            // First two requests fail with 500, third succeeds
            stubFor(
                    post(urlEqualTo("/attachment"))
                            .inScenario("retry-test")
                            .whenScenarioStateIs("Started")
                            .willReturn(aResponse().withStatus(500).withBody("Error"))
                            .willSetStateTo("retry-1"));

            stubFor(
                    post(urlEqualTo("/attachment"))
                            .inScenario("retry-test")
                            .whenScenarioStateIs("retry-1")
                            .willReturn(aResponse().withStatus(500).withBody("Error"))
                            .willSetStateTo("retry-2"));

            stubFor(
                    post(urlEqualTo("/attachment"))
                            .inScenario("retry-test")
                            .whenScenarioStateIs("retry-2")
                            .willReturn(
                                    aResponse()
                                            .withStatus(200)
                                            .withHeader("Content-Type", "application/json")
                                            .withBody(
                                                    "{\"signedUrl\":\""
                                                            + baseUrl
                                                            + "/upload\",\"headers\":{}}")));

            var response =
                    retryUploader.requestUploadUrl(
                            "org-123", "key-1", "test.json", "application/json");

            assertEquals(baseUrl + "/upload", response.signedUrl());
            verify(3, postRequestedFor(urlEqualTo("/attachment")));

            retryUploader.shutdown(Duration.ofSeconds(0));
        }
    }
}
