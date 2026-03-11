package dev.braintrust;

import static org.junit.jupiter.api.Assertions.fail;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import lombok.SneakyThrows;

/**
 * an in memory span exporter which allows for blocking until the exported span count reaches an
 * expected min
 */
public class UnitTestSpanExporter implements SpanExporter {
    private final Queue<SpanData> finishedSpanItems = new ConcurrentLinkedQueue<>();
    private final Lock lock = new ReentrantLock();
    private final Condition spansAdded = lock.newCondition();
    private boolean isStopped = false;

    public UnitTestSpanExporter() {}

    @SneakyThrows
    public List<SpanData> getFinishedSpanItems(int minSpanCount) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        lock.lock();
        try {
            while (finishedSpanItems.size() < minSpanCount) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    fail(
                            String.format(
                                    "Timeout waiting for spans: expected at least %d spans, but got"
                                            + " %d after 30 seconds",
                                    minSpanCount, finishedSpanItems.size()));
                }
                spansAdded.awaitNanos(remainingNanos);
            }
            return Collections.unmodifiableList(new ArrayList<>(finishedSpanItems));
        } finally {
            lock.unlock();
        }
    }

    public List<SpanData> getFinishedSpanItems() {
        return Collections.unmodifiableList(new ArrayList<>(finishedSpanItems));
    }

    public void reset() {
        finishedSpanItems.clear();
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        if (isStopped) {
            return CompletableResultCode.ofFailure();
        }
        lock.lock();
        try {
            finishedSpanItems.addAll(spans);
            spansAdded.signalAll();
        } finally {
            lock.unlock();
        }
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        finishedSpanItems.clear();
        isStopped = true;
        return CompletableResultCode.ofSuccess();
    }
}
