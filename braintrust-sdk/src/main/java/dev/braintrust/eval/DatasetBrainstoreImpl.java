package dev.braintrust.eval;

import dev.braintrust.api.BraintrustApiClient;
import java.util.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jspecify.annotations.NonNull;

/** A dataset loaded externally from Braintrust using paginated API fetches */
public class DatasetBrainstoreImpl<INPUT, OUTPUT> implements Dataset<INPUT, OUTPUT> {
    private final BraintrustApiClient apiClient;
    private final String datasetId;
    private final @Nullable String pinnedVersion;
    private final int batchSize;

    public DatasetBrainstoreImpl(
            BraintrustApiClient apiClient, String datasetId, @Nullable String datasetVersion) {
        this(apiClient, datasetId, datasetVersion, 512);
    }

    DatasetBrainstoreImpl(
            BraintrustApiClient apiClient,
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
        return new BrainstoreCursor(null == pinnedVersion ? fetchMaxVersion() : pinnedVersion);
    }

    private String fetchMaxVersion() {
        var response =
                apiClient.btqlQuery(
                        "SELECT max(_xact_id) as version FROM dataset('%s')".formatted(datasetId));
        if (response.data().isEmpty()) {
            throw new RuntimeException(
                    "Failed to fetch max version for dataset: " + datasetId + " (empty response)");
        }
        var version = response.data().get(0).get("version");
        if (version == null) {
            throw new RuntimeException(
                    "Failed to fetch max version for dataset: "
                            + datasetId
                            + " (null version — dataset may be empty)");
        }
        return String.valueOf(version);
    }

    private class BrainstoreCursor implements Cursor<DatasetCase<INPUT, OUTPUT>> {
        private List<Map<String, Object>> currentBatch;
        private int currentIndex;
        private @Nullable String cursor;
        private boolean exhausted;
        private boolean closed;
        private final @Nonnull String cursorVersion;

        BrainstoreCursor(@NonNull String cursorVersion) {
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

            // Fetch next batch if we've consumed the current one
            if (currentIndex >= currentBatch.size() && !exhausted) {
                fetchNextBatch();
            }

            // Return empty if no more data
            if (currentIndex >= currentBatch.size()) {
                return Optional.empty();
            }

            // Parse the event into a DatasetCase
            Map<String, Object> event = currentBatch.get(currentIndex++);

            INPUT input = (INPUT) event.get("input");
            OUTPUT expected = (OUTPUT) event.get("expected");

            Map<String, Object> metadata = (Map<String, Object>) event.get("metadata");
            if (metadata == null) {
                metadata = Map.of();
            }

            List<String> tags = (List<String>) event.get("tags");
            if (tags == null) {
                tags = List.of();
            }

            DatasetCase<INPUT, OUTPUT> datasetCase =
                    new DatasetCase<>(
                            input,
                            expected,
                            tags,
                            metadata,
                            Optional.of(
                                    new dev.braintrust.Origin(
                                            "dataset",
                                            Objects.requireNonNull(
                                                    (String) event.get("dataset_id")),
                                            Objects.requireNonNull((String) event.get("id")),
                                            Objects.requireNonNull((String) event.get("_xact_id")),
                                            Objects.requireNonNull(
                                                    (String) event.get("created")))));

            return Optional.of(datasetCase);
        }

        private void fetchNextBatch() {
            var request =
                    new BraintrustApiClient.DatasetFetchRequest(batchSize, cursor, cursorVersion);
            var response = apiClient.fetchDatasetEvents(datasetId, request);

            currentBatch = new ArrayList<>(response.events());
            currentIndex = 0;
            cursor = response.cursor();

            // Mark as exhausted if no cursor or no events returned
            if (cursor == null || cursor.isEmpty() || response.events().isEmpty()) {
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
}
