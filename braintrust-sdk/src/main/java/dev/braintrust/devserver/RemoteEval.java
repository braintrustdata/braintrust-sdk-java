package dev.braintrust.devserver;

import dev.braintrust.eval.DatasetCase;
import dev.braintrust.eval.ParameterDef;
import dev.braintrust.eval.Parameters;
import dev.braintrust.eval.Scorer;
import dev.braintrust.eval.Task;
import dev.braintrust.eval.TaskResult;
import java.util.*;
import java.util.function.Function;
import javax.annotation.Nonnull;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

/**
 * Represents a remote evaluator that can be exposed via the dev server.
 *
 * @param <INPUT> The type of input data for the evaluation
 * @param <OUTPUT> The type of output produced by the task
 */
@Getter
@Builder(builderClassName = "Builder", buildMethodName = "internalBuild")
public class RemoteEval<INPUT, OUTPUT> {
    /** The name of this evaluator (used as identifier) */
    @Nonnull private final String name;

    /**
     * The task function that performs the evaluation
     *
     * <p>The task function must be thread safe.
     */
    @Nonnull private final Task<INPUT, OUTPUT> task;

    /**
     * List of scorers for this evaluator
     *
     * <p>The score function must be thread safe.
     */
    @Singular @Nonnull private final List<Scorer<INPUT, OUTPUT>> scorers;

    /** Optional parameter definitions that can be configured from the UI */
    @Singular @Nonnull private final List<ParameterDef<?>> parameters;

    public static class Builder<INPUT, OUTPUT> {
        /**
         * Convenience builder method to create a RemoteEval with a simple task function.
         *
         * @param taskFn Function that takes input and returns output
         * @return this builder
         */
        public Builder<INPUT, OUTPUT> taskFunction(Function<INPUT, OUTPUT> taskFn) {
            return task(
                    new Task<>() {
                        @Override
                        public TaskResult<INPUT, OUTPUT> apply(
                                DatasetCase<INPUT, OUTPUT> datasetCase, Parameters parameters)
                                throws Exception {
                            var result = taskFn.apply(datasetCase.input());
                            return new TaskResult<>(result, datasetCase, parameters);
                        }
                    });
        }

        /** Build the RemoteEval */
        public RemoteEval<INPUT, OUTPUT> build() {
            var result = internalBuild();
            // Validate parameter names are unique
            var seen = new HashSet<String>();
            for (var param : result.getParameters()) {
                if (!seen.add(param.name())) {
                    throw new IllegalArgumentException(
                            "Duplicate parameter name: '" + param.name() + "'");
                }
            }
            return result;
        }
    }
}
