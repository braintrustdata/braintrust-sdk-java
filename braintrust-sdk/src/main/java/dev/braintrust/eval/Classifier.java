package dev.braintrust.eval;

import java.util.List;
import java.util.function.Function;

/**
 * A classifier categorizes and labels eval outputs, producing zero or more structured {@link
 * Classification} items.
 *
 * <p>Classifiers run independently from {@link Scorer}s. Each Classifier exposes a name (used as
 * the span name and as the default grouping key for classifications whose own {@code name} is
 * blank).
 *
 * @param <INPUT> type of the input data
 * @param <OUTPUT> type of the output data
 */
public interface Classifier<INPUT, OUTPUT> {
    String INVALID_CLASSIFICATION_MESSAGE =
            "When returning structured classifier results, each classification must be a non-empty"
                    + " object.";

    String getName();

    /**
     * Classifies the result of a successful task execution.
     *
     * @param taskResult the task output and originating dataset case
     * @return zero or more classifications. An empty list means "no classifications for this case".
     */
    List<Classification> classify(TaskResult<INPUT, OUTPUT> taskResult);

    /**
     * Creates a classifier from a function that returns a (possibly empty or null) list of
     * classifications.
     *
     * <p>A {@code null} return value is treated as no classifications. Each returned {@link
     * Classification} must have a non-blank {@code id}; otherwise the classifier throws an
     * exception (which the eval runner records but does not abort on).
     */
    static <INPUT, OUTPUT> Classifier<INPUT, OUTPUT> of(
            String classifierName,
            Function<TaskResult<INPUT, OUTPUT>, List<Classification>> classifierFn) {
        return new Classifier<>() {
            @Override
            public String getName() {
                return classifierName;
            }

            @Override
            public List<Classification> classify(TaskResult<INPUT, OUTPUT> taskResult) {
                var result = classifierFn.apply(taskResult);
                if (result == null) {
                    return List.of();
                }
                for (var item : result) {
                    validate(item);
                }
                return result;
            }
        };
    }

    /**
     * Creates a classifier from a function that returns a single classification.
     *
     * <p>A {@code null} return value is treated as no classifications.
     */
    static <INPUT, OUTPUT> Classifier<INPUT, OUTPUT> single(
            String classifierName,
            Function<TaskResult<INPUT, OUTPUT>, Classification> classifierFn) {
        return new Classifier<>() {
            @Override
            public String getName() {
                return classifierName;
            }

            @Override
            public List<Classification> classify(TaskResult<INPUT, OUTPUT> taskResult) {
                var item = classifierFn.apply(taskResult);
                if (item == null) {
                    return List.of();
                }
                validate(item);
                return List.of(item);
            }
        };
    }

    /**
     * Validates a single classification: it must have a non-blank id. Throws with the spec-mandated
     * wording on failure.
     */
    private static void validate(Classification item) {
        if (item == null || item.id() == null || item.id().isBlank()) {
            throw new IllegalArgumentException(INVALID_CLASSIFICATION_MESSAGE + " Got: " + item);
        }
    }
}
