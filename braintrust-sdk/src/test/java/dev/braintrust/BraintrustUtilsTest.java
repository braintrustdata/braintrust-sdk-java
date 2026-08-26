package dev.braintrust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class BraintrustUtilsTest {
    @Test
    public void testBuildProjectUri() {
        assertEquals(
                URI.create("http://someserver:3009/app/some%20org/p/some%20project"),
                BraintrustUtils.createProjectURI(
                        "http://someserver:3009/", "some org", "some project"));
    }

    @Test
    void testParseParent() {
        var parsed1 = BraintrustUtils.parseParent("experiment_id:abc123");
        assertEquals("experiment_id", parsed1.type());
        assertEquals("abc123", parsed1.id());

        var parsed2 = BraintrustUtils.parseParent("project_name:my-project");
        assertEquals("project_name", parsed2.type());
        assertEquals("my-project", parsed2.id());

        assertThrows(
                Exception.class,
                () -> BraintrustUtils.parseParent("invalid-no-colon"),
                "Should throw on invalid format");
        assertThrows(
                Exception.class,
                () -> BraintrustUtils.parseParent("invalid:too:many:colons"),
                "Should throw on invalid format");
        assertThrows(
                Exception.class,
                () -> BraintrustUtils.parseParent(""),
                "Should throw on empty string");
    }

    /**
     * The platform-thread fallback used on Java < 21. The virtual-thread path is covered by
     * jvm-compat-tests/java21, since this suite runs on Java 17.
     */
    @Test
    void testNewExecutorUsesDaemonThreads() throws Exception {
        var executor = BraintrustUtils.newExecutor(2, "braintrust-utils-test-");
        try {
            var thread = executor.submit(Thread::currentThread).get(10, TimeUnit.SECONDS);
            assertTrue(thread.isDaemon(), "executor threads must be daemons");
            assertTrue(
                    thread.getName().startsWith("braintrust-utils-test-"),
                    "unexpected thread name: " + thread.getName());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void testNewExecutorCapsConcurrency() throws Exception {
        int maxSize = 3;
        var executor = BraintrustUtils.newExecutor(maxSize, "braintrust-utils-bounded-");
        var inFlight = new AtomicInteger();
        var peak = new AtomicInteger();
        var release = new CountDownLatch(1);
        var done = new CountDownLatch(maxSize);
        try {
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

            var submitted = new CountDownLatch(1);
            var submitter =
                    new Thread(
                            () -> {
                                executor.execute(() -> {});
                                submitted.countDown();
                            });
            submitter.setDaemon(true);
            submitter.start();

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
