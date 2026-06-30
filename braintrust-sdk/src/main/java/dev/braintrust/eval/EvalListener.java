package dev.braintrust.eval;

import io.opentelemetry.api.trace.Span;
import java.util.List;
import javax.annotation.Nullable;

/**
 * A listener which can be attached to an eval to observe and/or decorate its lifecycle.
 *
 * <p>{@link Eval} owns the OpenTelemetry span <em>structure</em> — it creates the root ({@code
 * eval}), {@code task}, {@code score}, and classifier spans (names only), manages the current
 * context (so user/LLM child spans nest correctly), and ends the spans. Listeners receive the live
 * {@link Span}s at each lifecycle point and may decorate them with attributes (e.g. the built-in
 * {@link EvalSpanDecorator}) or simply observe them (e.g. read span ids for streaming progress).
 *
 * <p>All callbacks are no-ops by default so implementations only override what they need.
 */
public interface EvalListener {
    /** Creates a run-scoped listener. Called once per {@link Eval#run()}. */
    RunListener createRunListener(EvalRunInfo info);

    /** Run-scoped listener; spawns a {@link CaseListener} per eval case. */
    interface RunListener {
        default void onRunStart() {}

        CaseListener createCaseListener(DatasetCase<?, ?> datasetCase);

        default void onRunEnd() {}
    }

    /** Case-scoped listener receiving the live spans for a single eval case. */
    interface CaseListener {
        /** The root {@code eval} span has been created (no attributes yet). */
        default void onRootSpan(Span rootSpan, DatasetCase<?, ?> datasetCase) {}

        /** The {@code task} span has been created (no attributes yet). */
        default void onTaskSpan(Span taskSpan, DatasetCase<?, ?> datasetCase) {}

        /** The task completed successfully. */
        default void onTaskSuccess(Span rootSpan, Span taskSpan, TaskResult<?, ?> taskResult) {}

        /**
         * The task threw. Scorers still run via {@code scoreForTaskException}; classifiers do not.
         */
        default void onTaskError(
                Span rootSpan, Span taskSpan, DatasetCase<?, ?> datasetCase, Exception error) {}

        /** A {@code score} span has been created (no attributes yet). */
        default void onScoreSpan(Span scoreSpan, Scorer<?, ?> scorer) {}

        /**
         * A scorer produced scores. Not called when score validation aborts the eval. {@code
         * scoreException} is non-null when the scorer threw and the fallback was used.
         */
        default void onScoreResult(
                Span scoreSpan,
                Span rootSpan,
                Scorer<?, ?> scorer,
                List<Score> scores,
                @Nullable Exception scoreException) {}

        /** A classifier span has been created (no attributes yet). */
        default void onClassifierSpan(
                Span classifierSpan, Classifier<?, ?> classifier, String resolvedName) {}

        /**
         * A classifier finished. {@code classifierException} is non-null when the classifier threw
         * (non-fatal).
         */
        default void onClassifierResult(
                Span classifierSpan,
                Span rootSpan,
                Classifier<?, ?> classifier,
                String resolvedName,
                List<Classification> classifications,
                @Nullable Exception classifierException) {}

        /** The case is finishing; the root span is about to be ended. */
        default void onCaseEnd(Span rootSpan) {}
    }
}
