package dev.braintrust.eval;

import dev.braintrust.api.BraintrustOpenApiClient;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.openapi.model.Project;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Resolves the {@link EvalRunInfo target} for an eval run. The default implementation creates a
 * Braintrust experiment (see {@link ExperimentTargetProvider}); alternative implementations (e.g.
 * the devserver/playground) can supply a different parent and skip experiment creation.
 */
public interface EvalTargetProvider {
    @Nonnull
    EvalRunInfo create(@Nonnull Context ctx);

    /**
     * Inputs available when resolving the eval target, gathered at the start of {@link Eval#run()}.
     */
    record Context(
            @Nonnull BraintrustConfig config,
            @Nonnull BraintrustOpenApiClient client,
            @Nonnull Project project,
            @Nonnull BraintrustOpenApiClient.OrgInfo orgInfo,
            @Nonnull String experimentName,
            @Nonnull List<String> tags,
            @Nonnull Map<String, Object> metadata,
            @Nonnull Optional<String> datasetId,
            @Nonnull Optional<String> datasetVersion) {}
}
