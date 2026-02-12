package dev.braintrust.eval;

import lombok.Getter;
import lombok.SneakyThrows;

/** Results of all eval cases of an experiment. */
public class EvalResult {
    @Getter private final String experimentUrl;

    @SneakyThrows
    EvalResult(String experimentUrl) {
        this.experimentUrl = experimentUrl;
    }

    public String createReportString() {
        return "Experiment complete. View results in braintrust: %s".formatted(experimentUrl);
    }
}
