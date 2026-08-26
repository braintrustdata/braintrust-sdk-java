package dev.braintrust.eval;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.SneakyThrows;

/**
 * Results of all eval cases of an experiment.
 *
 * <p>The experiment identifiers are available as soon as this object exists, but the run itself may
 * still be in progress: {@link Eval#start()} hands back a result whose cases are still being
 * evaluated, so that callers can surface the experiment link immediately. Use {@link #isDone()} and
 * {@link #awaitCompletion()} to observe the run. A result returned by {@link Eval#run()} is always
 * already complete.
 */
public class EvalResult {
    @Getter private final @Nullable String experimentId;
    @Getter private final @Nullable String experimentName;
    @Getter private final String experimentUrl;
    private final @Nonnull RunState state;

    /**
     * Lifecycle of an eval run.
     *
     * <p>A failing case does not abort the run: a task that throws is recorded on its span and
     * handed to {@link Scorer#scoreForTaskException}, and the run still reaches {@link #COMPLETE}.
     * Only an error that escapes the eval's own handling aborts, because such an error means the
     * eval itself is broken rather than one of its cases.
     */
    public enum Status {
        /** Cases are still being evaluated. */
        RUNNING,
        /** Every case in the dataset was evaluated. */
        COMPLETE,
        /** The run stopped early because an error escaped; see {@link #getAbortCause()}. */
        ABORTED,
    }

    EvalResult(
            @Nullable String experimentId,
            @Nullable String experimentName,
            String experimentUrl,
            @Nonnull RunState state) {
        this.experimentId = experimentId;
        this.experimentName = experimentName;
        this.experimentUrl = experimentUrl;
        this.state = state;
    }

    /** where the run is in its lifecycle */
    public Status getStatus() {
        return state.getStatus();
    }

    /** true if the eval has completed execution, whether it finished its cases or aborted */
    public boolean isDone() {
        return state.isDone();
    }

    /**
     * the error that aborted the run, or empty if the run has not aborted — always empty while the
     * run is still {@link Status#RUNNING}, even if an abort is already underway
     */
    public Optional<Throwable> getAbortCause() {
        return state.getAbortCause();
    }

    /**
     * wait until the eval finishes running, or return right away if already done.
     *
     * <p>If the run aborted with an error, that error is rethrown here.
     */
    public void awaitCompletion() {
        state.await();
    }

    /**
     * wait until the eval finishes running, or return right away if already done, giving up after
     * {@code timeout}.
     *
     * @return true if the eval completed, false if the timeout elapsed while it was still running
     */
    public boolean awaitCompletion(@Nonnull Duration timeout) {
        return state.await(timeout);
    }

    /** when the eval started running */
    public Instant getStartedAt() {
        return state.getStartedAt();
    }

    /**
     * how long the eval took to run, or empty while it is still running — the duration is not known
     * until every case has finished
     */
    public Optional<Duration> getDuration() {
        return state.getDuration();
    }

    /** number of cases that have finished evaluating so far, whether they succeeded or failed */
    public int getCasesExecuted() {
        return state.getCasesExecuted();
    }

    public String createReportString() {
        var executed = getCasesExecuted();
        if (!isDone()) {
            return "Experiment is running (%d case(s) executed so far). View live results in braintrust: %s"
                    .formatted(executed, experimentUrl);
        }
        var elapsed = getDuration().map(EvalResult::formatDuration).orElse("an unknown duration");
        var abortCause = getAbortCause().orElse(null);
        if (abortCause != null) {
            return "Experiment aborted after %s and %d case(s): %s. View partial results in braintrust: %s"
                    .formatted(elapsed, executed, abortCause, experimentUrl);
        }
        return "Experiment complete in %s: %d case(s) executed. View results in braintrust: %s"
                .formatted(elapsed, executed, experimentUrl);
    }

    /** Renders a duration as "X minutes, Y seconds". Hours and beyond roll up into the minutes. */
    private static String formatDuration(Duration duration) {
        var minutes = duration.toMinutes();
        var seconds = duration.toSecondsPart();
        return "%d %s, %d %s"
                .formatted(
                        minutes,
                        minutes == 1 ? "minute" : "minutes",
                        seconds,
                        seconds == 1 ? "second" : "seconds");
    }

    /**
     * Mutable execution state of a single eval run, shared between the {@link Eval} executing the
     * run and the enclosing {@link EvalResult}, which exposes a read-only view of it.
     *
     * <p>An {@link Eval} may hand back its {@link EvalResult} before the run has finished (see
     * {@link Eval#start()}), so this state is written by the eval's coordinator and worker threads
     * while the caller reads it. All members are thread-safe.
     */
    static final class RunState {
        private final CountDownLatch completed = new CountDownLatch(1);
        private final AtomicInteger casesExecuted = new AtomicInteger();

        /**
         * The first error that aborted the run, or null if none has. Set before {@link #completed}
         * counts down, so a done run's status never observes a stale null.
         */
        private final AtomicReference<Throwable> abortCause = new AtomicReference<>();

        /** When the run began. Wall-clock, for reporting only. */
        private final Instant startedAt = Instant.now();

        /**
         * Monotonic counterpart to {@link #startedAt}, used to measure {@link #duration} — a wall
         * clock can step backwards mid-run and yield a negative elapsed time.
         */
        private final long startedAtNanos = System.nanoTime();

        /** How long the run took, set once by {@link #complete}. Null while it is still running. */
        private final AtomicReference<Duration> duration = new AtomicReference<>();

        /** Returns a state that is already complete, for callers that never ran anything. */
        static RunState alreadyComplete() {
            var state = new RunState();
            state.complete();
            return state;
        }

        /**
         * Records an error fatal enough to abort the whole run. The first error wins; the
         * coordinator stops submitting cases, cases that have not started skip themselves, and the
         * run completes as {@link Status#ABORTED} with this error.
         */
        void abort(Throwable error) {
            abortCause.compareAndSet(null, error);
        }

        /**
         * true once {@link #abort} has been called and the run is winding down. Distinct from
         * {@link Status#ABORTED}, which the run only reaches once it has actually finished.
         */
        boolean isAborting() {
            return abortCause.get() != null;
        }

        Status getStatus() {
            if (!isDone()) {
                return Status.RUNNING;
            }
            return isAborting() ? Status.ABORTED : Status.COMPLETE;
        }

        boolean isDone() {
            return completed.getCount() == 0;
        }

        /** The abort cause, exposed only once the run is done so that it agrees with the status. */
        Optional<Throwable> getAbortCause() {
            return isDone() ? Optional.ofNullable(abortCauseOrNull()) : Optional.empty();
        }

        /**
         * The abort cause whether or not the run has finished, for the eval's own bookkeeping —
         * callers observing the run should use {@link #getAbortCause()}.
         */
        @Nullable
        Throwable abortCauseOrNull() {
            return abortCause.get();
        }

        /**
         * Blocks until the run completes. If the run aborted, the error that aborted it is rethrown
         * here.
         */
        void await() {
            try {
                completed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted while awaiting eval completion", e);
            }
            throwIfAborted();
        }

        /**
         * Blocks until the run completes or the timeout elapses.
         *
         * @return true if the run completed, false if the timeout elapsed first
         */
        boolean await(Duration timeout) {
            final boolean done;
            try {
                done = completed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted while awaiting eval completion", e);
            }
            if (done) {
                throwIfAborted();
            }
            return done;
        }

        @SneakyThrows // not so sneaky, really
        private void throwIfAborted() {
            var t = abortCause.get();
            if (t == null) {
                return;
            }
            throw t;
        }

        /**
         * Marks the run finished, as {@link Status#ABORTED} if {@link #abort} was called at any
         * point during it and {@link Status#COMPLETE} otherwise.
         */
        void complete() {
            // Set before counting down so that isDone() never observes a run without a duration.
            duration.compareAndSet(null, Duration.ofNanos(System.nanoTime() - startedAtNanos));
            completed.countDown();
        }

        /** Counts a case that finished evaluating, whether or not it produced scores. */
        void caseExecuted() {
            casesExecuted.incrementAndGet();
        }

        Instant getStartedAt() {
            return startedAt;
        }

        Optional<Duration> getDuration() {
            return Optional.ofNullable(duration.get());
        }

        int getCasesExecuted() {
            return casesExecuted.get();
        }
    }
}
