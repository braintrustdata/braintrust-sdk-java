package dev.braintrust.devserver;

import dev.braintrust.eval.ParameterDef;
import dev.braintrust.eval.Scorer;
import dev.braintrust.eval.Task;
import dev.braintrust.eval.TaskResult;
import dev.braintrust.json.BraintrustJsonMapper;
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

    /**
     * Converts raw JSON-decoded {@code input} values from eval requests into {@code INPUT}.
     * Defaults to an unchecked cast of the raw value.
     */
    @lombok.Builder.Default @Nonnull
    private final Function<Object, INPUT> inputConverter = uncheckedCast();

    /**
     * Converts raw JSON-decoded {@code expected} values from eval requests into {@code OUTPUT}.
     * Defaults to an unchecked cast of the raw value.
     */
    @lombok.Builder.Default @Nonnull
    private final Function<Object, OUTPUT> outputConverter = uncheckedCast();

    /**
     * Create a builder for an eval whose case {@code input} and {@code expected} values are
     * deserialized into the given types using the SDK's shared Jackson mapper (see {@link
     * dev.braintrust.json.BraintrustJsonMapper}).
     *
     * <pre>{@code
     * var eval = RemoteEval.builder(MyInput.class, MyOutput.class)
     *         .name("my-eval")
     *         .taskFunction(input -> ...)
     *         .build();
     * }</pre>
     *
     * @param inputClass the type to deserialize each case's {@code input} into
     * @param outputClass the type to deserialize each case's {@code expected} into
     */
    public static <INPUT, OUTPUT> Builder<INPUT, OUTPUT> builder(
            Class<INPUT> inputClass, Class<OUTPUT> outputClass) {
        return builder(
                BraintrustJsonMapper.converter(inputClass),
                BraintrustJsonMapper.converter(outputClass));
    }

    /**
     * Create a builder for an eval whose case {@code input} and {@code expected} values are
     * converted with the supplied functions. Converters receive the raw JSON-decoded value
     * (typically a {@code Map<String, Object>}, {@code List}, {@code String}, {@code Number},
     * {@code Boolean}, or null) and must tolerate null. Most callers should prefer {@link
     * #builder(Class, Class)}; use this variant for generic types or custom deserialization.
     *
     * @param inputConverter converts each case's raw {@code input} value
     * @param outputConverter converts each case's raw {@code expected} value
     */
    public static <INPUT, OUTPUT> Builder<INPUT, OUTPUT> builder(
            Function<Object, INPUT> inputConverter, Function<Object, OUTPUT> outputConverter) {
        return new Builder<INPUT, OUTPUT>()
                .inputConverter(Objects.requireNonNull(inputConverter))
                .outputConverter(Objects.requireNonNull(outputConverter));
    }

    /**
     * Create a builder with no input/expected deserialization: case values are passed through as
     * raw JSON-decoded objects (e.g. {@code LinkedHashMap}).
     *
     * @deprecated the {@code INPUT} and {@code OUTPUT} type parameters are not applied at runtime,
     *     and the task/scorers will receive raw JSON objects, throwing {@link ClassCastException}
     *     unless those types are {@code Object} or {@code Map}. Use {@link #builder(Class, Class)}
     *     or {@link #builder(Function, Function)} instead.
     */
    @Deprecated
    public static <INPUT, OUTPUT> Builder<INPUT, OUTPUT> builder() {
        return RemoteEval.builder(uncheckedCast(), uncheckedCast());
    }

    /** Legacy behavior: pass the raw JSON-decoded value through via an unchecked cast. */
    @SuppressWarnings("unchecked")
    private static <T> Function<Object, T> uncheckedCast() {
        return raw -> (T) raw;
    }

    public static class Builder<INPUT, OUTPUT> {
        /**
         * Convenience builder method to create a RemoteEval with a simple task function.
         *
         * @param taskFn Function that takes input and returns output
         * @return this builder
         */
        public Builder<INPUT, OUTPUT> taskFunction(Function<INPUT, OUTPUT> taskFn) {
            return task(
                    (datasetCase, parameters) -> {
                        var result = taskFn.apply(datasetCase.input());
                        return new TaskResult<>(result, datasetCase, parameters);
                    });
        }

        /** Build the RemoteEval */
        public RemoteEval<INPUT, OUTPUT> build() {
            var result = internalBuild();
            Objects.requireNonNull(result.getInputConverter(), "inputConverter");
            Objects.requireNonNull(result.getOutputConverter(), "outputConverter");
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
