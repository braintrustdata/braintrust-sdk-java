package dev.braintrust.eval;

import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.api.BraintrustOpenApiClient;
import dev.braintrust.json.BraintrustJsonMapper;
import dev.braintrust.openapi.api.DatasetsApi;
import dev.braintrust.openapi.model.FetchEventsRequest;
import java.util.*;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** A dataset loaded externally from Braintrust using paginated API fetches */
public class DatasetBrainstoreImpl<INPUT, OUTPUT> implements Dataset<INPUT, OUTPUT> {
    private final BraintrustOpenApiClient apiClient;
    private final String datasetId;
    private final @Nullable String pinnedVersion;
    private final int batchSize;
    private final Function<Object, INPUT> inputConverter;
    private final Function<Object, OUTPUT> outputConverter;

    @Deprecated
    public DatasetBrainstoreImpl(
            BraintrustApiClient apiClient, String datasetId, @Nullable String datasetVersion) {
        this(apiClient.openApiClient(), datasetId, datasetVersion);
    }

    /**
     * @deprecated the {@code INPUT} and {@code OUTPUT} type parameters are not applied at runtime:
     *     case values are returned as raw JSON-decoded objects. Use {@link
     *     #DatasetBrainstoreImpl(BraintrustOpenApiClient, String, String, Class, Class)} instead.
     */
    @Deprecated
    public DatasetBrainstoreImpl(
            BraintrustOpenApiClient apiClient, String datasetId, @Nullable String datasetVersion) {
        this(apiClient, datasetId, datasetVersion, 512, uncheckedCast(), uncheckedCast());
    }

    /**
     * Create a dataset whose case {@code input} and {@code expected} values are deserialized into
     * the given types using the SDK's shared Jackson mapper (see {@link BraintrustJsonMapper}).
     *
     * @param apiClient the Braintrust API client
     * @param datasetId the Braintrust dataset id
     * @param datasetVersion the dataset version to pin, or null to fetch the latest version upon
     *     every cursor open
     * @param inputClass the type to deserialize each case's {@code input} into
     * @param outputClass the type to deserialize each case's {@code expected} into
     */
    public DatasetBrainstoreImpl(
            BraintrustOpenApiClient apiClient,
            String datasetId,
            @Nullable String datasetVersion,
            Class<INPUT> inputClass,
            Class<OUTPUT> outputClass) {
        this(
                apiClient,
                datasetId,
                datasetVersion,
                512,
                BraintrustJsonMapper.converter(inputClass),
                BraintrustJsonMapper.converter(outputClass));
    }

    /**
     * Create a dataset whose case {@code input} and {@code expected} values are converted with the
     * supplied functions. Converters receive the raw JSON-decoded value (typically a {@code
     * Map<String, Object>}, {@code List}, {@code String}, {@code Number}, {@code Boolean}, or
     * null).
     *
     * @param apiClient the Braintrust API client
     * @param datasetId the Braintrust dataset id
     * @param datasetVersion the dataset version to pin, or null to fetch the latest version upon
     *     every cursor open
     * @param inputConverter converts each case's raw {@code input} value; must tolerate null
     * @param outputConverter converts each case's raw {@code expected} value; must tolerate null
     */
    public DatasetBrainstoreImpl(
            BraintrustOpenApiClient apiClient,
            String datasetId,
            @Nullable String datasetVersion,
            Function<Object, INPUT> inputConverter,
            Function<Object, OUTPUT> outputConverter) {
        this(apiClient, datasetId, datasetVersion, 512, inputConverter, outputConverter);
    }

    DatasetBrainstoreImpl(
            BraintrustOpenApiClient apiClient,
            String datasetId,
            @Nullable String datasetVersion,
            int batchSize) {
        this(apiClient, datasetId, datasetVersion, batchSize, uncheckedCast(), uncheckedCast());
    }

    DatasetBrainstoreImpl(
            BraintrustOpenApiClient apiClient,
            String datasetId,
            @Nullable String datasetVersion,
            int batchSize,
            Function<Object, INPUT> inputConverter,
            Function<Object, OUTPUT> outputConverter) {
        this.apiClient = apiClient;
        this.datasetId = datasetId;
        this.batchSize = batchSize;
        this.pinnedVersion = datasetVersion;
        this.inputConverter = Objects.requireNonNull(inputConverter);
        this.outputConverter = Objects.requireNonNull(outputConverter);
    }

    /** Legacy behavior: pass the raw JSON-decoded value through via an unchecked cast. */
    @SuppressWarnings("unchecked")
    private static <T> Function<Object, T> uncheckedCast() {
        return raw -> (T) raw;
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

            INPUT input = inputConverter.apply(event.getInput());
            OUTPUT expected = outputConverter.apply(event.getExpected());

            var metadataObj = event.getMetadata();
            // InsertProjectLogsEventMetadata extends HashMap<String,Object>. Jackson stores
            // unknown fields in the HashMap base (not in additionalProperties) for Map subclasses,
            // so copy from both sources defensively.
            Map<String, Object> metadata;
            if (metadataObj != null) {
                metadata = new HashMap<>(metadataObj);
                if (metadataObj.getAdditionalProperties() != null) {
                    metadata.putAll(metadataObj.getAdditionalProperties());
                }
            } else {
                metadata = Map.of();
            }

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
