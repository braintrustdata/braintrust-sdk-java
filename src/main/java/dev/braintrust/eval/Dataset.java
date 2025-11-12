package dev.braintrust.eval;

import java.util.List;
import java.util.Optional;
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

    String version();

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
    }

    /** Create an in-memory Dataset containing the provided cases. */
    @SafeVarargs
    static <INPUT, OUTPUT> Dataset<INPUT, OUTPUT> of(DatasetCase<INPUT, OUTPUT>... cases) {
        return new DatasetInMemoryImpl<>(List.of(cases));
    }
}
