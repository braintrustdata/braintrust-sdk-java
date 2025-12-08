package dev.braintrust.trace;

import dev.braintrust.BraintrustUtils;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * Used to identify the braintrust parent for spans and experiments. SDK users probably don't want
 * to use this and instead should use {@link BraintrustTracing} or {@link dev.braintrust.eval.Eval}
 */
@Slf4j
public final class BraintrustContext {
    private static final ContextKey<BraintrustContext> KEY = ContextKey.named("braintrust-context");

    // NOTE we're actually not using this right now, but leaving in for the future
    @Nullable private final String projectId;
    @Nullable private final String experimentId;

    private BraintrustContext(@Nullable String projectId, @Nullable String experimentId) {
        this.projectId = projectId;
        this.experimentId = experimentId;
    }

    /** Creates a context for an experiment parent. */
    public static Context ofExperiment(@Nonnull String experimentId, @Nonnull Span span) {
        Objects.requireNonNull(experimentId);
        Objects.requireNonNull(span);
        Context ctx =
                Context.current().with(span).with(KEY, new BraintrustContext(null, experimentId));
        return setParentInBaggage(ctx, "experiment_id", experimentId);
    }

    /**
     * Sets the parent in baggage for distributed tracing.
     *
     * <p>Baggage propagates automatically via W3C headers when propagators are configured, allowing
     * parent context to flow across process boundaries.
     *
     * @param ctx the context to update
     * @param parentType the type of parent (e.g., "experiment_id", "project_name")
     * @param parentId the ID of the parent
     * @return updated context with baggage set
     */
    static Context setParentInBaggage(
            @Nonnull Context ctx, @Nonnull String parentType, @Nonnull String parentId) {
        try {
            String parentValue = (new BraintrustUtils.Parent(parentType, parentId)).toParentValue();
            Baggage existingBaggage = Baggage.fromContext(ctx);
            BaggageBuilder builder = existingBaggage.toBuilder();
            builder.put(BraintrustTracing.PARENT_KEY, parentValue);
            return ctx.with(builder.build());
        } catch (Exception e) {
            log.warn("Failed to set parent in baggage: {}", e.getMessage(), e);
            return ctx;
        }
    }

    /**
     * Retrieves the parent value from baggage for distributed tracing.
     *
     * <p>This method checks the OpenTelemetry Baggage for the braintrust.parent attribute. This is
     * used as a fallback when parent information is not available in the Context (e.g., when
     * crossing process boundaries).
     *
     * @param ctx the context to check
     * @return the parent value if present in baggage (format: "type:id")
     */
    static Optional<String> getParentFromBaggage(@Nonnull Context ctx) {
        try {
            Baggage baggage = Baggage.fromContext(ctx);
            String parentValue = baggage.getEntryValue(BraintrustTracing.PARENT_KEY);
            return Optional.ofNullable(parentValue).filter(s -> !s.isEmpty());
        } catch (Exception e) {
            log.warn("Failed to get parent from baggage: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /** Retrieves a BraintrustContext from the given Context. */
    @Nullable
    public static BraintrustContext fromContext(Context context) {
        return context.get(KEY);
    }

    public Optional<String> projectId() {
        return Optional.ofNullable(projectId);
    }

    public Optional<String> experimentId() {
        return Optional.ofNullable(experimentId);
    }
}
