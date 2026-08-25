package dev.braintrust.sdkspecimpl;

import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.braintrust.TestHarness;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * JUnit 5 parametrized test runner for the cross-language LLM span specs.
 *
 * <p>Loads all YAML files from {@code btx/spec/llm_span/}, executes them in-process using the
 * Braintrust Java SDK, and validates the exported spans against the spec's {@code
 * expected_brainstore_spans} assertions.
 */
class LlmSpanSpecTest {

    // Initialized statically so they are available when specs() is called during
    // test discovery (which happens before any @Before* lifecycle methods run).
    private static final TestHarness HARNESS = TestHarness.setup();
    private static final SpecClientContext CTX = new SpecClientContext(HARNESS);
    private static final SpanFetcher SPAN_FETCHER = new SpanFetcher(HARNESS);

    static Stream<Arguments> specs() throws Exception {
        List<LlmSpanSpec> all = SpecLoader.loadAll();
        assertFalse(all.isEmpty(), "No specs found under btx/spec/llm_span/");

        // When -Pbtx.spec.filter=<glob> is passed, only pre-execute matching specs.
        // The filter is matched as a substring against each spec's display name, with '*'
        // acting as a wildcard. E.g. "openai" matches "openai/completions"; "openai/c*"
        // matches "openai/completions" but not "openai/tools".
        String filterProp = System.getProperty("btx.spec.filter");
        List<LlmSpanSpec> toExecute;
        if (filterProp != null) {
            List<Pattern> patterns =
                    Arrays.stream(filterProp.split(","))
                            .map(
                                    f ->
                                            Pattern.compile(
                                                    f.contains("*")
                                                            ? f.replace("*", ".*")
                                                            : ".*" + Pattern.quote(f) + ".*"))
                            .toList();
            toExecute =
                    all.stream()
                            .filter(
                                    s ->
                                            patterns.stream()
                                                    .anyMatch(
                                                            p ->
                                                                    p.matcher(s.displayName())
                                                                            .matches()))
                            .toList();
        } else {
            toExecute = all;
        }

        final AtomicInteger totalExpectedSpans = new AtomicInteger(0);
        // In replay mode, execution must be sequential: cassettes for identical request bodies
        // (e.g. the same spec executed for multiple clients) are recorded as stateful WireMock
        // scenarios,
        // and concurrent requests race on the scenario state, causing intermittent
        // "Request was not matched" failures.
        boolean isReplay = TestHarness.getVcrMode().equals(dev.braintrust.VCR.VcrMode.REPLAY);
        var pool = new ForkJoinPool(isReplay ? 1 : 3);
        var results =
                pool.submit(
                                () ->
                                        toExecute.parallelStream()
                                                .map(
                                                        spec -> {
                                                            // Uncovered specs are not executed;
                                                            // runSpec fails them with an
                                                            // actionable message instead.
                                                            if (SpecClientRegistry
                                                                    .UNSUPPORTED_CLIENT_ID
                                                                    .equals(spec.client())) {
                                                                return Arguments.of(spec, "");
                                                            }
                                                            try {
                                                                var rootSpanId =
                                                                        SpecClientRegistry.execute(
                                                                                spec, CTX);
                                                                totalExpectedSpans.addAndGet(
                                                                        countExpectedSpans(
                                                                                        spec
                                                                                                .expectedBrainstoreSpans())
                                                                                + 1);
                                                                return Arguments.of(
                                                                        spec, rootSpanId);
                                                            } catch (Exception e) {
                                                                throw new RuntimeException(e);
                                                            }
                                                        })
                                                .toList())
                        .get();
        HARNESS.awaitExportedSpans(totalExpectedSpans.get());
        return results.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("specs")
    void runSpec(LlmSpanSpec spec, String rootSpanId) throws Exception {
        if (SpecClientRegistry.UNSUPPORTED_CLIENT_ID.equals(spec.client())) {
            org.junit.jupiter.api.Assertions.fail(SpecClientRegistry.unsupportedSpecMessage(spec));
        }
        int expectedSpanCount = countExpectedSpans(spec.expectedBrainstoreSpans());
        List<Map<String, Object>> brainstoreSpans =
                SPAN_FETCHER.fetch(rootSpanId, expectedSpanCount);
        SpanValidator.validate(brainstoreSpans, spec.expectedBrainstoreSpans(), spec.displayName());
        // Provider-independent token invariants, applied to every LLM span this spec produced.
        // These relate metrics to each other, which per-field YAML assertions cannot express, so
        // running them here gives every spec in the suite token-accounting coverage for free.
        TokenAccountingSpec.assertSpanTree(brainstoreSpans, spec.displayName());
    }

    /**
     * Total number of spans a spec expects, counting nested {@code child_spans} recursively. Used
     * to size the {@code awaitExportedSpans} gate so it doesn't return before child (e.g. tool)
     * spans have flushed.
     */
    @SuppressWarnings("unchecked")
    private static int countExpectedSpans(List<Map<String, Object>> spans) {
        int total = 0;
        for (Map<String, Object> span : spans) {
            total += 1;
            Object children = span.get("child_spans");
            if (children instanceof List<?> l) {
                total += countExpectedSpans((List<Map<String, Object>>) l);
            }
        }
        return total;
    }
}
