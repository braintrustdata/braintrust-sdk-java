package dev.braintrust.eval;

import javax.annotation.Nullable;
import lombok.Getter;
import lombok.SneakyThrows;

/** Results of all eval cases of an experiment. */
public class EvalResult {
    @Getter private final @Nullable String experimentId;
    @Getter private final @Nullable String experimentName;
    @Getter private final String experimentUrl;

    @SneakyThrows
    EvalResult(
            @Nullable String experimentId, @Nullable String experimentName, String experimentUrl) {
        this.experimentId = experimentId;
        this.experimentName = experimentName;
        this.experimentUrl = experimentUrl;
    }

    public String createReportString() {
        return "Experiment complete. View results in braintrust: %s".formatted(experimentUrl);
    }
}
