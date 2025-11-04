package dev.braintrust.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode
class BaseConfig {
    /** Sentinal used to set null in the env. Only used for testing. */
    static final String NULL_OVERRIDE = "BRAINTRUST_NULL_SENTINAL_" + System.currentTimeMillis();

    protected final Map<String, String> envOverrides;

    BaseConfig(Map<String, String> envOverrides) {
        this.envOverrides = Map.copyOf(envOverrides);
    }

    protected <T> @Nonnull T getConfig(@Nonnull String settingName, @Nonnull T defaultValue) {
        Objects.requireNonNull(defaultValue);
        return Objects.requireNonNull(
                getConfig(settingName, defaultValue, (Class<T>) defaultValue.getClass()));
    }

    protected <T> @Nullable T getConfig(
            @Nonnull String settingName, @Nullable T defaultValue, @Nonnull Class<T> settingClass) {
        @Nullable String rawVal = getEnvValue(settingName);
        if (rawVal == null) {
            return defaultValue;
        } else {
            return cast(rawVal, settingClass);
        }
    }

    protected @Nonnull String getRequiredConfig(@Nonnull String settingName) {
        return getRequiredConfig(settingName, String.class);
    }

    protected @Nonnull <T> T getRequiredConfig(String settingName, Class<T> settingClass) {
        T value = getConfig(settingName, null, settingClass);
        if (null == value) {
            throw new RuntimeException("%s is required".formatted(settingName));
        }
        return value;
    }

    protected <T> T cast(@Nonnull String value, @Nonnull Class<T> clazz) {
        if (clazz.equals(String.class)) {
            return (T) value;
        } else if (List.of(Boolean.class, boolean.class).contains(clazz)) {
            return (T) Boolean.valueOf(value);
        } else if (List.of(Integer.class, int.class).contains(clazz)) {
            return (T) Integer.valueOf(value);
        } else if (List.of(Long.class, long.class).contains(clazz)) {
            return (T) Long.valueOf(value);
        } else if (List.of(Float.class, float.class).contains(clazz)) {
            return (T) Float.valueOf(value);
        } else if (List.of(Double.class, double.class).contains(clazz)) {
            return (T) Double.valueOf(value);
        } else {
            throw new RuntimeException(
                    "Unsupported default class: %s -- please implement or use a different default"
                            .formatted(clazz));
        }
    }

    protected @Nullable String getEnvValue(@Nonnull String settingName) {
        // first try the override map
        var settingValue = envOverrides.get(settingName);
        if (settingValue == null) {
            // then get it from the sysenv
            settingValue = System.getenv(settingName);
        }
        return NULL_OVERRIDE.equals(settingValue) ? null : settingValue;
    }
}
