package dev.braintrust.eval;

/** Result from a single task run. */
public record TaskResult<INPUT, OUTPUT>(
        /** task output */
        OUTPUT result,
        /** The dataset case the task ran against to produce the result */
        DatasetCase<INPUT, OUTPUT> datasetCase) {}
