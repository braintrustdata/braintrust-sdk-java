package dev.braintrust.eval;

/**
 * A task executes an EvalCase and returns a result
 *
 * @param <INPUT> type of the input data
 * @param <OUTPUT> type of the output data
 */
public interface Task<INPUT, OUTPUT> {
    TaskResult<INPUT, OUTPUT> apply(DatasetCase<INPUT, OUTPUT> datasetCase);
}
