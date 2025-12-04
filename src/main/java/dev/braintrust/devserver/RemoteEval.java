package dev.braintrust.devserver;

import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.eval.Scorer;
import dev.braintrust.eval.Task;
import java.util.*;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
     * The Braintrust project name. If not specified, uses config.defaultProjectName() or
     * config.defaultProjectId()
     */
    @Nonnull private final String projectName;

    /** Braintrust configuration for API access */
    @Nonnull private final BraintrustConfig config;

    /** The task function that performs the evaluation */
    @Nonnull private final Task<INPUT, OUTPUT> task;

    /** List of scorers for this evaluator */
    @Singular @Nonnull private final List<Scorer<INPUT, OUTPUT>> scorers;

    /** Optional parameters that can be configured from the UI */
    @Singular @Nonnull private final Map<String, Parameter> parameters;

    public static class Builder<INPUT, OUTPUT> {
        /**
         * Convenience builder method to create a RemoteEval with a simple task function.
         *
         * @param taskFn Function that takes input and returns output
         * @return this builder
         */
        public Builder<INPUT, OUTPUT> taskFunction(Function<INPUT, OUTPUT> taskFn) {
            return task(
                    datasetCase -> {
                        var result = taskFn.apply(datasetCase.input());
                        return new dev.braintrust.eval.TaskResult<>(result, datasetCase);
                    });
        }

        /** Build the RemoteEval, using config's default project name if projectName is not set. */
        public RemoteEval<INPUT, OUTPUT> build() {
            // If projectName is not set, try to use the default from config
            if (this.projectName == null && this.config != null) {
                // Try defaultProjectName first, fall back to defaultProjectId
                this.projectName =
                        this.config
                                .defaultProjectName()
                                .or(() -> this.config.defaultProjectId())
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "projectName must be specified either"
                                                            + " explicitly or via"
                                                            + " config.defaultProjectName/defaultProjectId"));
            }

            if (this.projectName == null) {
                throw new IllegalArgumentException(
                        "projectName must be specified (config is not set yet)");
            }

            return internalBuild();
        }
    }

    /** Represents a configurable parameter for the evaluator */
    @Getter
    @lombok.Builder(builderClassName = "Builder")
    public static class Parameter {
        /** Type of parameter: "prompt" or "data" */
        @Nonnull private final ParameterType type;

        /** Optional description of the parameter */
        @Nullable private final String description;

        /** Optional default value for the parameter */
        @Nullable private final Object defaultValue;

        /**
         * JSON Schema for data type parameters. Only applicable when type is DATA. Should be a Map
         * representing a JSON Schema object.
         */
        @Nullable private final Map<String, Object> schema;

        public static Parameter promptParameter(String description, Object defaultValue) {
            return Parameter.builder()
                    .type(ParameterType.PROMPT)
                    .description(description)
                    .defaultValue(defaultValue)
                    .build();
        }

        public static Parameter promptParameter(Object defaultValue) {
            return promptParameter(null, defaultValue);
        }

        public static Parameter dataParameter(
                String description, Map<String, Object> schema, Object defaultValue) {
            return Parameter.builder()
                    .type(ParameterType.DATA)
                    .description(description)
                    .schema(schema)
                    .defaultValue(defaultValue)
                    .build();
        }

        public static Parameter dataParameter(Map<String, Object> schema, Object defaultValue) {
            return dataParameter(null, schema, defaultValue);
        }

        public static Parameter dataParameter(Map<String, Object> schema) {
            return dataParameter(null, schema, null);
        }
    }

    /** Parameter type enumeration */
    public enum ParameterType {
        /** Prompt parameter (for LLM prompts) */
        PROMPT("prompt"),
        /** Data parameter (for other configuration data) */
        DATA("data");

        private final String value;

        ParameterType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }
}
