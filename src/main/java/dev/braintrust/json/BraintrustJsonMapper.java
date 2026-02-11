package dev.braintrust.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import lombok.SneakyThrows;

/** Centralized ObjectMapper for the Braintrust SDK. */
public final class BraintrustJsonMapper {

    private static volatile ObjectMapper instance;
    private static final List<Consumer<ObjectMapper>> configurers = new ArrayList<>();
    private static volatile boolean initialized = false;

    /** Default configuration applied to all ObjectMapper instances. */
    private static final Consumer<ObjectMapper> DEFAULT_CONFIG =
            objectMapper -> {
                objectMapper
                        .registerModule(new JavaTimeModule())
                        .registerModule(new Jdk8Module())
                        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        .setDefaultPropertyInclusion(JsonInclude.Include.NON_ABSENT)
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            };

    static {
        configurers.add(DEFAULT_CONFIG);
    }

    private BraintrustJsonMapper() {}

    public static ObjectMapper get() {
        if (instance == null) {
            synchronized (BraintrustJsonMapper.class) {
                if (instance == null) {
                    instance = new ObjectMapper();
                    for (Consumer<ObjectMapper> configurer : configurers) {
                        configurer.accept(instance);
                    }
                    initialized = true;
                }
            }
        }
        return instance;
    }

    public static synchronized void configure(Consumer<ObjectMapper> configurer) {
        if (initialized) {
            throw new IllegalStateException(
                    "BraintrustJsonMapper has already been initialized. "
                            + "configure() must be called before the first call to get().");
        }
        configurers.add(configurer);
    }

    @SneakyThrows
    public static String toJson(Object o) {
        return get().writeValueAsString(o);
    }

    @SneakyThrows
    public static <T> T fromJson(String jsonString, Class<T> targetClass) {
        return get().readValue(jsonString, targetClass);
    }

    static synchronized void reset() {
        instance = null;
        configurers.clear();
        configurers.add(DEFAULT_CONFIG); // Re-add default configuration
        initialized = false;
    }
}
