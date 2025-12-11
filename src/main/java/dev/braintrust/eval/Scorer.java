package dev.braintrust.eval;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A scorer evaluates the result of a test case with a score between 0 (inclusive) and 1
 * (inclusive).
 *
 * @param <INPUT> type of the input data
 * @param <OUTPUT> type of the output data
 */
public interface Scorer<INPUT, OUTPUT> {
    String getName();

    List<Score> score(TaskResult<INPUT, OUTPUT> taskResult);

    static <INPUT, OUTPUT> Scorer<INPUT, OUTPUT> of(
            String scorerName, Function<TaskResult<INPUT, OUTPUT>, Double> scorerFn) {
        return new Scorer<>() {
            @Override
            public String getName() {
                return scorerName;
            }

            @Override
            public List<Score> score(TaskResult<INPUT, OUTPUT> taskResult) {
                return List.of(new Score(scorerName, scorerFn.apply(taskResult)));
            }
        };
    }

    static <INPUT, OUTPUT> Scorer<INPUT, OUTPUT> of(
            String scorerName, BiFunction<OUTPUT, OUTPUT, Double> scorerFn) {
        return new Scorer<>() {
            @Override
            public String getName() {
                return scorerName;
            }

            @Override
            public List<Score> score(TaskResult<INPUT, OUTPUT> taskResult) {
                return List.of(
                        new Score(
                                scorerName,
                                scorerFn.apply(
                                        taskResult.datasetCase().expected(), taskResult.result())));
            }
        };
    }
}
