package dev.braintrust.eval;

import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

/** Deprecated. Use {@link DatasetCase} instead */
@Deprecated
public record EvalCase<INPUT, OUTPUT>(
        INPUT input,
        OUTPUT expected,
        @Nonnull List<String> tags,
        @Nonnull Map<String, Object> metadata) {
    /** Deprecated. Use {@link DatasetCase} instead */
    @Deprecated
    public EvalCase {
        if (!metadata.isEmpty()) {
            throw new RuntimeException("TODO: metadata support not yet implemented");
        }
        if (!tags.isEmpty()) {
            throw new RuntimeException("TODO: tags support not yet implemented");
        }
    }

    /** Deprecated. Use {@link DatasetCase} instead */
    @Deprecated
    public static <INPUT, OUTPUT> EvalCase<INPUT, OUTPUT> of(INPUT input, OUTPUT expected) {
        return of(input, expected, List.of(), Map.of());
    }

    /** Deprecated. Use {@link DatasetCase} instead */
    @Deprecated
    public static <INPUT, OUTPUT> EvalCase<INPUT, OUTPUT> of(
            INPUT input,
            OUTPUT expected,
            @Nonnull List<String> tags,
            @Nonnull Map<String, Object> metadata) {
        return new EvalCase<>(input, expected, tags, metadata);
    }

    static <INPUT, OUTPUT> EvalCase<INPUT, OUTPUT> from(DatasetCase<INPUT, OUTPUT> datasetCase) {
        return new EvalCase<>(
                datasetCase.input(),
                datasetCase.expected(),
                datasetCase.tags(),
                datasetCase.metadata());
    }
}
