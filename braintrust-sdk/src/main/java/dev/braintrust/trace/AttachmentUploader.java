package dev.braintrust.trace;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.braintrust.api.BraintrustOpenApiClient;
import dev.braintrust.json.BraintrustJsonMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * Uploads Braintrust attachments in the background.
 *
 * <p>Implementations accept attachment data via {@link #enqueue}, process them asynchronously, and
 * support graceful shutdown with {@link #shutdown}.
 */
interface AttachmentUploader {
    /**
     * Enqueues an attachment for upload.
     *
     * <p>NOTE: if the upload queue is full, this method will block until space becomes available
     *
     * @param reference the attachment reference metadata
     * @param data the attachment data to upload
     * @return true if the attachment was successfully enqueued for upload. False if the uploader
     *     declined to enqueue the message
     */
    boolean enqueue(@Nonnull AttachmentReference reference, @Nonnull byte[] data);

    /** runs force flush with a default timeout */
    default void forceFlush() {
        forceFlush(Duration.ofSeconds(30));
    }

    /**
     * Waits for all currently enqueued uploads to complete with a timeout.
     *
     * <p><b>Concurrency note:</b> Items enqueued concurrently with or after this call are not
     * guaranteed to be included. This is safe because callers that need ordering should enqueue
     * first, then flush.
     *
     * @param timeout the maximum time to wait
     * @return true if all uploads completed, false if timed out
     */
    boolean forceFlush(@Nonnull Duration timeout);

    /** runs shutdown with a default timeout */
    default void shutdown() {
        // dropping s3 uploads is a bad user experience so shut down with a gracious timeout
        shutdown(Duration.ofSeconds(120));
    }

    /**
     * Shuts down the uploader with a custom timeout.
     *
     * @param timeout the maximum time to wait for pending uploads
     */
    void shutdown(@Nonnull Duration timeout);

    boolean isShutdown();

    /**
     * Background uploader for Braintrust attachments that uploads to S3 via signed URLs.
     *
     * <p>Uploads are enqueued and processed by a single-threaded worker that:
     *
     * <ol>
     *   <li>Requests a signed upload URL from the Braintrust API
     *   <li>Uploads the data to the signed URL
     *   <li>Reports the upload status (done/error) to the Braintrust API
     * </ol>
     *
     * <p>The uploader starts lazily on first enqueue and can be shut down gracefully.
     */
    @Slf4j
    class S3AttachmentUploader implements AttachmentUploader {
        private static final int QUEUE_SIZE = 1024;

        /** Default per-request timeout for HTTP calls. */
        private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

        /** Default maximum number of retry attempts for transient failures. */
        private static final int DEFAULT_MAX_RETRIES = 8;

        /** Default initial backoff delay between retries. Doubles on each subsequent attempt. */
        private static final Duration DEFAULT_INITIAL_RETRY_DELAY = Duration.ofMillis(500);

        private final BraintrustOpenApiClient apiClient;
        private final Duration requestTimeout;
        private final int maxRetries;
        private final Duration initialRetryDelay;

        private final LinkedBlockingQueue<UploadJob> queue;
        private final AtomicReference<ExecutorService> worker = new AtomicReference<>();
        private final AtomicReference<String> orgId = new AtomicReference<>();

        // non thread safe fields must be checked and read under the lock
        private final Object lock = new Object();
        private boolean rejectNewJobs = false;
        private boolean workerDone = false;
        private CountDownLatch currentBatch = new CountDownLatch(1);

        /**
         * Creates a new attachment uploader with default retry settings.
         *
         * @param apiClient the Braintrust API client (provides auth, base URL, and HTTP transport)
         */
        S3AttachmentUploader(@Nonnull BraintrustOpenApiClient apiClient) {
            this(
                    apiClient,
                    DEFAULT_REQUEST_TIMEOUT,
                    DEFAULT_MAX_RETRIES,
                    DEFAULT_INITIAL_RETRY_DELAY);
        }

        /**
         * Creates a new attachment uploader with custom retry settings.
         *
         * @param apiClient the Braintrust API client (provides auth, base URL, and HTTP transport)
         * @param requestTimeout the per-request timeout for HTTP calls
         * @param maxRetries the maximum number of retry attempts for transient failures
         * @param initialRetryDelay the initial backoff delay between retries (doubles on each
         *     attempt)
         */
        S3AttachmentUploader(
                @Nonnull BraintrustOpenApiClient apiClient,
                @Nonnull Duration requestTimeout,
                int maxRetries,
                @Nonnull Duration initialRetryDelay) {
            if (requestTimeout.toMillis() < 0) {
                throw new IllegalArgumentException("requestTimeout must be >= 0");
            }
            if (maxRetries <= 0) {
                throw new IllegalArgumentException("maxRetries must be > 0");
            }
            if (initialRetryDelay.toMillis() < 0) {
                throw new IllegalArgumentException("initialRetryDelay must be >= 0");
            }
            this.apiClient = apiClient;
            this.requestTimeout = requestTimeout;
            this.maxRetries = maxRetries;
            this.initialRetryDelay = initialRetryDelay;
            this.queue = new LinkedBlockingQueue<>(QUEUE_SIZE);
            BraintrustShutdownHook.addShutdownHook(
                    BraintrustShutdownHook.ShutdownOrder.ATTACHMENT_UPLOADER, this::shutdown);
        }

        @Override
        public boolean enqueue(@Nonnull AttachmentReference reference, @Nonnull byte[] data) {
            if (checkRejectNewJobsThreadSafe()) {
                return false;
            }
            try {
                ensureWorkerStarted();
                UploadJob job = new UploadJob(reference, data);
                return queue.offer(job, 0, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.error("failed to enqueue attachment", e);
                shutdown();
                return false;
            }
        }

        @Override
        @SneakyThrows
        public boolean forceFlush(@Nonnull Duration timeout) {
            return awaitCurrentBatch(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        @SneakyThrows
        public void shutdown(@Nonnull Duration timeout) {
            synchronized (lock) {
                rejectNewJobs = true;
                if (workerDone) {
                    return;
                }
            }
            ExecutorService executor = worker.getAndSet(null);
            if (executor == null) {
                return;
            }
            executor.shutdown();
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("failed to gracefully shut down s3 upload worker");
                executor.shutdownNow();
            }
        }

        @Override
        public boolean isShutdown() {
            return checkRejectNewJobsThreadSafe();
        }

        // ── Worker lifecycle ──────────────────────────────────────────────

        /**
         * start worker thread or do nothing if already started
         *
         * <p>calling this method does not require the lock
         */
        private void ensureWorkerStarted() {
            if (worker.get() == null) {
                var newWorker =
                        Executors.newSingleThreadExecutor(
                                r -> {
                                    Thread t = new Thread(r, "braintrust-attachment-uploader");
                                    t.setDaemon(true);
                                    return t;
                                });
                if (worker.compareAndSet(null, newWorker)) {
                    // NOTE: if shutdown is called concurrently job submission may throw an
                    // exception. This is fine.
                    newWorker.submit(this::workerLoop);
                } else {
                    // tried to start the worker concurrently. This is fine, we'll just shut down
                    // and dereference the redundant worker
                    newWorker.shutdown();
                }
            }
        }

        private void workerLoop() {
            log.debug("Attachment uploader worker started");
            while ((!checkRejectNewJobsThreadSafe()) || queue.peek() != null) {
                UploadJob job = null;
                try {
                    job = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (job == null) {
                        finishCurrentBatch();
                    } else {
                        upload(job);
                    }
                } catch (InterruptedException e) {
                    // worker thread shutdownNow was invoked
                    if (!queue.isEmpty()) {
                        log.warn(
                                "s3 uploader force shutdown was reached. Dropping {} uploads",
                                queue.size(),
                                e);
                    }
                    break;
                } catch (Exception e) {
                    // this only user of this util is our span processor so we'll just fall back to
                    // sending attachments in span data if an error occurs
                    synchronized (lock) {
                        rejectNewJobs = true;
                    }
                    if (job == null) {
                        log.warn("Failed to upload attachment", e);
                    } else {
                        log.warn("Failed to upload attachment key={}", job.reference().key(), e);
                        reportStatus(job.reference().key(), "error", e.getMessage());
                    }
                    // NOTE: we'll continue the loop attempting uploads of the remaining jobs until
                    // the queue is drained
                }
            }
            synchronized (lock) {
                workerDone = true;
                finishCurrentBatch();
                log.debug("Attachment uploader worker stopped");
            }
        }

        private boolean checkRejectNewJobsThreadSafe() {
            synchronized (lock) {
                return rejectNewJobs;
            }
        }

        // ── Upload orchestration ──────────────────────────────────────────

        private void upload(@Nonnull UploadJob job) throws IOException, InterruptedException {
            UploadUrlResponse urlResponse =
                    requestUploadUrl(
                            getOrgId(),
                            job.reference().key(),
                            job.reference().filename(),
                            job.reference().contentType());

            uploadToSignedUrl(
                    urlResponse.signedUrl(),
                    urlResponse.headers(),
                    job.reference().contentType(),
                    job.data());

            reportStatus(job.reference().key(), "done", null);
        }

        private String getOrgId() {
            if (orgId.get() != null) {
                return orgId.get();
            }
            return orgId.updateAndGet(curr -> curr != null ? curr : resolveOrgId());
        }

        private String resolveOrgId() {
            try {
                var loginResponse = apiClient.login();
                if (loginResponse.orgInfo() != null && !loginResponse.orgInfo().isEmpty()) {
                    return loginResponse.orgInfo().get(0).id();
                } else {
                    throw new IllegalStateException("No org info returned from login");
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to resolve org ID for attachment upload", e);
            }
        }

        private void reportStatus(
                @Nonnull String key, @Nonnull String status, @Nullable String errorMessage) {
            try {
                var statusMap = new java.util.HashMap<String, Object>();
                statusMap.put("upload_status", status);
                if (errorMessage != null) {
                    statusMap.put("error_message", errorMessage);
                }
                updateUploadStatus(getOrgId(), key, statusMap);
            } catch (Exception e) {
                log.warn("Failed to report attachment status key={} status={}", key, status, e);
            }
        }

        // ── S3 HTTP operations ────────────────────────────────────────────

        /**
         * Requests a signed upload URL from the Braintrust API.
         *
         * @param orgId the organization ID
         * @param key the attachment key
         * @param filename the filename for the attachment
         * @param contentType the MIME type of the attachment
         * @return the signed URL response with upload URL and required headers
         * @throws IOException if the request fails
         * @throws InterruptedException if the request is interrupted
         */
        UploadUrlResponse requestUploadUrl(
                @Nonnull String orgId,
                @Nonnull String key,
                @Nonnull String filename,
                @Nonnull String contentType)
                throws IOException, InterruptedException {

            var requestBody =
                    BraintrustJsonMapper.get()
                            .writeValueAsString(
                                    new UploadUrlRequest(key, filename, contentType, orgId));

            var requestBuilder =
                    HttpRequest.newBuilder()
                            .uri(toUri(apiClient.getBaseUri() + "/attachment"))
                            .timeout(requestTimeout)
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody));

            if (apiClient.getRequestInterceptor() != null) {
                apiClient.getRequestInterceptor().accept(requestBuilder);
            }

            HttpResponse<String> response =
                    sendWithRetry(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (!isSuccessStatus(response.statusCode())) {
                throw new IOException(
                        "Failed to request upload URL: HTTP "
                                + response.statusCode()
                                + " - "
                                + response.body());
            }

            UploadUrlResponse result =
                    BraintrustJsonMapper.get().readValue(response.body(), UploadUrlResponse.class);

            if (result.signedUrl() == null || result.signedUrl().isEmpty()) {
                throw new IOException("Signed URL response missing signedUrl");
            }

            return result;
        }

        /**
         * Uploads data to a signed URL with the specified headers.
         *
         * <p>Automatically detects Azure Blob Storage URLs and adds the required {@code
         * x-ms-blob-type: BlockBlob} header.
         *
         * @param signedUrl the signed upload URL
         * @param headers additional headers to include in the upload request
         * @param contentType the MIME type of the data being uploaded
         * @param data the data to upload
         * @throws IOException if the upload fails
         * @throws InterruptedException if the upload is interrupted
         */
        void uploadToSignedUrl(
                @Nonnull String signedUrl,
                @Nonnull Map<String, String> headers,
                @Nonnull String contentType,
                @Nonnull byte[] data)
                throws IOException, InterruptedException {

            var requestBuilder =
                    HttpRequest.newBuilder()
                            .uri(toUri(signedUrl))
                            .timeout(requestTimeout)
                            .header("Content-Type", contentType)
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(data));

            for (var entry : headers.entrySet()) {
                requestBuilder.header(entry.getKey(), entry.getValue());
            }

            addAzureBlobHeaders(signedUrl, requestBuilder);

            HttpResponse<String> response =
                    sendWithRetry(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (!isSuccessStatus(response.statusCode())) {
                throw new IOException(
                        "Failed to upload to object store: HTTP "
                                + response.statusCode()
                                + " - "
                                + response.body());
            }
        }

        /**
         * Updates the upload status for an attachment.
         *
         * @param orgId the organization ID
         * @param key the attachment key
         * @param status the status map (e.g., {"upload_status": "done"} or {"upload_status":
         *     "error", "error_message": "..."})
         * @throws IOException if the request fails
         * @throws InterruptedException if the request is interrupted
         */
        void updateUploadStatus(
                @Nonnull String orgId, @Nonnull String key, @Nonnull Map<String, Object> status)
                throws IOException, InterruptedException {

            var requestBody =
                    BraintrustJsonMapper.get()
                            .writeValueAsString(new StatusRequest(key, orgId, status));

            var requestBuilder =
                    HttpRequest.newBuilder()
                            .uri(toUri(apiClient.getBaseUri() + "/attachment/status"))
                            .timeout(requestTimeout)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(requestBody));

            if (apiClient.getRequestInterceptor() != null) {
                apiClient.getRequestInterceptor().accept(requestBuilder);
            }

            HttpResponse<String> response =
                    sendWithRetry(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (!isSuccessStatus(response.statusCode())) {
                throw new IOException(
                        "Failed to update upload status: HTTP "
                                + response.statusCode()
                                + " - "
                                + response.body());
            }
        }

        // ── HTTP helpers ──────────────────────────────────────────────────

        /** Returns {@code true} if the HTTP status code indicates success (2xx). */
        private static boolean isSuccessStatus(int statusCode) {
            return statusCode >= 200 && statusCode < 300;
        }

        /**
         * Parses a URI string.
         *
         * @param uriString the string to parse
         * @return the parsed URI
         */
        @SneakyThrows
        private static URI toUri(@Nonnull String uriString) {
            return new URI(uriString);
        }

        /**
         * Sends an HTTP request with retry logic for transient failures.
         *
         * <p>Retries on 5xx server errors and {@link IOException} (network errors) up to {@link
         * #maxRetries} times with exponential backoff starting at {@link #initialRetryDelay}.
         *
         * @param request the request to send
         * @param bodyHandler the response body handler
         * @param <T> the response body type
         * @return the HTTP response
         * @throws IOException if all retries are exhausted
         * @throws InterruptedException if the thread is interrupted during retry backoff
         */
        private <T> HttpResponse<T> sendWithRetry(
                @Nonnull HttpRequest request, @Nonnull HttpResponse.BodyHandler<T> bodyHandler)
                throws IOException, InterruptedException {

            IOException lastException = null;
            long backoffMs = initialRetryDelay.toMillis();

            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                if (attempt > 0) {
                    log.debug(
                            "Retrying request {} (attempt {}/{})",
                            request.uri(),
                            attempt,
                            maxRetries);
                    Thread.sleep(backoffMs);
                    backoffMs *= 2;
                }

                HttpResponse<T> response;
                try {
                    response = apiClient.getHttpClient().send(request, bodyHandler);
                } catch (IOException e) {
                    lastException = e;
                    log.debug("Request to {} failed with IOException", request.uri(), e);
                    continue;
                }

                // Don't retry client errors (4xx) or successes
                if (response.statusCode() < 500) {
                    return response;
                }

                // Server error (5xx) — retry
                lastException = new IOException("Server error: HTTP " + response.statusCode());
                log.debug(
                        "Request to {} returned status {}, will retry",
                        request.uri(),
                        response.statusCode());
            }

            throw new IOException(
                    "Request to " + request.uri() + " failed after " + maxRetries + " retries",
                    lastException);
        }

        /** Adds Azure Blob Storage specific headers when the signed URL points to Azure. */
        private static void addAzureBlobHeaders(
                @Nonnull String signedUrl, HttpRequest.Builder requestBuilder) {
            try {
                var uri = new URI(signedUrl);
                String host = uri.getHost();
                if (host != null && host.endsWith(".blob.core.windows.net")) {
                    requestBuilder.header("x-ms-blob-type", "BlockBlob");
                }
            } catch (URISyntaxException e) {
                log.warn("Failed to parse signed URL for Azure detection: {}", signedUrl, e);
            }
        }

        // ── Batch coordination ────────────────────────────────────────────

        private void finishCurrentBatch() {
            synchronized (lock) {
                currentBatch.countDown();
                currentBatch = new CountDownLatch(1);
            }
        }

        private boolean awaitCurrentBatch(long timeout, TimeUnit timeUnit)
                throws InterruptedException {
            CountDownLatch latch;
            synchronized (lock) {
                latch = currentBatch;
            }
            return latch.await(timeout, timeUnit);
        }

        // ── DTOs ──────────────────────────────────────────────────────────

        private record UploadJob(AttachmentReference reference, byte[] data) {}

        /** Response from requesting a signed upload URL. */
        record UploadUrlResponse(
                @JsonProperty("signedUrl") @Nonnull String signedUrl,
                @JsonProperty("headers") @Nonnull Map<String, String> headers) {

            /** Compact constructor that enforces non-null headers with an empty map default. */
            UploadUrlResponse {
                if (headers == null) {
                    headers = Map.of();
                }
            }
        }

        private record UploadUrlRequest(
                @Nonnull String key,
                @Nonnull String filename,
                @JsonProperty("content_type") @Nonnull String contentType,
                @JsonProperty("org_id") @Nonnull String orgId) {}

        private record StatusRequest(
                @Nonnull String key,
                @JsonProperty("org_id") @Nonnull String orgId,
                @Nonnull Map<String, Object> status) {}
    }
}
