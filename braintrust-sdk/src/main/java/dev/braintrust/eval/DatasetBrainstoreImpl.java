package dev.braintrust.eval;

import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.api.BraintrustOpenApiClient;
import dev.braintrust.openapi.api.DatasetsApi;
import dev.braintrust.openapi.model.FetchEventsRequest;
import java.util.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** A dataset loaded externally from Braintrust using paginated API fetches */
public class DatasetBrainstoreImpl<INPUT, OUTPUT> implements Dataset<INPUT, OUTPUT> {
    private final BraintrustOpenApiClient apiClient;
    private final String datasetId;
    private final @Nullable String pinnedVersion;
    private final int batchSize;

    @Deprecated
    public DatasetBrainstoreImpl(
            BraintrustApiClient apiClient, String datasetId, @Nullable String datasetVersion) {
        this(apiClient.openApiClient(), datasetId, datasetVersion, 512);
    }

    public DatasetBrainstoreImpl(
            BraintrustOpenApiClient apiClient, String datasetId, @Nullable String datasetVersion) {
        this(apiClient, datasetId, datasetVersion, 512);
    }

    DatasetBrainstoreImpl(
            BraintrustOpenApiClient apiClient,
            String datasetId,
            @Nullable String datasetVersion,
            int batchSize) {
        this.apiClient = apiClient;
        this.datasetId = datasetId;
        this.batchSize = batchSize;
        this.pinnedVersion = datasetVersion;
    }

    @Override
    public String id() {
        return datasetId;
    }

    @Override
    public Optional<String> version() {
        return Optional.ofNullable(pinnedVersion);
    }

    @Override
    public Cursor<DatasetCase<INPUT, OUTPUT>> openCursor() {
        if (null != pinnedVersion) {
            return new BrainstoreCursor(pinnedVersion);
        }
        var maxVersion = fetchMaxVersion();
        if (null == maxVersion) {
            return EMPTY_CURSOR;
        } else {
            return new BrainstoreCursor(maxVersion);
        }
    }

    private @Nullable String fetchMaxVersion() {
        var response =
                apiClient.btqlQuery(
                        "SELECT max(_xact_id) as version, count(*) as count FROM dataset('%s')"
                                .formatted(datasetId));
        if (response.data().isEmpty()) {
            throw new RuntimeException(
                    "Failed to fetch max version for dataset: " + datasetId + " (empty response)");
        }
        if ("0".equals(response.data().get(0).get("count").toString())) {
            // empty dataset
            return null;
        }
        var version = response.data().get(0).get("version");
        if (version == null) {
            throw new RuntimeException("failed to fetch max version for dataset: " + datasetId);
        }
        return String.valueOf(version);
    }

    private class BrainstoreCursor implements Cursor<DatasetCase<INPUT, OUTPUT>> {
        private List<dev.braintrust.openapi.model.DatasetEvent> currentBatch;
        private int currentIndex;
        private @Nullable String cursor;
        private boolean exhausted;
        private boolean closed;
        private final @Nonnull String cursorVersion;

        BrainstoreCursor(@Nonnull String cursorVersion) {
            this.currentBatch = new ArrayList<>();
            this.currentIndex = 0;
            this.cursor = null;
            this.exhausted = false;
            this.closed = false;
            this.cursorVersion = cursorVersion;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Optional<DatasetCase<INPUT, OUTPUT>> next() {
            if (closed) {
                throw new IllegalStateException("Cursor is closed");
            }

            if (currentIndex >= currentBatch.size() && !exhausted) {
                fetchNextBatch();
            }

            if (currentIndex >= currentBatch.size()) {
                return Optional.empty();
            }

            var event = currentBatch.get(currentIndex++);

            INPUT input = (INPUT) event.getInput();
            OUTPUT expected = (OUTPUT) event.getExpected();

            var metadataObj = event.getMetadata();
            Map<String, Object> metadata =
                    metadataObj != null ? metadataObj.getAdditionalProperties() : Map.of();
            if (metadata == null) metadata = Map.of();

            List<String> tags = event.getTags() != null ? event.getTags() : List.of();

            var datasetCase =
                    new DatasetCase<>(
                            input,
                            expected,
                            tags,
                            metadata,
                            Optional.of(
                                    new dev.braintrust.Origin(
                                            "dataset",
                                            Objects.requireNonNull(
                                                    event.getDatasetId() != null
                                                            ? event.getDatasetId().toString()
                                                            : null),
                                            Objects.requireNonNull(event.getId()),
                                            Objects.requireNonNull(event.getXactId()),
                                            Objects.requireNonNull(
                                                    event.getCreated() != null
                                                            ? event.getCreated().toString()
                                                            : null))));

            return Optional.of(datasetCase);
        }

        private void fetchNextBatch() {
            var request =
                    new FetchEventsRequest().limit(batchSize).cursor(cursor).version(cursorVersion);

            var response =
                    new DatasetsApi(apiClient)
                            .postDatasetIdFetch(UUID.fromString(datasetId), request);

            currentBatch = new ArrayList<>(response.getEvents());
            currentIndex = 0;
            cursor = response.getCursor();

            if (cursor == null || cursor.isEmpty() || response.getEvents().isEmpty()) {
                exhausted = true;
            }
        }

        @Override
        public void close() {
            closed = true;
            currentBatch.clear();
        }

        @Override
        public Optional<String> version() {
            return Optional.of(cursorVersion);
        }
    }

    private final Cursor<DatasetCase<INPUT, OUTPUT>> EMPTY_CURSOR =
            new Cursor<>() {
                @Override
                public Optional<DatasetCase<INPUT, OUTPUT>> next() {
                    return Optional.empty();
                }

                @Override
                public void close() {}

                @Override
                public Optional<String> version() {
                    return Optional.empty();
                }
            };
}
