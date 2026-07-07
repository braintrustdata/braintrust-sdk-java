package dev.braintrust.eval;

import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.api.BraintrustOpenApiClient;
import dev.braintrust.openapi.api.DatasetsApi;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Datasets define the cases for evals. This interface provides a means of iterating through all
 * cases of a particular dataset.
 *
 * <p>The most common implementations are in-memory datasets, and datasets fetched from the
 * Braintrust API.
 */
public interface Dataset<INPUT, OUTPUT> {
    Cursor<DatasetCase<INPUT, OUTPUT>> openCursor();

    String id();

    /** Dataset version. Empty means the dataset will fetch latest upon every cursor open */
    Optional<String> version();

    /** Convenience method to safely iterate all items in a dataset. */
    default void forEach(Consumer<DatasetCase<INPUT, OUTPUT>> consumer) {
        try (var cursor = openCursor()) {
            cursor.forEach(consumer);
        }
    }

    @NotThreadSafe
    interface Cursor<CASE> extends AutoCloseable {
        /**
         * Fetch the next case. Returns empty if there are no more cases to fetch.
         *
         * <p>Implementations may make external requests to fetch data.
         *
         * <p>If this method is invoked after {@link #close()} an IllegalStateException will be
         * thrown
         */
        Optional<CASE> next();

        /** close all cursor resources */
        void close();

        /** version of the dataset this cursor was opened against */
        Optional<String> version();

        default void forEach(Consumer<CASE> caseConsumer) {
            Optional<CASE> c = next();
            while (c.isPresent()) {
                caseConsumer.accept(c.get());
                c = next();
            }
        }
    }

    /** Create an in-memory Dataset containing the provided cases. */
    @SafeVarargs
    static <INPUT, OUTPUT> Dataset<INPUT, OUTPUT> of(DatasetCase<INPUT, OUTPUT>... cases) {
        return new DatasetInMemoryImpl<>(List.of(cases));
    }

    @Deprecated
    static <INPUT, OUTPUT> Dataset<INPUT, OUTPUT> fetchFromBraintrust(
            BraintrustApiClient apiClient,
            String projectName,
            String datasetName,
            @Nullable String datasetVersion) {
        return fetchFromBraintrust(
                apiClient.openApiClient(), projectName, datasetName, datasetVersion);
    }

    /**
     * Fetch a dataset from Braintrust.
     *
     * @deprecated the {@code INPUT} and {@code OUTPUT} type parameters are not applied at runtime:
     *     case values are returned as raw JSON-decoded objects (e.g. {@code LinkedHashMap}). Use
     *     {@link #fetchFromBraintrust(BraintrustOpenApiClient, String, String, String, Class,
     *     Class)} instead.
     */
    @Deprecated
    static <INPUT, OUTPUT> Dataset<INPUT, OUTPUT> fetchFromBraintrust(
            BraintrustOpenApiClient apiClient,
            String projectName,
            String datasetName,
            @Nullable String datasetVersion) {
        var datasetId = resolveDatasetId(apiClient, projectName, datasetName);
        return new DatasetBrainstoreImpl<>(apiClient, datasetId, datasetVersion);
    }

    /**
     * Fetch a dataset from Braintrust, deserializing each case's {@code input} and {@code expected}
     * values into the given types using the SDK's shared Jackson mapper (see {@link
     * dev.braintrust.json.BraintrustJsonMapper}).
     *
     * @param apiClient the Braintrust API client
     * @param projectName the project containing the dataset
     * @param datasetName the name of the dataset within the project
     * @param datasetVersion the dataset version to pin, or null to fetch the latest version upon
     *     every cursor open
     * @param inputClass the type to deserialize each case's {@code input} into
     * @param outputClass the type to deserialize each case's {@code expected} into
     */
    static <INPUT, OUTPUT> Dataset<INPUT, OUTPUT> fetchFromBraintrust(
            BraintrustOpenApiClient apiClient,
            String projectName,
            String datasetName,
            @Nullable String datasetVersion,
            Class<INPUT> inputClass,
            Class<OUTPUT> outputClass) {
        var datasetId = resolveDatasetId(apiClient, projectName, datasetName);
        return new DatasetBrainstoreImpl<>(
                apiClient, datasetId, datasetVersion, inputClass, outputClass);
    }

    /**
     * Fetch a dataset from Braintrust, converting each case's {@code input} and {@code expected}
     * values with the supplied converter functions. Converters receive the raw JSON-decoded value
     * (typically a {@code Map<String, Object>}, {@code List}, {@code String}, {@code Number},
     * {@code Boolean}, or null) and must tolerate null.
     *
     * @param apiClient the Braintrust API client
     * @param projectName the project containing the dataset
     * @param datasetName the name of the dataset within the project
     * @param datasetVersion the dataset version to pin, or null to fetch the latest version upon
     *     every cursor open
     * @param inputConverter converts each case's raw {@code input} value
     * @param outputConverter converts each case's raw {@code expected} value
     */
    static <INPUT, OUTPUT> Dataset<INPUT, OUTPUT> fetchFromBraintrust(
            BraintrustOpenApiClient apiClient,
            String projectName,
            String datasetName,
            @Nullable String datasetVersion,
            Function<Object, INPUT> inputConverter,
            Function<Object, OUTPUT> outputConverter) {
        var datasetId = resolveDatasetId(apiClient, projectName, datasetName);
        return new DatasetBrainstoreImpl<>(
                apiClient, datasetId, datasetVersion, inputConverter, outputConverter);
    }

    private static String resolveDatasetId(
            BraintrustOpenApiClient apiClient, String projectName, String datasetName) {
        var datasetsApi = new DatasetsApi(apiClient);
        var objects =
                datasetsApi
                        .getDataset(null, null, null, null, datasetName, projectName, null, null)
                        .getObjects();

        if (objects.isEmpty()) {
            throw new RuntimeException(
                    "Dataset not found: project=" + projectName + ", dataset=" + datasetName);
        }

        if (objects.size() > 1) {
            throw new RuntimeException(
                    "Multiple datasets found for project="
                            + projectName
                            + ", dataset="
                            + datasetName
                            + ". Found "
                            + objects.size()
                            + " datasets");
        }

        return objects.get(0).getId().toString();
    }
}
