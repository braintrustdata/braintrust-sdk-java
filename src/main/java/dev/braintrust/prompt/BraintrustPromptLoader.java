package dev.braintrust.prompt;

import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.config.BraintrustConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.Builder;

/** Load LLM objects from the Braintrust API */
public class BraintrustPromptLoader {
    private final BraintrustConfig config;
    private final BraintrustApiClient client;

    private BraintrustPromptLoader(BraintrustConfig config, BraintrustApiClient client) {
        this.config = config;
        this.client = client;
    }

    public static BraintrustPromptLoader of(BraintrustConfig config, BraintrustApiClient client) {
        return new BraintrustPromptLoader(config, client);
    }

    public BraintrustPrompt load(String promptSlug) {
        PromptLoadRequest request = PromptLoadRequest.builder().promptSlug(promptSlug).build();
        return load(request);
    }

    public BraintrustPrompt load(PromptLoadRequest promptLoadRequest) {
        var projectName = promptLoadRequest.projectName;
        if (null == projectName) {
            // TODO: fall back to project ID if appropriate
            projectName = config.defaultProjectName().orElseThrow();
        }
        // Request the prompt from the Braintrust API
        var promptOpt =
                client.getPrompt(
                        projectName, promptLoadRequest.promptSlug, promptLoadRequest.version);
        var prompt =
                promptOpt.orElseThrow(
                        () ->
                                new RuntimeException(
                                        "Prompt not found: " + promptLoadRequest.promptSlug));
        return new BraintrustPrompt(prompt, promptLoadRequest.defaults);
    }

    @Builder
    public static class PromptLoadRequest {
        private @Nonnull String promptSlug;
        private @Nullable String projectName;
        private @Nullable String version;
        @Builder.Default private @Nonnull Map<String, String> defaults = Map.of();

        public static class PromptLoadRequestBuilder {
            public PromptLoadRequestBuilder defaults(String... keyValuePairDefaults) {
                this.defaults$value = keyValueListToMap(keyValuePairDefaults);
                this.defaults$set = true;
                return this;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Map<T, T> keyValueListToMap(T... keyValueList) {
        if (keyValueList.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "keyValueList must contain an even number of elements (key-value pairs)");
        }

        Map<T, T> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValueList.length; i += 2) {
            T key = keyValueList[i];
            T value = keyValueList[i + 1];
            map.put(key, value);
        }

        return Map.copyOf(map);
    }
}
