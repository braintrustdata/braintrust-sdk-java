package dev.braintrust.eval;

import dev.braintrust.api.BraintrustApiClient;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
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

    static <INPUT, OUTPUT> Dataset<INPUT, OUTPUT> fetchFromBraintrust(
            BraintrustApiClient apiClient,
            String projectName,
            String datasetName,
            @Nullable String datasetVersion) {
        var datasets = apiClient.queryDatasets(projectName, datasetName);

        if (datasets.isEmpty()) {
            throw new RuntimeException(
                    "Dataset not found: project=" + projectName + ", dataset=" + datasetName);
        }

        if (datasets.size() > 1) {
            throw new RuntimeException(
                    "Multiple datasets found for project="
                            + projectName
                            + ", dataset="
                            + datasetName
                            + ". Found "
                            + datasets.size()
                            + " datasets");
        }

        var dataset = datasets.get(0);
        return new DatasetBrainstoreImpl<>(
                apiClient,
                dataset.id(),
                datasetVersion != null ? datasetVersion : dataset.updatedAt());
    }
}
