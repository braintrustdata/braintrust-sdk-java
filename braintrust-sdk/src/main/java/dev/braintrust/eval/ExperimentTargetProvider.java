package dev.braintrust.eval;

import dev.braintrust.BraintrustUtils;
import dev.braintrust.openapi.api.ExperimentsApi;
import dev.braintrust.openapi.model.CreateExperiment;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Default {@link EvalTargetProvider}: creates a Braintrust experiment and targets spans at it via
 * an {@code experiment_id:} parent.
 */
public final class ExperimentTargetProvider implements EvalTargetProvider {
    private final boolean ensureNew;

    public ExperimentTargetProvider() {
        this(false);
    }

    /**
     * @param ensureNew when true, sets {@code ensure_new} on the create-experiment request so a new
     *     experiment is always created even if one with the same name already exists. Useful for
     *     repeated remote/UI runs that should each produce a distinct experiment.
     */
    public ExperimentTargetProvider(boolean ensureNew) {
        this.ensureNew = ensureNew;
    }

    @Override
    @Nonnull
    public EvalRunInfo create(@Nonnull Context ctx) {
        var createExperiment =
                new CreateExperiment().projectId(ctx.project().getId()).name(ctx.experimentName());
        if (ensureNew) {
            createExperiment.ensureNew(true);
        }
        if (!ctx.tags().isEmpty()) {
            createExperiment.tags(ctx.tags());
        }
        if (!ctx.metadata().isEmpty()) {
            createExperiment.metadata(ctx.metadata());
        }
        ctx.datasetId().ifPresent(id -> createExperiment.datasetId(UUID.fromString(id)));
        ctx.datasetVersion().ifPresent(createExperiment::datasetVersion);

        var experiment = new ExperimentsApi(ctx.client()).postExperiment(createExperiment);
        var experimentId = experiment.getId().toString();
        // Use the experiment's actual name from the response, not the requested name: with
        // ensure_new the backend may dedupe the name (e.g. "foo" -> "foo-2f8ca776"), and the URL
        // must point at the real, created experiment.
        var experimentName =
                experiment.getName() != null ? experiment.getName() : ctx.experimentName();
        var experimentUrl =
                "%s/experiments/%s"
                        .formatted(
                                BraintrustUtils.createProjectURI(
                                                ctx.config().appUrl(),
                                                ctx.orgInfo().name(),
                                                ctx.project().getName())
                                        .toASCIIString(),
                                experimentName);
        return new EvalRunInfo(
                new BraintrustUtils.Parent("experiment_id", experimentId),
                null,
                experimentId,
                experimentName,
                experimentUrl);
    }
}
