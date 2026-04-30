package dev.braintrust.eval;

import dev.braintrust.trace.BrainstoreTrace;
import java.util.List;

/**
 * A scorer that receives access to the full distributed trace of the task that was evaluated.
 *
 * <p>Implement this interface when your scorer needs to examine intermediate LLM calls, tool
 * invocations, or other spans produced during task execution — not just the final {@code
 * TaskResult}.
 *
 * @param <INPUT> type of the input data
 * @param <OUTPUT> type of the output data
 */
public interface TracedScorer<INPUT, OUTPUT> extends Scorer<INPUT, OUTPUT> {

    /**
     * Scores the task result using the distributed trace for additional context. Called instead of
     * {@link Scorer#score(TaskResult)} when a {@link BrainstoreTrace} is available.
     *
     * @param taskResult the task output and originating dataset case
     * @param trace lazy access to the distributed trace spans for this eval case
     * @return one or more scores, each with a value between 0 and 1 inclusive
     */
    List<Score> score(TaskResult<INPUT, OUTPUT> taskResult, BrainstoreTrace trace);

    /**
     * {@inheritDoc}
     *
     * <p>When used inside an {@link Eval}, this overload is never called — {@link
     * #score(TaskResult, BrainstoreTrace)} is dispatched instead. This default implementation
     * throws {@link UnsupportedOperationException} to surface any accidental direct calls.
     */
    @Override
    default List<Score> score(TaskResult<INPUT, OUTPUT> taskResult) {
        throw new UnsupportedOperationException(
                "traced scorer score method directly called. This is likely an accident. If you"
                        + " wish to support this, your implementation must override this method.");
    }
}
