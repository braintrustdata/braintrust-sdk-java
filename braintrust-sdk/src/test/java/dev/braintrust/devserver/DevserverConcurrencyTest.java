package dev.braintrust.devserver;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.BraintrustUtils;
import dev.braintrust.TestHarness;
import dev.braintrust.eval.DatasetCase;
import dev.braintrust.eval.Score;
import dev.braintrust.eval.Scorer;
import dev.braintrust.eval.TaskResult;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;

/** Covers concurrent case execution on the devserver's playground path. */
@Slf4j
class DevserverConcurrencyTest {
    private static final int TEST_PORT = 8302;
    private static final String TEST_URL = "http://localhost:" + TEST_PORT;
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final int MAX_CONCURRENCY = 4;

    private static final String BLOCKING_EVAL = "concurrency-blocking-eval";
    private static final String SCORES_EVAL = "concurrency-scores-eval";
    private static final String SLOW_EVAL = "concurrency-slow-eval";
    private static final String BROKEN_SCORER_EVAL = "concurrency-broken-scorer-eval";
    private static final String BROKEN_TASK_FALLBACK_EVAL = "concurrency-broken-task-fallback-eval";

    private static final BraintrustUtils.Parent PLAYGROUND_PARENT =
            new BraintrustUtils.Parent("playground_id", "ceea7422-3507-4d1c-a5f7-7acf41d9fac2");

    private static Devserver server;
    private static Thread serverThread;
    private static TestHarness testHarness;

    // Observed concurrency of the blocking eval's task, reset per test.
    private static final AtomicInteger inFlight = new AtomicInteger();
    private static final AtomicInteger peak = new AtomicInteger();
    // When set, every task counts down and then waits for all of them to arrive.
    private static volatile CountDownLatch gate;
    // Cases of SLOW_EVAL that started, reset per test.
    private static final AtomicInteger slowTasksRun = new AtomicInteger();

    @BeforeAll
    static void setUp() throws Exception {
        testHarness = TestHarness.setup();

        var blockingEval =
                RemoteEval.<String, String>builder()
                        .name(BLOCKING_EVAL)
                        .taskFunction(
                                input -> {
                                    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                                    try {
                                        var g = gate;
                                        if (g != null) {
                                            g.countDown();
                                            assertTrue(
                                                    g.await(10, TimeUnit.SECONDS),
                                                    "tasks did not run concurrently");
                                        } else {
                                            Thread.sleep(25);
                                        }
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        throw new RuntimeException(e);
                                    } finally {
                                        inFlight.decrementAndGet();
                                    }
                                    return "ok";
                                })
                        .scorer(Scorer.of("static_scorer", (expected, result) -> 1.0))
                        .build();

        // Each case's input *is* its score, so a lost score in the shared aggregate changes the
        // reported average. With identical scores per case the race would be invisible.
        var scoresEval =
                RemoteEval.<String, String>builder()
                        .name(SCORES_EVAL)
                        .taskFunction(input -> input)
                        .scorer(
                                Scorer.of(
                                        "varying_scorer",
                                        (expected, result) -> Double.parseDouble(result)))
                        .build();

        var brokenScorerEval =
                RemoteEval.<String, String>builder()
                        .name(BROKEN_SCORER_EVAL)
                        .taskFunction(input -> input)
                        .scorer(
                                new Scorer<>() {
                                    @Override
                                    public String getName() {
                                        return "broken_scorer";
                                    }

                                    @Override
                                    public List<Score> score(TaskResult<String, String> result) {
                                        throw new IllegalStateException("scorer failed");
                                    }

                                    @Override
                                    public List<Score> scoreForScorerException(
                                            Exception scorerException,
                                            TaskResult<String, String> result) {
                                        throw new IllegalStateException("fallback failed");
                                    }
                                })
                        .build();

        var brokenTaskFallbackEval =
                RemoteEval.<String, String>builder()
                        .name(BROKEN_TASK_FALLBACK_EVAL)
                        .taskFunction(
                                input -> {
                                    throw new IllegalStateException("task failed");
                                })
                        .scorer(
                                new Scorer<>() {
                                    @Override
                                    public String getName() {
                                        return "broken_task_fallback";
                                    }

                                    @Override
                                    public List<Score> score(TaskResult<String, String> result) {
                                        return List.of();
                                    }

                                    @Override
                                    public List<Score> scoreForTaskException(
                                            Exception taskException,
                                            DatasetCase<String, String> datasetCase) {
                                        throw new IllegalStateException("task fallback failed");
                                    }
                                })
                        .build();

        // Slow enough that a client can disconnect while cases are still queued up behind it.
        var slowEval =
                RemoteEval.<String, String>builder()
                        .name(SLOW_EVAL)
                        .taskFunction(
                                input -> {
                                    slowTasksRun.incrementAndGet();
                                    try {
                                        Thread.sleep(100);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        throw new RuntimeException(e);
                                    }
                                    return "ok";
                                })
                        .scorer(Scorer.of("static_scorer", (expected, result) -> 1.0))
                        .build();

        server =
                Devserver.builder()
                        .config(testHarness.braintrust().config())
                        .registerEval(blockingEval)
                        .registerEval(scoresEval)
                        .registerEval(slowEval)
                        .registerEval(brokenScorerEval)
                        .registerEval(brokenTaskFallbackEval)
                        .maxConcurrency(MAX_CONCURRENCY)
                        .host("localhost")
                        .port(TEST_PORT)
                        .build();

        serverThread =
                new Thread(
                        () -> {
                            try {
                                server.start();
                            } catch (Exception e) {
                                log.error("unable to start dev server", e);
                            }
                        });
        serverThread.start();
        Thread.sleep(1000);
    }

    @AfterAll
    @SneakyThrows
    static void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (serverThread != null) {
            serverThread.join(30_000);
            if (serverThread.isAlive()) {
                serverThread.interrupt();
            }
        }
    }

    @BeforeEach
    void resetCounters() {
        inFlight.set(0);
        peak.set(0);
        slowTasksRun.set(0);
        gate = null;
    }

    @Test
    void casesRunConcurrently() throws Exception {
        // Every task blocks until all MAX_CONCURRENCY of them have arrived, so the run can only
        // complete if they genuinely execute at the same time.
        gate = new CountDownLatch(MAX_CONCURRENCY);

        var events = runPlaygroundEval(BLOCKING_EVAL, inputs(MAX_CONCURRENCY));

        assertEquals(
                MAX_CONCURRENCY,
                events.stream().filter(e -> "progress".equals(e.get("event"))).count(),
                "every case should report progress");
        assertEquals(MAX_CONCURRENCY, peak.get(), "expected all cases in flight at once");
    }

    @Test
    void maxConcurrencyIsRespected() throws Exception {
        int caseCount = MAX_CONCURRENCY * 3;
        var events = runPlaygroundEval(BLOCKING_EVAL, inputs(caseCount));

        assertEquals(
                caseCount, events.stream().filter(e -> "progress".equals(e.get("event"))).count());
        assertTrue(peak.get() > 1, "expected concurrent execution, peak was " + peak.get());
        assertTrue(
                peak.get() <= MAX_CONCURRENCY, "exceeded maxConcurrency, peak was " + peak.get());
    }

    @Test
    void scoreAggregationIsCorrectUnderConcurrency() throws Exception {
        // Every case scores differently, so the summary average is only right if each concurrent
        // case's score made it into the shared aggregate. The case count is high to make the
        // narrow computeIfAbsent-then-add window land reliably.
        int caseCount = 300;
        var caseInputs = new ArrayList<String>();
        double expectedSum = 0.0;
        for (int i = 1; i <= caseCount; i++) {
            double value = i / (double) caseCount;
            expectedSum += value;
            caseInputs.add(String.valueOf(value));
        }
        double expectedAverage = expectedSum / caseCount;

        var events = runPlaygroundEval(SCORES_EVAL, caseInputs);

        var summary =
                events.stream()
                        .filter(e -> "summary".equals(e.get("event")))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no summary event"));
        JsonNode scores = JSON_MAPPER.readTree(summary.get("data")).get("scores");
        assertNotNull(scores.get("varying_scorer"), "summary should carry the scorer's average");
        assertEquals(
                expectedAverage,
                scores.get("varying_scorer").get("score").asDouble(),
                1e-9,
                "every case's score must be counted exactly once");
    }

    @Test
    void scorerFallbackFailureSendsErrorInsteadOfSuccessfulCompletion() throws Exception {
        var events = runPlaygroundEval(BROKEN_SCORER_EVAL, inputs(4));

        assertTrue(
                events.stream().anyMatch(e -> "error".equals(e.get("event"))),
                "a fatal scorer failure should be reported to the client");
        assertFalse(
                events.stream()
                        .anyMatch(
                                e ->
                                        "summary".equals(e.get("event"))
                                                || "done".equals(e.get("event"))),
                "a failed run must not report successful completion");
    }

    @Test
    void taskFallbackFailureSendsErrorInsteadOfSuccessfulCompletion() throws Exception {
        var events = runPlaygroundEval(BROKEN_TASK_FALLBACK_EVAL, inputs(4));

        assertTrue(
                events.stream().anyMatch(e -> "error".equals(e.get("event"))),
                "a fatal task fallback failure should be reported to the client");
        assertFalse(
                events.stream()
                        .anyMatch(
                                e ->
                                        "summary".equals(e.get("event"))
                                                || "done".equals(e.get("event"))),
                "a failed run must not report successful completion");
    }

    @Test
    void clientDisconnectAbortsTheRun() throws Exception {
        // The playground streams per-case progress over SSE. Once the client is gone there is
        // nobody to stream to, so the remaining cases must not be evaluated.
        int caseCount = 200;
        var conn = postPlaygroundEval(SLOW_EVAL, inputs(caseCount));
        assertEquals(200, conn.getResponseCode());

        // Read far enough to know the run is under way, then drop the connection.
        try (var reader =
                new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event: progress")) {
                    break;
                }
            }
            assertNotNull(line, "expected at least one progress event before disconnecting");
        }
        conn.disconnect();

        // Wait for the server to stop starting cases: the count holding still means the run ended.
        int settled = slowTasksRun.get();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Thread.sleep(500);
            int current = slowTasksRun.get();
            if (current == settled) {
                break;
            }
            settled = current;
        }
        assertTrue(
                settled < caseCount,
                "every case ran despite the client disconnecting: " + settled + "/" + caseCount);
    }

    private static List<String> inputs(int count) {
        var list = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            list.add("case-" + i);
        }
        return list;
    }

    /** POSTs a streaming playground eval with inline cases and returns the parsed SSE events. */
    private List<Map<String, String>> runPlaygroundEval(String evalName, List<String> caseInputs)
            throws Exception {
        var conn = postPlaygroundEval(evalName, caseInputs);
        assertEquals(200, conn.getResponseCode());
        return readSSEEvents(conn);
    }

    /** POSTs a streaming playground eval with inline cases, leaving the response unread. */
    private HttpURLConnection postPlaygroundEval(String evalName, List<String> caseInputs)
            throws Exception {
        var evalRequest = new EvalRequest();
        evalRequest.setName(evalName);
        evalRequest.setStream(true);
        evalRequest.setParent(
                Map.of(
                        "object_type", PLAYGROUND_PARENT.type(),
                        "object_id", PLAYGROUND_PARENT.id()));

        var dataSpec = new EvalRequest.DataSpec();
        var cases = new ArrayList<EvalRequest.EvalCaseData>();
        for (String input : caseInputs) {
            var c = new EvalRequest.EvalCaseData();
            c.setInput(input);
            c.setExpected(input);
            cases.add(c);
        }
        dataSpec.setData(cases);
        evalRequest.setData(dataSpec);

        String requestBody = JSON_MAPPER.writeValueAsString(evalRequest);

        HttpURLConnection conn =
                (HttpURLConnection) new URI(TEST_URL + "/eval").toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("x-bt-auth-token", testHarness.braintrustApiKey());
        conn.setRequestProperty("x-bt-project-id", TestHarness.defaultProjectId());
        conn.setRequestProperty("x-bt-org-name", TestHarness.defaultOrgName());
        conn.setDoOutput(true);
        conn.getOutputStream().write(requestBody.getBytes(StandardCharsets.UTF_8));
        conn.getOutputStream().flush();
        return conn;
    }

    private List<Map<String, String>> readSSEEvents(HttpURLConnection conn) throws Exception {
        var events = new ArrayList<Map<String, String>>();
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            String currentEvent = null;
            var currentData = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event: ")) {
                    currentEvent = line.substring(7);
                } else if (line.startsWith("data: ")) {
                    currentData.append(line.substring(6));
                } else if (line.isEmpty() && currentEvent != null) {
                    events.add(Map.of("event", currentEvent, "data", currentData.toString()));
                    currentEvent = null;
                    currentData = new StringBuilder();
                }
            }
        }
        return events;
    }
}
