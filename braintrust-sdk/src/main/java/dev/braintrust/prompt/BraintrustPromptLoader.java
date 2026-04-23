package dev.braintrust.prompt;

import dev.braintrust.api.BraintrustOpenApiClient;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.openapi.api.PromptsApi;
import dev.braintrust.openapi.model.PromptDataNullish;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.Builder;

/** Load LLM prompts from the Braintrust API */
public class BraintrustPromptLoader {
    private final BraintrustConfig config;
    private final PromptsApi promptsApi;

    private BraintrustPromptLoader(BraintrustConfig config, BraintrustOpenApiClient apiClient) {
        this.config = config;
        this.promptsApi = new PromptsApi(apiClient);
    }

    public static BraintrustPromptLoader of(
            BraintrustConfig config, BraintrustOpenApiClient apiClient) {
        return new BraintrustPromptLoader(config, apiClient);
    }

    public BraintrustPrompt load(String promptSlug) {
        return load(PromptLoadRequest.builder().promptSlug(promptSlug).build());
    }

    public BraintrustPrompt load(PromptLoadRequest request) {
        var projectName =
                request.projectName != null
                        ? request.projectName
                        : config.defaultProjectName().orElseThrow();

        var response =
                promptsApi.getPrompt(
                        null, // limit
                        null, // startingAfter
                        null, // endingBefore
                        null, // ids
                        null, // promptName
                        projectName, // projectName
                        null, // projectId
                        request.promptSlug, // slug
                        request.version, // version
                        null, // environment
                        null // orgName
                        );

        List<?> objects = response.getObjects();
        if (objects == null || objects.isEmpty()) {
            throw new RuntimeException("Prompt not found: " + request.promptSlug);
        }
        if (objects.size() > 1) {
            throw new RuntimeException(
                    "Multiple prompts found for slug: "
                            + request.promptSlug
                            + ", projectName: "
                            + projectName);
        }

        var prompt = (dev.braintrust.openapi.model.Prompt) objects.get(0);
        PromptDataNullish promptData = prompt.getPromptData();
        if (promptData == null) {
            throw new RuntimeException("prompt_data missing for prompt: " + request.promptSlug);
        }

        return new BraintrustPrompt(promptData, request.defaults);
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
            map.put(keyValueList[i], keyValueList[i + 1]);
        }
        return Map.copyOf(map);
    }
}
