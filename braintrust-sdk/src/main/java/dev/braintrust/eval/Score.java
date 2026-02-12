package dev.braintrust.eval;

/** Individual metric value assigned by a scorer. */
public record Score(
        /**
         * Name of the metric being scored.
         *
         * <p>This does not have to be the same as the scorer name, but it often will be.
         */
        String name,
        /**
         * Numeric representation of how well the task performed.
         *
         * <p>Must be between 0.0 (inclusive) and 1.0 (inclusive)
         *
         * <p>0 is completely incorrect. 1 is completely correct.
         */
        double value) {}
