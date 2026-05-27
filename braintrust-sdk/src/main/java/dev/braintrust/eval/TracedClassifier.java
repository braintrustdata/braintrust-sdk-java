package dev.braintrust.eval;

import dev.braintrust.trace.BrainstoreTrace;
import java.util.List;

/**
 * A classifier that receives access to the full distributed trace of the task that was evaluated.
 *
 * <p>Implement this interface when your classifier needs to examine intermediate LLM calls, tool
 * invocations, or other spans produced during task execution — not just the final {@link
 * TaskResult}.
 *
 * @param <INPUT> type of the input data
 * @param <OUTPUT> type of the output data
 */
public interface TracedClassifier<INPUT, OUTPUT> extends Classifier<INPUT, OUTPUT> {

    /**
     * Classifies the task result using the distributed trace for additional context. Called instead
     * of {@link Classifier#classify(TaskResult)} when a {@link BrainstoreTrace} is available.
     *
     * @param taskResult the task output and originating dataset case
     * @param trace lazy access to the distributed trace spans for this eval case
     * @return zero or more classifications
     */
    List<Classification> classify(TaskResult<INPUT, OUTPUT> taskResult, BrainstoreTrace trace);

    /**
     * {@inheritDoc}
     *
     * <p>When used inside an {@link Eval}, this overload is never called — {@link
     * #classify(TaskResult, BrainstoreTrace)} is dispatched instead. This default implementation
     * throws {@link UnsupportedOperationException} to surface any accidental direct calls.
     */
    @Override
    default List<Classification> classify(TaskResult<INPUT, OUTPUT> taskResult) {
        throw new UnsupportedOperationException(
                "traced classifier classify method directly called. This is likely an accident. If"
                    + " you wish to support this, your implementation must override this method.");
    }
}
