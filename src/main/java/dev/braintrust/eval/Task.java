package dev.braintrust.eval;

/**
 * A task function that runs against a single dataset case and produces an output.
 *
 * <p>If the task throws an exception, the error is recorded on the span and each scorer's {@link
 * Scorer#scoreForTaskException} method is invoked instead of {@link Scorer#score}.
 *
 * @param <INPUT> type of the input data
 * @param <OUTPUT> type of the output data
 */
public interface Task<INPUT, OUTPUT> {
    /**
     * Executes this task against a single dataset case and returns the result.
     *
     * @param datasetCase the dataset case to evaluate
     * @return the task result containing the output and the originating dataset case
     * @throws Exception if the task fails, the error will be recorded on the span and scoring will
     *     fall back to {@link Scorer#scoreForTaskException}
     */
    TaskResult<INPUT, OUTPUT> apply(DatasetCase<INPUT, OUTPUT> datasetCase) throws Exception;
}
