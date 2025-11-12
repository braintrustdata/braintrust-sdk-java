package dev.braintrust.eval;

import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

/** A single row in a dataset. */
public record DatasetCase<INPUT, OUTPUT>(
        INPUT input,
        OUTPUT expected,
        @Nonnull List<String> tags,
        @Nonnull Map<String, Object> metadata) {
    public DatasetCase {
        if (!metadata.isEmpty()) {
            throw new RuntimeException("TODO: metadata support not yet implemented");
        }
        if (!tags.isEmpty()) {
            throw new RuntimeException("TODO: tags support not yet implemented");
        }
    }

    public static <INPUT, OUTPUT> DatasetCase<INPUT, OUTPUT> of(INPUT input, OUTPUT expected) {
        return new DatasetCase<>(input, expected, List.of(), Map.of());
    }
}
