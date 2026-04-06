package dev.braintrust.eval;

import javax.annotation.Nonnull;

/** Result from a single task run. */
public record TaskResult<INPUT, OUTPUT>(
        /** task output */
        OUTPUT result,
        /** The dataset case the task ran against to produce the result */
        DatasetCase<INPUT, OUTPUT> datasetCase,
        /** The merged parameter values for this eval run */
        @Nonnull Parameters parameters) {
    public TaskResult(OUTPUT result, DatasetCase<INPUT, OUTPUT> datasetCase) {
        this(result, datasetCase, Parameters.empty());
    }
}
