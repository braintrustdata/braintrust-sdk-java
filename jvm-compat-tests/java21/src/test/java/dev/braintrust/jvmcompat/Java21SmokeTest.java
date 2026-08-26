package dev.braintrust.jvmcompat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.braintrust.Braintrust;
import dev.braintrust.BraintrustUtils;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class Java21SmokeTest {
    @Test
    void runsOnJava21() {
        assertEquals(
                21,
                Runtime.version().feature(),
                "expected the test JVM to be Java 21, got " + System.getProperty("java.version"));
        assertNotNull(Braintrust.class.getName());
    }

    @Test
    void usesVirtualThreadsOnJava21() throws Exception {
        ExecutorService executor = BraintrustUtils.newExecutor(10, "java-21-smoke-test-");
        try {
            var thread = executor.submit(Thread::currentThread).get(10, TimeUnit.SECONDS);
            assertTrue(
                    thread.isVirtual(),
                    "expected a virtual thread on Java 21, got "
                            + thread
                            + " "
                            + thread.getClass());
            assertTrue(
                    thread.getName().startsWith("java-21-smoke-test-"),
                    "expected the thread name prefix to be preserved, got " + thread.getName());
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Virtual threads must not quietly turn {@code maxSize} into "unbounded" — eval concurrency
     * depends on it to avoid hammering LLM providers.
     */
    @Test
    void virtualThreadExecutorStillCapsConcurrency() throws Exception {
        int maxSize = 4;
        ExecutorService executor = BraintrustUtils.newExecutor(maxSize, "java-21-bounded-");
        var inFlight = new AtomicInteger();
        var peak = new AtomicInteger();
        var release = new CountDownLatch(1);
        var done = new CountDownLatch(maxSize);
        try {
            // Saturate the executor: each task parks until we release it.
            for (int i = 0; i < maxSize; i++) {
                executor.execute(
                        () -> {
                            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                            try {
                                release.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                inFlight.decrementAndGet();
                                done.countDown();
                            }
                        });
            }

            // One more submit must block until a permit frees up rather than spawning task #5.
            var submitted = new CountDownLatch(1);
            var submitter =
                    Thread.ofPlatform()
                            .daemon()
                            .start(
                                    () -> {
                                        executor.execute(() -> {});
                                        submitted.countDown();
                                    });
            assertTrue(
                    !submitted.await(500, TimeUnit.MILLISECONDS),
                    "submitting past maxSize should have backpressured the caller");

            release.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "tasks did not finish");
            assertTrue(submitted.await(10, TimeUnit.SECONDS), "blocked submit never went through");
            submitter.join();

            assertEquals(maxSize, peak.get(), "concurrency exceeded maxSize");
        } finally {
            executor.shutdown();
        }
    }
}
