package dev.braintrust.eval;

import java.util.Map;
import javax.annotation.Nullable;

/**
 * A single structured classification produced by a {@link Classifier}.
 *
 * <p>Unlike a {@link Score} (numeric 0-1), a Classification carries a stable id, an optional
 * display label, and optional metadata. The {@code name} acts as the grouping key in the aggregated
 * result map; when {@code name} is {@code null} or blank, the owning classifier's resolved name is
 * used instead.
 *
 * @param name optional grouping key; defaults to the owning classifier's resolved name when null or
 *     blank
 * @param id stable identifier for the classification (required)
 * @param label optional display label
 * @param metadata optional arbitrary metadata
 */
public record Classification(
        @Nullable String name,
        String id,
        @Nullable String label,
        @Nullable Map<String, Object> metadata) {

    public static Classification of(String id) {
        return new Classification(null, id, null, null);
    }

    public static Classification of(String id, String label) {
        return new Classification(null, id, label, null);
    }

    public static Classification of(String name, String id, String label) {
        return new Classification(name, id, label, null);
    }
}
