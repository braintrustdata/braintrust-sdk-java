package dev.braintrust.eval;

import dev.braintrust.BraintrustUtils;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolved target for an eval run, produced by an {@link EvalTargetProvider} and handed to every
 * {@link EvalListener} via {@link EvalListener#createRunListener(EvalRunInfo)}.
 *
 * @param parent the braintrust parent for all spans (e.g. {@code experiment_id:…} or {@code
 *     playground_id:…})
 * @param generation optional generation identifier woven into span attributes (playground)
 * @param experimentId the experiment id, when running against an experiment; otherwise null
 * @param experimentName the experiment's actual (possibly deduped) name, when running against an
 *     experiment; otherwise null
 * @param experimentUrl the experiment URL, when applicable; otherwise null
 * @param tracingSupported whether a {@link dev.braintrust.trace.BrainstoreTrace} can be built for
 *     traced scorers/classifiers (true only in experiment mode)
 */
public record EvalRunInfo(
        @Nonnull BraintrustUtils.Parent parent,
        @Nullable String generation,
        @Nullable String experimentId,
        @Nullable String experimentName,
        @Nullable String experimentUrl,
        boolean tracingSupported) {}
