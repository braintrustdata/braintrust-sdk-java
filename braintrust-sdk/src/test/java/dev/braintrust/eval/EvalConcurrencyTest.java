package dev.braintrust.eval;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.TestHarness;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Covers concurrent case execution and the run-state exposed on {@link EvalResult}. */
public class EvalConcurrencyTest {
    private TestHarness testHarness;

    @BeforeEach
    void beforeEach() {
        testHarness = TestHarness.setup();
    }

    private DatasetCase<String, String>[] cases(int n) {
        @SuppressWarnings("unchecked")
        DatasetCase<String, String>[] cases = new DatasetCase[n];
        for (int i = 0; i < n; i++) {
            cases[i] = DatasetCase.of("input-" + i, "fruit");
        }
        return cases;
    }

    @Test
    @SneakyThrows
    public void casesRunConcurrently() {
        int caseCount = 10;
        // Every task blocks until all of them have arrived. This can only complete if the tasks
        // genuinely run at the same time.
        var allArrived = new CountDownLatch(caseCount);
        var peak = new AtomicInteger();
        var inFlight = new AtomicInteger();

        var eval =
                testHarness
                        .braintrust()
                        .<String, String>evalBuilder()
                        .name("concurrency-test")
                        .cases(cases(caseCount))
                        .maxConcurrency(caseCount)
                        .taskFunction(
                                input -> {
                                    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                                    allArrived.countDown();
                                    try {
                                        assertTrue(
                                                allArrived.await(10, TimeUnit.SECONDS),
                                                "tasks did not run concurrently");
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        throw new RuntimeException(e);
                                    } finally {
                                        inFlight.decrementAndGet();
                                    }
                                    return "fruit";
                                })
                        .scorers(Scorer.of("s", r -> "fruit".equals(r) ? 1.0 : 0.0))
                        .build();

        var result = eval.run();
        assertEquals(caseCount, peak.get(), "expected all cases in flight at once");
        assertTrue(result.isDone());
        assertEquals(caseCount, result.getCasesExecuted());
        assertEquals(EvalResult.Status.COMPLETE, result.getStatus());
        assertTrue(result.getAbortCause().isEmpty());
    }

    @Test
    @SneakyThrows
    public void maxConcurrencyIsRespected() {
        int caseCount = 12;
        int limit = 3;
        var peak = new AtomicInteger();
        var inFlight = new AtomicInteger();

        var eval =
                testHarness
                        .braintrust()
                        .<String, String>evalBuilder()
                        .name("max-concurrency-test")
                        .cases(cases(caseCount))
                        .maxConcurrency(limit)
                        .taskFunction(
                                input -> {
                                    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                                    try {
                                        Thread.sleep(25);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    inFlight.decrementAndGet();
                                    return "fruit";
                                })
                        .scorers(Scorer.of("s", r -> 1.0))
                        .build();

        var result = eval.run();
        assertTrue(peak.get() > 1, "expected concurrent execution, peak was " + peak.get());
        assertTrue(peak.get() <= limit, "exceeded maxConcurrency, peak was " + peak.get());
        assertEquals(caseCount, result.getCasesExecuted());
    }

    @Test
    @SneakyThrows
    public void serialWhenMaxConcurrencyIsOne() {
        var peak = new AtomicInteger();
        var inFlight = new AtomicInteger();
        var eval =
                testHarness
                        .braintrust()
                        .<String, String>evalBuilder()
                        .name("serial-test")
                        .cases(cases(6))
                        .maxConcurrency(1)
                        .taskFunction(
                                input -> {
                                    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                                    try {
                                        Thread.sleep(10);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    inFlight.decrementAndGet();
                                    return "fruit";
                                })
                        .scorers(Scorer.of("s", r -> 1.0))
                        .build();
        eval.run();
        assertEquals(1, peak.get(), "maxConcurrency(1) must evaluate cases one at a time");
    }

    @Test
    @SneakyThrows
    public void startReturnsBeforeCompletionAndAwaitBlocks() {
        var release = new CountDownLatch(1);
        var eval =
                testHarness
                        .braintrust()
                        .<String, String>evalBuilder()
                        .name("start-test")
                        .cases(cases(4))
                        .maxConcurrency(4)
                        .taskFunction(
                                input -> {
                                    try {
                                        assertTrue(release.await(10, TimeUnit.SECONDS));
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return "fruit";
                                })
                        .scorers(Scorer.of("s", r -> 1.0))
                        .build();

        var result = eval.start();
        // The experiment link is available immediately, while cases are still running.
        assertNotNull(result.getExperimentUrl());
        assertFalse(result.isDone());
        assertEquals(EvalResult.Status.RUNNING, result.getStatus());
        assertFalse(
                result.awaitCompletion(Duration.ofMillis(100)),
                "awaitCompletion should time out while cases are still running");
        assertTrue(result.createReportString().contains("running"));
        // The start timestamp is known immediately; the duration is not known until the run ends.
        assertFalse(result.getStartedAt().isAfter(Instant.now()));
        assertTrue(
                result.getDuration().isEmpty(), "duration must not be set while cases are running");

        release.countDown();
        result.awaitCompletion();
        assertTrue(result.isDone());
        assertEquals(EvalResult.Status.COMPLETE, result.getStatus());
        assertTrue(result.createReportString().contains("complete"));

        var duration = result.getDuration().orElseThrow();
        assertFalse(duration.isNegative());
        assertTrue(
                duration.compareTo(Duration.ofMillis(100)) >= 0,
                "the run was gated for at least 100ms, got " + duration);
        var report = result.createReportString();
        assertTrue(
                report.matches(".*complete in \\d+ minutes?, \\d+ seconds?:.*"),
                "report should carry a \"X minutes, Y seconds\" duration, got: " + report);
        assertEquals(4, result.getCasesExecuted());
        assertTrue(
                report.contains("4 case(s) executed"),
                "report should carry the executed case count, got: " + report);
    }

    @Test
    @SneakyThrows
    public void failingTaskIsContainedButJvmErrorAbortsRun() {
        var tasksRun = new AtomicInteger();
        var eval =
                testHarness
                        .braintrust()
                        .<String, String>evalBuilder()
                        .name("failing-task-test")
                        .cases(cases(6))
                        .maxConcurrency(1)
                        .taskFunction(
                                input -> {
                                    tasksRun.incrementAndGet();
                                    if ("input-1".equals(input)) {
                                        throw new RuntimeException("case failure");
                                    }
                                    if ("input-3".equals(input)) {
                                        throw new LinkageError("jvm failure");
                                    }
                                    return "fruit";
                                })
                        .scorers(Scorer.of("s", r -> 1.0))
                        .build();

        var result = eval.start();
        var thrown = assertThrows(LinkageError.class, result::awaitCompletion);
        assertEquals("jvm failure", thrown.getMessage());
        assertEquals(EvalResult.Status.ABORTED, result.getStatus());
        assertSame(thrown, result.getAbortCause().orElseThrow());
        assertTrue(
                tasksRun.get() >= 4,
                "the case after the ordinary failure should still run before the JVM error");
        assertTrue(
                tasksRun.get() < 6,
                "the run should stop after a JVM error, but every case ran: " + tasksRun.get());
    }

    /** An input Jackson cannot serialize, so writing the eval span's attributes throws. */
    public static final class Unserializable {
        public String getBoom() {
            throw new IllegalStateException("cannot serialize this input");
        }
    }

    @Test
    @SneakyThrows
    public void errorEscapingACaseAbortsTheRun() {
        // A throw from the eval's own machinery (here, serializing a case's input onto its span)
        // is not a contained case failure: the SDK could not record the case at all, and every
        // remaining case would hit the same problem, so the run aborts.
        var tasksRun = new AtomicInteger();
        @SuppressWarnings("unchecked")
        DatasetCase<Object, String>[] cases =
                new DatasetCase[] {
                    DatasetCase.of("ok-0", "fruit"),
                    DatasetCase.of(new Unserializable(), "fruit"),
                    DatasetCase.of("ok-2", "fruit"),
                    DatasetCase.of("ok-3", "fruit"),
                };
        var eval =
                testHarness
                        .braintrust()
                        .<Object, String>evalBuilder()
                        // Reuses the recorded experiment stub; only the case data differs.
                        .name("failing-task-test")
                        .cases(cases)
                        .maxConcurrency(1)
                        .taskFunction(
                                input -> {
                                    tasksRun.incrementAndGet();
                                    return "fruit";
                                })
                        .scorers(Scorer.of("s", r -> 1.0))
                        .build();

        var result = eval.start();
        // awaitCompletion rethrows the original error, unwrapped, whatever its type.
        var thrown = assertThrows(Throwable.class, result::awaitCompletion);
        assertEquals(EvalResult.Status.ABORTED, result.getStatus());
        var cause = result.getAbortCause().orElseThrow();
        assertSame(cause, thrown);
        assertTrue(
                Stream.iterate(cause, Objects::nonNull, Throwable::getCause)
                        .anyMatch(t -> String.valueOf(t.getMessage()).contains("cannot serialize")),
                "the run should abort on the serialization error, got: " + thrown);
        assertTrue(
                tasksRun.get() < 4,
                "the run should stop at the bad case, but every case ran: " + tasksRun.get());
        var report = result.createReportString();
        assertTrue(report.contains("aborted"), "report should say the run aborted, got: " + report);
    }

    @Test
    @SneakyThrows
    public void outOfRangeScoreAbortsTheRun() {
        // A scorer that scores out of range is broken rather than unlucky, so the run stops
        // instead of grinding through every remaining case.
        var tasksRun = new AtomicInteger();
        var eval =
                testHarness
                        .braintrust()
                        .<String, String>evalBuilder()
                        .name("bad-score-test")
                        .cases(cases(5))
                        .maxConcurrency(1)
                        .taskFunction(
                                input -> {
                                    tasksRun.incrementAndGet();
                                    return input;
                                })
                        .scorers(Scorer.of("s", r -> "input-1".equals(r.result()) ? 42.0 : 1.0))
                        .build();

        var thrown = assertThrows(RuntimeException.class, eval::run);
        assertTrue(
                thrown.getMessage().contains("score must be between 0 and 1"),
                "the scorer's error should surface to the caller, got: " + thrown.getMessage());
        assertTrue(
                tasksRun.get() < 5,
                "the run should stop early, but every case ran: " + tasksRun.get());
    }

    @Test
    @SneakyThrows
    public void throwingScorerFallbackAbortsTheRun() {
        // The scorer throws and its own fallback throws too: nothing can score this eval.
        Scorer<String, String> brokenScorer =
                new Scorer<>() {
                    @Override
                    public String getName() {
                        return "broken";
                    }

                    @Override
                    public java.util.List<Score> score(TaskResult<String, String> taskResult) {
                        throw new IllegalStateException("scorer boom");
                    }

                    @Override
                    public java.util.List<Score> scoreForScorerException(
                            Exception scorerException, TaskResult<String, String> taskResult) {
                        throw new IllegalStateException("fallback boom", scorerException);
                    }
                };
        var eval =
                testHarness
                        .braintrust()
                        .<String, String>evalBuilder()
                        // Reuses the recorded experiment name from the test above: cassettes are
                        // keyed on it, and this test cares about the scorer, not the experiment.
                        .name("bad-score-test")
                        .cases(cases(5))
                        .maxConcurrency(1)
                        .taskFunction(input -> input)
                        .scorers(brokenScorer)
                        .build();

        var thrown = assertThrows(IllegalStateException.class, eval::run);
        assertEquals("fallback boom", thrown.getMessage());
    }

    @Test
    @SneakyThrows
    public void callerSuppliedExecutorIsNotShutDown() {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            var eval =
                    testHarness
                            .braintrust()
                            .<String, String>evalBuilder()
                            .name("byo-executor-test")
                            .cases(cases(8))
                            .executor(executor)
                            .taskFunction(input -> "fruit")
                            .scorers(Scorer.of("s", r -> 1.0))
                            .build();
            var result = eval.run();
            assertEquals(8, result.getCasesExecuted());
            assertFalse(executor.isShutdown(), "SDK must not shut down a caller-supplied executor");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @SneakyThrows
    public void fallsBackToConfigDefaultMaxConcurrency() {
        // No maxConcurrency on the builder: the bound must come from the config.
        var harness = TestHarness.setup(cfg -> cfg.defaultMaxConcurrency(2));
        var peak = new AtomicInteger();
        var inFlight = new AtomicInteger();

        var eval =
                harness.braintrust()
                        .<String, String>evalBuilder()
                        .name("config-default-concurrency-test")
                        .cases(cases(8))
                        .taskFunction(
                                input -> {
                                    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                                    try {
                                        Thread.sleep(25);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    inFlight.decrementAndGet();
                                    return "fruit";
                                })
                        .scorers(Scorer.of("s", r -> 1.0))
                        .build();

        var result = eval.run();
        assertEquals(8, result.getCasesExecuted());
        assertTrue(peak.get() > 1, "expected concurrent execution, peak was " + peak.get());
        assertTrue(
                peak.get() <= 2,
                "config default of 2 should bound concurrency, peak was " + peak.get());
    }

    @Test
    @SneakyThrows
    public void suppliedExecutorGovernsConcurrency() {
        // The eval no longer bounds in-flight cases itself, so a supplied executor is the only
        // thing deciding how much runs at once — maxConcurrency must not sneak back in as a limit.
        var executor = Executors.newFixedThreadPool(2);
        var peak = new AtomicInteger();
        var inFlight = new AtomicInteger();
        try {
            var eval =
                    testHarness
                            .braintrust()
                            .<String, String>evalBuilder()
                            .name("supplied-executor-concurrency-test")
                            .cases(cases(12))
                            .maxConcurrency(8)
                            .executor(executor)
                            .taskFunction(
                                    input -> {
                                        peak.accumulateAndGet(
                                                inFlight.incrementAndGet(), Math::max);
                                        try {
                                            Thread.sleep(25);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                        inFlight.decrementAndGet();
                                        return "fruit";
                                    })
                            .scorers(Scorer.of("s", r -> 1.0))
                            .build();

            var result = eval.run();
            assertEquals(12, result.getCasesExecuted());
            assertTrue(peak.get() > 1, "expected concurrent execution, peak was " + peak.get());
            assertTrue(
                    peak.get() <= 2,
                    "the supplied 2-thread executor should bound concurrency, not"
                            + " maxConcurrency(8); peak was "
                            + peak.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @SneakyThrows
    public void coordinatorDoesNotRunAheadOfThePool() {
        // The default pool backpressures the coordinator: it must not drain the whole cursor into
        // the executor's queue while the only worker is stuck on the first case. This is what
        // regresses if the default pool is ever swapped back to an unbounded newFixedThreadPool.
        int caseCount = 20;
        var pulled = new AtomicInteger();
        var firstCaseStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        var eval =
                testHarness
                        .braintrust()
                        .<String, String>evalBuilder()
                        .name("backpressure-test")
                        .dataset(countingCursorDataset(cases(caseCount), pulled))
                        .maxConcurrency(1)
                        .taskFunction(
                                input -> {
                                    firstCaseStarted.countDown();
                                    try {
                                        release.await();
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return "fruit";
                                })
                        .scorers(Scorer.of("s", r -> 1.0))
                        .build();

        var result = eval.start();
        assertTrue(firstCaseStarted.await(10, TimeUnit.SECONDS), "first case never started");
        // Give the coordinator a chance to run ahead if it is going to.
        Thread.sleep(250);
        // The blocked case, plus at most one more sitting in the handoff. Anything beyond that
        // means the coordinator drained the cursor without waiting for the pool.
        assertTrue(
                pulled.get() <= 2,
                "coordinator ran ahead of the pool: pulled " + pulled.get() + " of " + caseCount);

        release.countDown();
        result.awaitCompletion();
        assertEquals(caseCount, result.getCasesExecuted());
        assertEquals(caseCount, pulled.get(), "every case should eventually be pulled");
    }

    /** Wraps a dataset so the test can see how far ahead of the executor the coordinator pulls. */
    private Dataset<String, String> countingCursorDataset(
            DatasetCase<String, String>[] cases, AtomicInteger pulled) {
        var delegate = Dataset.of(cases);
        return new Dataset<>() {
            @Override
            public Dataset.Cursor<DatasetCase<String, String>> openCursor() {
                var cursor = delegate.openCursor();
                return new Dataset.Cursor<>() {
                    @Override
                    public Optional<DatasetCase<String, String>> next() {
                        var next = cursor.next();
                        next.ifPresent(unused -> pulled.incrementAndGet());
                        return next;
                    }

                    @Override
                    public void close() {
                        cursor.close();
                    }

                    @Override
                    public Optional<String> version() {
                        return cursor.version();
                    }
                };
            }

            @Override
            public String id() {
                return delegate.id();
            }

            @Override
            public Optional<String> version() {
                return delegate.version();
            }
        };
    }

    @Test
    @SneakyThrows
    public void rejectsInvalidMaxConcurrency() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Eval.<String, String>builder().maxConcurrency(0));
    }
}
