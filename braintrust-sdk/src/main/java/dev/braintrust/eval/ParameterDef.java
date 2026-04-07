package dev.braintrust.eval;

import dev.braintrust.json.BraintrustJsonMapper;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Definition of a named parameter that can be configured from the Braintrust Playground UI.
 *
 * <p>Parameter definitions declare what parameters an evaluator accepts, their types, defaults, and
 * descriptions. The Playground uses these to render appropriate UI controls.
 *
 * @param name the parameter name (used as the key in the merged parameters map)
 * @param type the parameter type: {@code "data"} for generic values, {@code "model"} for a model
 *     picker
 * @param defaultValue optional default value used when the request does not include this parameter
 * @param description optional human-readable description shown in the Playground UI
 * @param schema optional JSON Schema fragment describing the value shape (e.g., {@code {"type":
 *     "string"}}). Only applicable for {@code "data"} type parameters.
 */
public record ParameterDef<T>(
        @Nonnull String name,
        @Nonnull Type type,
        @Nullable T defaultValue,
        @Nullable String description,
        @Nullable Map<String, Object> schema) {

    public ParameterDef {
        Objects.requireNonNull(name);
        Objects.requireNonNull(type);
    }

    public static <T> ParameterDef<T> data(@Nonnull String name, @Nonnull T defaultValue) {
        return data(name, defaultValue, null);
    }

    public static <T> ParameterDef<T> data(
            @Nonnull String name, @Nonnull T defaultValue, @Nullable String description) {
        return (ParameterDef<T>) data(name, defaultValue.getClass(), defaultValue, description);
    }

    /**
     * Create a data parameter definition with an explicit value class (for when no default is
     * provided or the type can't be inferred from the default).
     *
     * @param valueClass the Java class of the parameter value (e.g., {@code String.class}, {@code
     *     Map.class})
     */
    public static <T> ParameterDef<T> data(
            @Nonnull String name,
            @Nonnull Class<? extends T> valueClass,
            @Nullable T defaultValue,
            @Nullable String description) {
        var dataType = DataType.ofClass(valueClass);
        if (null == dataType) {
            throw new RuntimeException("unsupported parameter value class: " + valueClass);
        }
        if (DataType.OBJECT.equals(dataType)) {
            // fail fast if the class can't serialize
            try {
                BraintrustJsonMapper.get().constructType(valueClass);
                // Try deserializing an empty object — catches missing default constructor,
                // missing @JsonCreator, etc.
                BraintrustJsonMapper.fromJson("{}", valueClass);
            } catch (Exception e) {
                throw new RuntimeException(
                        "invalid object data type. Class is not deserializable by"
                                + " BraintrustJsonMapper: "
                                + valueClass,
                        e);
            }
        }
        return new ParameterDef<>(
                name,
                Type.DATA,
                defaultValue,
                description,
                Map.of("type", dataType.name().toLowerCase()));
    }

    /** Create a model parameter definition. The default value is a model name string. */
    public static ParameterDef<String> model(String name, String defaultValue) {
        return model(name, defaultValue, null);
    }

    /** Create a model parameter definition with a description. */
    public static ParameterDef<String> model(String name, String defaultValue, String description) {
        return new ParameterDef<>(name, Type.MODEL, defaultValue, description, null);
    }

    public enum Type {
        DATA,
        // TODO: prompts not supported yet
        // PROMPT,
        MODEL
    }

    enum DataType {
        STRING,
        NUMBER,
        BOOLEAN,
        OBJECT,
        ARRAY;

        static @Nullable DataType of(Object parameterValue) {
            if (null == parameterValue) return null;
            else if (parameterValue instanceof String) return DataType.STRING;
            else if (parameterValue instanceof Number) return DataType.NUMBER;
            else if (parameterValue instanceof Boolean) return DataType.BOOLEAN;
            else if (parameterValue instanceof Iterable) return DataType.ARRAY;
            else return DataType.OBJECT;
        }

        static @Nullable DataType ofClass(Class<?> clazz) {
            if (String.class.isAssignableFrom(clazz)) return STRING;
            if (Number.class.isAssignableFrom(clazz)) return NUMBER;
            if (Boolean.class.isAssignableFrom(clazz)) return BOOLEAN;
            if (Iterable.class.isAssignableFrom(clazz)) return ARRAY;
            if (Map.class.isAssignableFrom(clazz)) return OBJECT;
            // Assume any other class is a Jackson-serializable POJO
            return OBJECT;
        }
    }
}
