package dev.braintrust.eval;

/**
 * A task function that runs against a single dataset case and produces an output.
 *
 * <p>If the task throws an exception, the error is recorded on the span and each scorer's {@link
 * Scorer#scoreForTaskException} method is invoked instead of {@link Scorer#score}.
 *
 * <p>Tasks may optionally accept {@link Parameters} by overriding the two-arg {@link
 * #apply(DatasetCase, Parameters)} method. Tasks that don't need parameters can override the
 * single-arg {@link #apply(DatasetCase)} method instead — the two-arg version delegates to it by
 * default.
 *
 * @param <INPUT> type of the input data
 * @param <OUTPUT> type of the output data
 */
public interface Task<INPUT, OUTPUT> {
    /**
     * Executes this task against a single dataset case, with access to merged eval parameters.
     *
     * <p>Override this method if your task needs to read parameter values (e.g., model name,
     * temperature). The default implementation delegates to {@link #apply(DatasetCase)}, ignoring
     * parameters.
     *
     * @param datasetCase the dataset case to evaluate
     * @param parameters the merged parameter values for this eval run
     * @return the task result containing the output and the originating dataset case
     * @throws Exception if the task fails, the error will be recorded on the span and scoring will
     *     fall back to {@link Scorer#scoreForTaskException}
     */
    TaskResult<INPUT, OUTPUT> apply(DatasetCase<INPUT, OUTPUT> datasetCase, Parameters parameters)
            throws Exception;

    /**
     * Executes this task against a single dataset case.
     *
     * <p>Override this method for tasks that do not need parameter values. If you need parameters,
     * override {@link #apply(DatasetCase, Parameters)} instead.
     *
     * @param datasetCase the dataset case to evaluate
     * @return the task result containing the output and the originating dataset case
     * @throws Exception if the task fails, the error will be recorded on the span and scoring will
     *     fall back to {@link Scorer#scoreForTaskException}
     */
    default TaskResult<INPUT, OUTPUT> apply(DatasetCase<INPUT, OUTPUT> datasetCase)
            throws Exception {
        return apply(datasetCase, Parameters.empty());
    }
}
