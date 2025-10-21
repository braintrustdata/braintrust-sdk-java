package dev.braintrust.prompt;

import dev.braintrust.config.BraintrustConfig;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.Builder;

public class BraintrustPromptLoader {
    public static BraintrustPromptLoader of(BraintrustConfig config) {
        throw new RuntimeException("TODO");
    }

    public BraintrustPrompt load(String promptSlug) {
        PromptLoadRequest.builder().promptSlug(promptSlug);
        throw new RuntimeException("TODO");
    }

    public BraintrustPrompt load(PromptLoadRequest promptLoadRequest) {
        throw new RuntimeException("TODO");
    }

    @Builder
    public static class PromptLoadRequest {
        private @Nonnull String promptSlug;
        private @Nullable String projectName;
        private @Nullable String version;
        private @Nonnull @Builder.Default Map<String, String> defaults = Map.of();

        public static class PromptLoadRequestBuilder {
            public PromptLoadRequestBuilder defaults(String... keyValuePairDefaults) {
                throw new RuntimeException("TODO");
            }
        }
    }

    // TODO: what default values are allowed? Do we want to use an enum or is that too constrictive?
    public static class PromptLoadDefaultKeys {
        public static String MODEL = "model";
        public static String TEMPERATURE = "temperature";
    }
}
