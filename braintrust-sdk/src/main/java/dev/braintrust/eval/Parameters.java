package dev.braintrust.eval;

import dev.braintrust.json.BraintrustJsonMapper;
import java.util.*;
import javax.annotation.Nonnull;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Holds the merged parameter values for a single eval run, along with the parameter definitions.
 *
 * <p>Parameter values are the result of merging evaluator defaults with request overrides. This
 * class provides typed accessors so task and scorer implementations don't need to cast.
 */
@Slf4j
public class Parameters {
    private static final Parameters EMPTY =
            new Parameters(Collections.emptyList(), Collections.emptyMap());

    /** -- GETTER -- Returns the merged parameter values as an unmodifiable map. */
    @Getter private final Map<String, Object> merged;

    public Parameters(List<ParameterDef<?>> definitions, Map<String, Object> requestParams) {
        var remaining = new LinkedHashMap<>(requestParams);
        Map<String, Object> merged = new LinkedHashMap<>();
        for (var def : definitions) {
            var paramVal = remaining.remove(def.name());
            if (null == paramVal) {
                paramVal = def.defaultValue();
            }
            if (null != paramVal) {
                merged.put(def.name(), paramVal);
            }
        }
        if (!remaining.isEmpty()) {
            log.warn("unknown param names found in eval request: {}", remaining.keySet());
        }
        this.merged = Collections.unmodifiableMap(new LinkedHashMap<>(merged));
        // NOTE: we're not holding on to definitions outside of the constructor, but we may wish to
        // surface them later
    }

    /** Returns an empty {@code Parameters} instance with no values or definitions. */
    public static Parameters empty() {
        return EMPTY;
    }

    /** Returns true if no parameter values are present. */
    public boolean isEmpty() {
        return merged.isEmpty();
    }

    /** Returns true if a value exists for the given key. */
    public boolean has(String key) {
        return merged.containsKey(key);
    }

    /** Returns the raw value for the given key, or null if absent. */
    public <T> T get(@Nonnull String key, @Nonnull Class<T> paramClass) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(paramClass);
        if (!has(key)) {
            throw new RuntimeException("param not found: " + key);
        }
        var rawParam = merged.get(key);
        if (rawParam == null) {
            return null;
        }
        // Coerce integer types to floating point if requested
        if (rawParam instanceof Number number) {
            if (paramClass == Double.class || paramClass == double.class) {
                return (T) (Double) number.doubleValue();
            }
            if (paramClass == Float.class || paramClass == float.class) {
                return (T) (Float) number.floatValue();
            }
        }
        var actualClass = rawParam.getClass();
        if (paramClass.isAssignableFrom(actualClass)) {
            return (T) rawParam;
        }
        // Auto-convert using Jackson (e.g., Map -> POJO when Playground sends JSON objects)
        try {
            return BraintrustJsonMapper.get().convertValue(rawParam, paramClass);
        } catch (IllegalArgumentException e) {
            throw new ClassCastException(
                    "cannot convert param \"%s\" (%s) to %s: %s"
                            .formatted(key, actualClass, paramClass, e.getMessage()));
        }
    }
}
