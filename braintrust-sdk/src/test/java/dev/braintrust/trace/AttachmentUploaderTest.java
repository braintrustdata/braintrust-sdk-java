package dev.braintrust.trace;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import dev.braintrust.api.BraintrustOpenApiClient;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.json.BraintrustJsonMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

public class AttachmentUploaderTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final ResponseGates RESPONSE_GATES = new ResponseGates();

    @RegisterExtension
    static WireMockExtension wireMock =
            WireMockExtension.newInstance()
                    .options(wireMockConfig().dynamicPort().extensions(RESPONSE_GATES))
                    .configureStaticDsl(true)
                    .build();

    private AttachmentUploader.S3AttachmentUploader uploader;
    private String baseUrl;
    private BraintrustConfig config;

    @BeforeEach
    void setUp() {
        baseUrl = wireMock.getRuntimeInfo().getHttpBaseUrl();
        config =
                BraintrustConfig.builder()
                        .apiKey("test-api-key")
                        .apiUrl(baseUrl)
                        .attachmentUploaderRequestTimeout(Duration.ofMillis(10_000))
                        .attachmentUploaderMaxRetries(1)
                        .attachmentUploaderInitialRetryDelay(Duration.ofMillis(50))
                        .build();
        var apiClient = BraintrustOpenApiClient.of(config);
        uploader = new AttachmentUploader.S3AttachmentUploader(apiClient, config);
    }

    @AfterEach
    void tearDown() {
        uploader.shutdown(Duration.ofSeconds(0));
        RESPONSE_GATES.reset();
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
    void attachmentFreeProcessorTrafficNeverCallsUploadEndpoints() {
        var processor = new AttachmentProcessor(config, uploader);
        String text = "{\"action\":{\"query\":\"weather\"},\"status\":\"completed\"}";
        for (int i = 0; i < 10_000; i++) {
            assertEquals(text, processor.processAndUpload(text));
        }
        assertTrue(uploader.forceFlush(WAIT));
        verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void concurrentProcessorUploadsRecoverAfterSignedUrlFailure() throws Exception {
        var sleeper = useControlledRecovery(Duration.ofMillis(50), 128);
        stubLoginAndUploadFlow();
        var outage =
                stubFor(post(urlEqualTo("/attachment")).willReturn(aResponse().withStatus(403)));
        var processor = new AttachmentProcessor(config, uploader);
        Map<String, String> accepted;
        // Hold the first failure until every producer has enqueued its initial batch.
        try (var response = RESPONSE_GATES.hold("/attachment")) {
            accepted = processConcurrently(processor, "before outage");
            response.awaitRequest();
            response.release();
            assertEquals(50L, sleeper.awaitDelay());
        }
        assertFalse(uploader.forceFlush(Duration.ZERO));
        var signedUrlRequests = findAll(postRequestedFor(urlEqualTo("/attachment")));
        assertEquals(1, signedUrlRequests.size(), "The failed job must block later queued jobs");
        String retainedKey =
                BraintrustJsonMapper.get()
                        .readTree(signedUrlRequests.get(0).getBody())
                        .get("key")
                        .asText();
        var paused = processConcurrently(processor, "during outage");
        for (var entry : paused.entrySet()) {
            assertEquals(dataUriJson(entry.getKey().getBytes(UTF_8)), entry.getValue());
        }
        verify(1, postRequestedFor(urlEqualTo("/attachment")));
        verify(0, putRequestedFor(anyUrl()));

        removeStub(outage);
        // Each signed URL identifies its attachment so byte/key mismatches are observable.
        stubFor(
                post(urlEqualTo("/attachment"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"signedUrl\":\""
                                                        + baseUrl
                                                        + "/upload/{{jsonPath request.body"
                                                        + " '$.key'}}\",\"headers\":{}}")
                                        .withTransformers("response-template")));
        stubFor(put(urlPathMatching("/upload/.*")).willReturn(aResponse().withStatus(200)));
        sleeper.release();
        assertTrue(
                uploader.forceFlush(WAIT),
                "Retained attachments must recover on the same uploader");

        accepted.putAll(processConcurrently(processor, "after recovery"));
        assertTrue(uploader.forceFlush(WAIT));
        var expected = new HashMap<String, byte[]>();
        for (var entry : accepted.entrySet()) {
            var reference = BraintrustJsonMapper.get().readTree(entry.getValue()).get("url");
            assertEquals("braintrust_attachment", reference.get("type").asText());
            assertNull(
                    expected.put(reference.get("key").asText(), entry.getKey().getBytes(UTF_8)),
                    "Concurrent attachments must have distinct keys");
        }
        var uploads = findAll(putRequestedFor(urlPathMatching("/upload/.*")));
        assertEquals(
                expected.size(), uploads.size(), "Every accepted attachment uploads exactly once");
        for (var upload : uploads) {
            String key = upload.getUrl().substring("/upload/".length());
            byte[] bytes = expected.remove(key);
            assertNotNull(bytes, "Unexpected or duplicate upload: " + key);
            assertArrayEquals(
                    bytes, upload.getBody(), "Bytes must match the exported reference: " + key);
        }
        assertTrue(expected.isEmpty(), "No accepted attachments may be lost");
        verify(accepted.size() + 1, postRequestedFor(urlEqualTo("/attachment")));
        verify(
                2,
                postRequestedFor(urlEqualTo("/attachment"))
                        .withRequestBody(matchingJsonPath("$.key", equalTo(retainedKey))));
        verify(
                accepted.size(),
                postRequestedFor(urlEqualTo("/attachment/status"))
                        .withRequestBody(
                                matchingJsonPath("$.status.upload_status", equalTo("done"))));
        verifyNoErrorStatus();
    }

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
    void enqueueRejectsAfterShutdownBeforeWorkerStarts() throws Exception {
        assertTrue(uploader.forceFlush(Duration.ZERO));
        uploader.shutdown(Duration.ZERO);
        assertFalse(uploader.isAcceptingJobs());
        assertTrue(uploader.forceFlush(Duration.ZERO));

        var ref = AttachmentReference.create("test.json", "application/json");
        assertFalse(uploader.enqueue(ref, "data".getBytes()));
    }

    @Test
    void retainsFailedAttachmentAndResumesProcessorAfterRecovery() throws Exception {
        var sleeper = useControlledRecovery(Duration.ofMillis(500), 2);
        stubLoginAndUploadFlow();
        var first = AttachmentReference.create("first.txt", "text/plain");
        var second = AttachmentReference.create("second.txt", "text/plain");
        byte[] firstBytes = "original first attachment".getBytes(UTF_8);
        byte[] secondBytes = "queued second attachment".getBytes(UTF_8);
        stubSignedUrl(first, "/first");
        stubSignedUrl(second, "/second");
        stubFor(put(urlEqualTo("/first")).willReturn(aResponse().withStatus(403)));
        stubFor(put(urlEqualTo("/second")).willReturn(aResponse().withStatus(200)));
        var processor = new AttachmentProcessor(config, uploader);
        String inline = dataUriJson("paused attachment remains inline".getBytes(UTF_8));

        try (var firstResponse = RESPONSE_GATES.hold("/first");
                var secondResponse = RESPONSE_GATES.hold("/second")) {
            assertTrue(uploader.enqueue(first, firstBytes));
            firstResponse.awaitRequest();
            assertTrue(uploader.enqueue(second, secondBytes));
            firstResponse.release();
            assertEquals(500L, sleeper.awaitDelay());

            assertFalse(uploader.isAcceptingJobs());
            assertFalse(
                    uploader.enqueue(
                            AttachmentReference.create("rejected", "text/plain"), firstBytes));
            assertEquals(inline, processor.processAndUpload(inline));
            verify(0, putRequestedFor(urlEqualTo("/second")));
            assertFalse(uploader.forceFlush(Duration.ofMillis(20)));
            verifyNoErrorStatus();

            // A new URL proves recovery renews the signed URL rather than reusing an expired one.
            stubSignedUrl(first, "/first-recovered");
            stubFor(put(urlEqualTo("/first-recovered")).willReturn(aResponse().withStatus(200)));
            sleeper.release();
            secondResponse.awaitRequest();
            assertTrue(uploader.isAcceptingJobs(), "Recovery must not wait for the entire queue");
            verify(
                    2,
                    postRequestedFor(urlEqualTo("/attachment"))
                            .withRequestBody(matchingJsonPath("$.key", equalTo(first.key()))));
            verify(
                    1,
                    putRequestedFor(urlEqualTo("/first"))
                            .withRequestBody(binaryEqualTo(firstBytes)));
            verify(
                    1,
                    putRequestedFor(urlEqualTo("/first-recovered"))
                            .withRequestBody(binaryEqualTo(firstBytes)));

            byte[] resumedBytes = "new attachment after recovery".getBytes(UTF_8);
            var converted =
                    BraintrustJsonMapper.get()
                            .readTree(processor.processAndUpload(dataUriJson(resumedBytes)));
            var reference = converted.get("url");
            assertEquals("braintrust_attachment", reference.get("type").asText());
            String resumedKey = reference.get("key").asText();
            secondResponse.release();
            assertTrue(uploader.forceFlush(WAIT));
            verify(
                    1,
                    putRequestedFor(urlEqualTo("/second"))
                            .withRequestBody(binaryEqualTo(secondBytes)));
            verify(
                    1,
                    postRequestedFor(urlEqualTo("/attachment"))
                            .withRequestBody(matchingJsonPath("$.key", equalTo(resumedKey))));
            verify(
                    1,
                    putRequestedFor(urlEqualTo("/upload"))
                            .withRequestBody(binaryEqualTo(resumedBytes)));
            verifyNoErrorStatus();
        }
    }

    @ParameterizedTest
    @CsvSource({"400, true", "413, true", "415, true", "400, false", "413, false", "415, false"})
    void invalidAttachmentIsDroppedWithoutBlockingQueuedUploads(int status, boolean signedUrl)
            throws Exception {
        useControlledRecovery(Duration.ofMillis(500), 2);
        stubLoginAndUploadFlow();
        var invalid = AttachmentReference.create("invalid", "text/plain");
        var queued = AttachmentReference.create("queued", "text/plain");
        stubSignedUrl(invalid, "/invalid");
        stubSignedUrl(queued, "/queued");
        if (signedUrl) {
            stubFor(
                    post(urlEqualTo("/attachment"))
                            .withRequestBody(matchingJsonPath("$.key", equalTo(invalid.key())))
                            .willReturn(aResponse().withStatus(status)));
        } else {
            stubFor(put(urlEqualTo("/invalid")).willReturn(aResponse().withStatus(status)));
        }
        stubFor(put(urlEqualTo("/queued")).willReturn(aResponse().withStatus(200)));

        try (var invalidResponse = RESPONSE_GATES.hold(signedUrl ? "/attachment" : "/invalid");
                var queuedResponse = RESPONSE_GATES.hold("/queued")) {
            assertTrue(uploader.enqueue(invalid, "invalid".getBytes(UTF_8)));
            invalidResponse.awaitRequest();
            assertTrue(uploader.enqueue(queued, "queued".getBytes(UTF_8)));
            invalidResponse.release();
            queuedResponse.awaitRequest();
            assertTrue(uploader.isAcceptingJobs());
            var later = AttachmentReference.create("later", "text/plain");
            assertTrue(uploader.enqueue(later, "later".getBytes(UTF_8)));
            queuedResponse.release();
            uploader.shutdown(WAIT);
            assertFalse(uploader.forceFlush(Duration.ZERO), "A dropped upload is not successful");
            verify(
                    1,
                    postRequestedFor(urlEqualTo("/attachment"))
                            .withRequestBody(matchingJsonPath("$.key", equalTo(invalid.key()))));
            verify(signedUrl ? 0 : 1, putRequestedFor(urlEqualTo("/invalid")));
            verify(
                    1,
                    postRequestedFor(urlEqualTo("/attachment/status"))
                            .withRequestBody(matchingJsonPath("$.key", equalTo(invalid.key())))
                            .withRequestBody(
                                    matchingJsonPath("$.status.upload_status", equalTo("error")))
                            .withRequestBody(matchingJsonPath("$.status.error_message")));
            verify(
                    1,
                    putRequestedFor(urlEqualTo("/queued"))
                            .withRequestBody(binaryEqualTo("queued".getBytes(UTF_8))));
            verify(
                    1,
                    postRequestedFor(urlEqualTo("/attachment/status"))
                            .withRequestBody(matchingJsonPath("$.key", equalTo(later.key())))
                            .withRequestBody(
                                    matchingJsonPath("$.status.upload_status", equalTo("done"))));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void permanentFailureDuringRecoveryDropsJobAndReopensAdmission(boolean statusReportingFails)
            throws Exception {
        var sleeper = useControlledRecovery(Duration.ofMillis(500), 2);
        stubLoginAndUploadFlow();
        stubFor(put(urlEqualTo("/upload")).willReturn(aResponse().withStatus(403)));
        var invalid = AttachmentReference.create("invalid", "text/plain");
        assertTrue(uploader.enqueue(invalid, "invalid".getBytes(UTF_8)));
        assertEquals(500L, sleeper.awaitDelay());
        verifyNoErrorStatus();
        if (statusReportingFails) {
            stubFor(
                    post(urlEqualTo("/attachment/status"))
                            .withRequestBody(matchingJsonPath("$.key", equalTo(invalid.key())))
                            .willReturn(aResponse().withStatus(500)));
        }
        stubFor(put(urlEqualTo("/upload")).willReturn(aResponse().withStatus(415)));
        sleeper.release();
        assertFalse(uploader.forceFlush(WAIT));
        assertTrue(uploader.isAcceptingJobs(), "Dropping the retained job must release the pause");
        stubFor(put(urlEqualTo("/upload")).willReturn(aResponse().withStatus(200)));
        assertTrue(
                uploader.enqueue(
                        AttachmentReference.create("later", "text/plain"),
                        "later".getBytes(UTF_8)));
        uploader.shutdown(WAIT);
        verify(
                2,
                putRequestedFor(urlEqualTo("/upload"))
                        .withRequestBody(binaryEqualTo("invalid".getBytes(UTF_8))));
        verify(
                1,
                putRequestedFor(urlEqualTo("/upload"))
                        .withRequestBody(binaryEqualTo("later".getBytes(UTF_8))));
        verify(
                statusReportingFails ? 2 : 1,
                postRequestedFor(urlEqualTo("/attachment/status"))
                        .withRequestBody(matchingJsonPath("$.key", equalTo(invalid.key())))
                        .withRequestBody(
                                matchingJsonPath("$.status.upload_status", equalTo("error")))
                        .withRequestBody(matchingJsonPath("$.status.error_message")));
    }

    @Test
    void exhaustedHttpRetriesEnterRecoveryAndCanSucceed() throws Exception {
        var sleeper = useControlledRecovery(Duration.ofMillis(50), 2);
        stubLoginAndUploadFlow();
        stubFor(put(urlEqualTo("/upload")).willReturn(aResponse().withStatus(500)));
        var reference = AttachmentReference.create("retry.txt", "text/plain");
        byte[] bytes = "transient upload".getBytes(UTF_8);
        assertTrue(uploader.enqueue(reference, bytes));

        assertEquals(50L, sleeper.awaitDelay());
        verify(2, putRequestedFor(urlEqualTo("/upload")));
        assertFalse(uploader.isAcceptingJobs());
        assertFalse(uploader.forceFlush(Duration.ZERO));
        verifyNoErrorStatus();

        stubFor(put(urlEqualTo("/upload")).willReturn(aResponse().withStatus(200)));
        sleeper.release();
        assertTrue(uploader.forceFlush(WAIT));
        assertTrue(uploader.isAcceptingJobs());
        verify(
                2,
                postRequestedFor(urlEqualTo("/attachment"))
                        .withRequestBody(matchingJsonPath("$.key", equalTo(reference.key()))));
        verify(3, putRequestedFor(urlEqualTo("/upload")).withRequestBody(binaryEqualTo(bytes)));
    }

    @Test
    void recoveryBackoffStaysCappedIndefinitelyAndResetsAfterSuccess() throws Exception {
        var sleeper = useControlledRecovery(Duration.ofMillis(500), 2);
        stubLoginAndUploadFlow();
        var first = AttachmentReference.create("first.txt", "text/plain");
        var queued = AttachmentReference.create("queued.txt", "text/plain");
        stubSignedUrl(first, "/first");
        stubSignedUrl(queued, "/queued");
        stubFor(put(urlEqualTo("/first")).willReturn(aResponse().withStatus(403)));
        stubFor(put(urlEqualTo("/queued")).willReturn(aResponse().withStatus(200)));
        try (var firstResponse = RESPONSE_GATES.hold("/first")) {
            assertTrue(uploader.enqueue(first, "first".getBytes(UTF_8)));
            firstResponse.awaitRequest();
            assertTrue(uploader.enqueue(queued, "queued".getBytes(UTF_8)));
            firstResponse.release();
            long[] delays = {
                500, 1_000, 2_000, 4_000, 8_000, 16_000, 32_000, 64_000, 120_000, 120_000
            };
            for (int i = 0; i < delays.length; i++) {
                assertEquals(delays[i], sleeper.awaitDelay());
                assertFalse(uploader.isAcceptingJobs());
                verify(i + 1, putRequestedFor(urlEqualTo("/first")));
                verify(
                        0,
                        postRequestedFor(urlEqualTo("/attachment"))
                                .withRequestBody(matchingJsonPath("$.key", equalTo(queued.key()))));
                if (i + 1 < delays.length) {
                    sleeper.release();
                }
            }
            stubFor(put(urlEqualTo("/first")).willReturn(aResponse().withStatus(200)));
            sleeper.release();
            assertTrue(uploader.forceFlush(WAIT));
            verify(1, putRequestedFor(urlEqualTo("/queued")));
        }

        var later = AttachmentReference.create("later.txt", "text/plain");
        stubFor(put(urlEqualTo("/upload")).willReturn(aResponse().withStatus(403)));
        assertTrue(uploader.enqueue(later, "later".getBytes(UTF_8)));
        assertEquals(500L, sleeper.awaitDelay(), "A new outage starts at the initial delay");
        stubFor(put(urlEqualTo("/upload")).willReturn(aResponse().withStatus(200)));
        sleeper.release();
        assertTrue(uploader.forceFlush(WAIT));
        verifyNoErrorStatus();
    }

    @Test
    void zeroTimeoutShutdownInterruptsRecoveryWithoutAnotherAttempt() throws Exception {
        var sleeper = useControlledRecovery(Duration.ofMillis(500), 2);
        stubLoginAndUploadFlow();
        stubFor(put(urlEqualTo("/upload")).willReturn(aResponse().withStatus(403)));
        var reference = AttachmentReference.create("pending.txt", "text/plain");
        var queued = AttachmentReference.create("queued.txt", "text/plain");
        try (var response = RESPONSE_GATES.hold("/upload")) {
            assertTrue(uploader.enqueue(reference, "pending".getBytes(UTF_8)));
            response.awaitRequest();
            assertTrue(uploader.enqueue(queued, "queued".getBytes(UTF_8)));
            response.release();
            assertEquals(500L, sleeper.awaitDelay());
        }

        try (var shutdown =
                new WaitingCall<>(
                        () -> {
                            uploader.shutdown(Duration.ZERO);
                            return null;
                        })) {
            shutdown.result();
        }
        sleeper.awaitInterruption();
        assertFalse(uploader.isAcceptingJobs());
        assertFalse(uploader.enqueue(reference, "rejected".getBytes(UTF_8)));
        assertFalse(uploader.forceFlush(WAIT), "Interrupted work is not completed work");
        uploader.shutdown(Duration.ZERO);
        verify(1, postRequestedFor(urlEqualTo("/attachment")));
        verify(1, putRequestedFor(urlEqualTo("/upload")));
        verify(0, postRequestedFor(urlEqualTo("/attachment/status")));
    }

    @Test
    void successfulRecoveryDuringGracefulShutdownDoesNotReopenAdmission() throws Exception {
        var sleeper = useControlledRecovery(Duration.ofMillis(500), 2);
        stubLoginAndUploadFlow();
        stubFor(put(urlEqualTo("/upload")).willReturn(aResponse().withStatus(403)));
        var reference = AttachmentReference.create("pending.txt", "text/plain");
        assertTrue(uploader.enqueue(reference, "pending".getBytes(UTF_8)));
        assertEquals(500L, sleeper.awaitDelay());

        stubFor(put(urlEqualTo("/upload")).willReturn(aResponse().withStatus(200)));
        try (var response = RESPONSE_GATES.hold("/upload")) {
            sleeper.release();
            response.awaitRequest();
            try (var shutdown =
                    new WaitingCall<>(
                            () -> {
                                uploader.shutdown(WAIT);
                                return null;
                            })) {
                shutdown.awaitWaiting();
                assertFalse(uploader.isAcceptingJobs());
                response.release();
                shutdown.result();
            }
        }
        assertTrue(uploader.forceFlush(WAIT));
        assertFalse(uploader.isAcceptingJobs());
        assertFalse(uploader.enqueue(reference, "rejected".getBytes(UTF_8)));
        verify(2, putRequestedFor(urlEqualTo("/upload")));
        verify(
                1,
                postRequestedFor(urlEqualTo("/attachment/status"))
                        .withRequestBody(
                                matchingJsonPath("$.status.upload_status", equalTo("done"))));
    }

    @Test
    void fullQueueRejectsWithoutPausingAndCapacityBecomesUsable() throws Exception {
        useControlledRecovery(Duration.ofMillis(500), 1);
        stubLoginAndUploadFlow();
        var first = AttachmentReference.create("first", "text/plain");
        var second = AttachmentReference.create("second", "text/plain");
        var third = AttachmentReference.create("third", "text/plain");
        stubSignedUrl(first, "/first");
        stubSignedUrl(second, "/second");
        stubFor(put(urlEqualTo("/first")).willReturn(aResponse().withStatus(200)));
        stubFor(put(urlEqualTo("/second")).willReturn(aResponse().withStatus(200)));
        try (var firstResponse = RESPONSE_GATES.hold("/first");
                var secondResponse = RESPONSE_GATES.hold("/second")) {
            assertTrue(uploader.enqueue(first, "first".getBytes(UTF_8)));
            firstResponse.awaitRequest();
            assertTrue(uploader.enqueue(second, "second".getBytes(UTF_8)));
            assertFalse(uploader.enqueue(third, "third".getBytes(UTF_8)));
            assertTrue(uploader.isAcceptingJobs(), "Capacity rejection is not an upload outage");
            firstResponse.release();
            secondResponse.awaitRequest();
            assertTrue(uploader.enqueue(third, "third".getBytes(UTF_8)));
            secondResponse.release();
            assertTrue(uploader.forceFlush(WAIT));
            verify(
                    1,
                    postRequestedFor(urlEqualTo("/attachment"))
                            .withRequestBody(matchingJsonPath("$.key", equalTo(third.key()))));
            verify(
                    1,
                    putRequestedFor(urlEqualTo("/upload"))
                            .withRequestBody(binaryEqualTo("third".getBytes(UTF_8))));
        }
    }

    @Test
    void concurrentProcessorOverflowStaysInlineUntilQueueDrains() throws Exception {
        useControlledRecovery(Duration.ofMillis(50), 1);
        stubLoginAndUploadFlow();
        var processor = new AttachmentProcessor(config, uploader);
        var accepted = new HashMap<String, String>();
        String first = "attachment held in the object store";
        try (var response = RESPONSE_GATES.hold("/upload")) {
            accepted.put(first, processor.processAndUpload(dataUriJson(first.getBytes(UTF_8))));
            response.awaitRequest();
            // One upload is active and the one-slot waiting queue is initially empty.
            var results = processConcurrently(processor, "queue saturation");
            int inline = 0;
            for (var entry : results.entrySet()) {
                String original = dataUriJson(entry.getKey().getBytes(UTF_8));
                if (original.equals(entry.getValue())) {
                    inline++;
                } else {
                    accepted.put(entry.getKey(), entry.getValue());
                }
            }
            assertEquals(2, accepted.size(), "Only the active upload and one queued job fit");
            assertEquals(results.size() - 1, inline, "Overflow must preserve the original JSON");
            assertTrue(uploader.isAcceptingJobs(), "Queue pressure must not pause the uploader");
            assertFalse(uploader.forceFlush(Duration.ZERO));
            response.release();
            assertTrue(uploader.forceFlush(WAIT));
        }

        String later = "attachment after the queue drains";
        accepted.put(later, processor.processAndUpload(dataUriJson(later.getBytes(UTF_8))));
        assertTrue(uploader.forceFlush(WAIT));
        verify(accepted.size(), putRequestedFor(urlEqualTo("/upload")));
        verify(accepted.size(), postRequestedFor(urlEqualTo("/attachment")));
        for (var entry : accepted.entrySet()) {
            var reference = BraintrustJsonMapper.get().readTree(entry.getValue()).get("url");
            assertEquals("braintrust_attachment", reference.get("type").asText());
            verify(
                    1,
                    postRequestedFor(urlEqualTo("/attachment"))
                            .withRequestBody(
                                    matchingJsonPath(
                                            "$.key", equalTo(reference.get("key").asText()))));
            verify(
                    1,
                    putRequestedFor(urlEqualTo("/upload"))
                            .withRequestBody(binaryEqualTo(entry.getKey().getBytes(UTF_8))));
        }
        verify(
                accepted.size(),
                postRequestedFor(urlEqualTo("/attachment/status"))
                        .withRequestBody(
                                matchingJsonPath("$.status.upload_status", equalTo("done"))));
    }

    @Test
    void flushWaitsOnlyForWorkAcceptedBeforeItsSnapshot() throws Exception {
        stubLoginAndUploadFlow();
        assertTrue(uploader.forceFlush(Duration.ZERO));
        var first = AttachmentReference.create("first", "text/plain");
        var later = AttachmentReference.create("later", "text/plain");
        stubSignedUrl(first, "/first");
        stubSignedUrl(later, "/later");
        stubFor(put(urlEqualTo("/first")).willReturn(aResponse().withStatus(200)));
        stubFor(put(urlEqualTo("/later")).willReturn(aResponse().withStatus(200)));
        try (var firstResponse = RESPONSE_GATES.hold("/first");
                var laterResponse = RESPONSE_GATES.hold("/later")) {
            assertTrue(uploader.enqueue(first, "first".getBytes(UTF_8)));
            firstResponse.awaitRequest();
            try (var flush = new WaitingCall<>(() -> uploader.forceFlush(WAIT))) {
                // The dedicated thread has no other blocking operation before forceFlush's wait.
                flush.awaitWaiting();
                assertTrue(uploader.enqueue(later, "later".getBytes(UTF_8)));
                firstResponse.release();
                laterResponse.awaitRequest();
                assertTrue(flush.result(), "A later accepted job must not extend the snapshot");
                assertFalse(uploader.forceFlush(Duration.ZERO));
                laterResponse.release();
                assertTrue(uploader.forceFlush(WAIT));
            }
        }
    }

    private Map<String, String> processConcurrently(AttachmentProcessor processor, String batch)
            throws Exception {
        int producers = 4;
        var ready = new CountDownLatch(producers);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(producers);
        try {
            var futures = new ArrayList<Future<Map<String, String>>>();
            for (int producer = 0; producer < producers; producer++) {
                int id = producer;
                futures.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    assertTrue(start.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
                                    var results = new HashMap<String, String>();
                                    for (int i = 0; i < 8; i++) {
                                        String content =
                                                batch + " producer=" + id + " attachment=" + i;
                                        results.put(
                                                content,
                                                processor.processAndUpload(
                                                        dataUriJson(content.getBytes(UTF_8))));
                                    }
                                    return results;
                                }));
            }
            assertTrue(ready.await(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            start.countDown();
            var results = new HashMap<String, String>();
            for (var future : futures) {
                results.putAll(future.get(WAIT.toMillis(), TimeUnit.MILLISECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
            assertTrue(
                    pool.awaitTermination(WAIT.toMillis(), TimeUnit.MILLISECONDS),
                    "Processor caller threads must terminate");
        }
    }

    private ControlledSleeper useControlledRecovery(Duration initialDelay, int queueSize) {
        uploader.shutdown(Duration.ZERO);
        config =
                BraintrustConfig.builder()
                        .apiKey("test-api-key")
                        .apiUrl(baseUrl)
                        .autoConvertAIAttachments(true)
                        .attachmentUploaderRequestTimeout(Duration.ofSeconds(10))
                        .attachmentUploaderMaxRetries(1)
                        .attachmentUploaderInitialRetryDelay(initialDelay)
                        .attachmentUploaderQueueSize(queueSize)
                        .build();
        var sleeper = new ControlledSleeper();
        uploader =
                new AttachmentUploader.S3AttachmentUploader(
                        BraintrustOpenApiClient.of(config), config, sleeper);
        return sleeper;
    }

    private void stubSignedUrl(AttachmentReference reference, String path) {
        stubFor(
                post(urlEqualTo("/attachment"))
                        .withRequestBody(matchingJsonPath("$.key", equalTo(reference.key())))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"signedUrl\":\""
                                                        + baseUrl
                                                        + path
                                                        + "\",\"headers\":{}}")));
    }

    private static String dataUriJson(byte[] bytes) {
        return "{\"url\":\"data:text/plain;base64,"
                + Base64.getEncoder().encodeToString(bytes)
                + "\"}";
    }

    private static void verifyNoErrorStatus() {
        verify(
                0,
                postRequestedFor(urlEqualTo("/attachment/status"))
                        .withRequestBody(
                                matchingJsonPath("$.status.upload_status", equalTo("error"))));
    }

    private static final class ControlledSleeper
            implements AttachmentUploader.S3AttachmentUploader.RecoverySleeper {
        private final BlockingQueue<Long> delays = new LinkedBlockingQueue<>();
        private final Semaphore retries = new Semaphore(0);
        private final CountDownLatch interrupted = new CountDownLatch(1);

        @Override
        public void sleep(long delayMillis) throws InterruptedException {
            delays.add(delayMillis);
            try {
                retries.acquire();
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
        }

        long awaitDelay() throws InterruptedException {
            Long delay = delays.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertNotNull(delay, "The worker did not enter recovery sleep");
            return delay;
        }

        void release() {
            retries.release();
        }

        void awaitInterruption() throws InterruptedException {
            assertTrue(
                    interrupted.await(WAIT.toMillis(), TimeUnit.MILLISECONDS),
                    "Recovery sleep was not interrupted");
        }
    }

    private static final class ResponseGates implements ResponseDefinitionTransformerV2 {
        private final Map<String, ResponseGate> gates = new ConcurrentHashMap<>();

        ResponseGate hold(String path) {
            var gate = new ResponseGate();
            assertNull(gates.putIfAbsent(path, gate), "A response gate already exists for " + path);
            return gate;
        }

        @Override
        public ResponseDefinition transform(ServeEvent event) {
            var gate = gates.remove(event.getRequest().getUrl());
            if (gate != null) {
                gate.entered.countDown();
                try {
                    if (!gate.released.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Response gate was not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Response gate interrupted", e);
                }
            }
            return event.getResponseDefinition();
        }

        void reset() {
            gates.values().forEach(ResponseGate::release);
            gates.clear();
        }

        @Override
        public boolean applyGlobally() {
            return true;
        }

        @Override
        public String getName() {
            return "attachment-response-gates";
        }
    }

    private static final class ResponseGate implements AutoCloseable {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        void awaitRequest() throws InterruptedException {
            assertTrue(
                    entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS),
                    "Expected HTTP request did not arrive");
        }

        void release() {
            released.countDown();
        }

        @Override
        public void close() {
            release();
        }
    }

    /** Observes only the public caller thread, never the uploader's private state. */
    private static final class WaitingCall<T> implements AutoCloseable {
        private final FutureTask<T> call;
        private final Thread thread;

        WaitingCall(Callable<T> action) {
            call = new FutureTask<>(action);
            thread = new Thread(call, "attachment-uploader-test-caller");
            thread.setDaemon(true);
            thread.start();
        }

        void awaitWaiting() {
            long start = System.nanoTime();
            while (System.nanoTime() - start < WAIT.toNanos()) {
                if (thread.getState() == Thread.State.TIMED_WAITING
                        || thread.getState() == Thread.State.WAITING) {
                    return;
                }
                assertFalse(call.isDone(), "Call returned before waiting for accepted work");
                Thread.yield();
            }
            fail("Caller did not begin waiting");
        }

        T result() throws Exception {
            return call.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() throws InterruptedException {
            thread.interrupt();
            thread.join(WAIT.toMillis());
            assertFalse(thread.isAlive(), "Caller thread did not terminate");
        }
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
            var config =
                    BraintrustConfig.builder()
                            .apiKey("test-api-key")
                            .apiUrl(baseUrl)
                            .attachmentUploaderMaxRetries(2)
                            .attachmentUploaderInitialRetryDelay(Duration.ofMillis(100))
                            .build();
            var apiClient = BraintrustOpenApiClient.of(config);
            var retryUploader = new AttachmentUploader.S3AttachmentUploader(apiClient, config);

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
