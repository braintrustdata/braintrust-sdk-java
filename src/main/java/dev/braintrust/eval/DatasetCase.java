package dev.braintrust.eval;

import dev.braintrust.Origin;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;

/** A single row in a dataset. */
public record DatasetCase<INPUT, OUTPUT>(
        INPUT input,
        OUTPUT expected,
        @Nonnull List<String> tags,
        @Nonnull Map<String, Object> metadata,
        /** origin information. empty for in-memory cases */
        Optional<Origin> origin) {

    public static <INPUT, OUTPUT> DatasetCase<INPUT, OUTPUT> of(INPUT input, OUTPUT expected) {
        return of(input, expected, List.of(), Map.of());
    }

    public static <INPUT, OUTPUT> DatasetCase<INPUT, OUTPUT> of(
            INPUT input,
            OUTPUT expected,
            @Nonnull List<String> tags,
            @Nonnull Map<String, Object> metadata) {
        return new DatasetCase<>(input, expected, tags, metadata, Optional.empty());
    }
}
