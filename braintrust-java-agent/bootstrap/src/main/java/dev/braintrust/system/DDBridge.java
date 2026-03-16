package dev.braintrust.system;

import datadog.trace.api.GlobalTracer;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** Bridge between Datadog's tracing system and Braintrust. */
public class DDBridge {

    // FIXME fix up threading and add proper protection for tracer

    public static final AtomicReference<TracerProvider> tracerProvider = new AtomicReference<>();
    private static final AtomicReference<BridgeInMemorySpanExporter> smokeTestExporter =
            new AtomicReference<>();

    /**
     * Sets the in-memory span exporter used during smoke testing. Called by DDBridgeConsumer when
     * the smoke test system property is enabled.
     */
    public static void setSmokeTestExporter(BridgeInMemorySpanExporter exporter) {
        smokeTestExporter.set(exporter);
    }

    /** Returns the smoke test exporter, or null if not in smoke test mode. */
    public static BridgeInMemorySpanExporter getSmokeTestExporter() {
        return smokeTestExporter.get();
    }

    /**
     * Returns the map of bridged spans collected during smoke testing, keyed by trace ID. Reads
     * from the in-memory span exporter if one has been configured.
     */
    public static Map<String, List<SpanData>> getBridgedSpans() {
        BridgeInMemorySpanExporter exporter = smokeTestExporter.get();
        if (exporter == null) {
            return Collections.emptyMap();
        }
        Map<String, List<SpanData>> result = new LinkedHashMap<>();
        for (SpanData span : exporter.getFinishedSpanItems()) {
            result.computeIfAbsent(span.getTraceId(), k -> new ArrayList<>()).add(span);
        }
        return result;
    }

    /**
     * Consumer that receives completed DD traces. Set by BraintrustAgent once the agent internals
     * are loaded. Until set, traces are buffered.
     */
    private static volatile Consumer<List<MutableSpan>> traceConsumer;

    /** Buffer for traces that arrive before the consumer is set. */
    public static final CopyOnWriteArrayList<List<MutableSpan>> bufferedTraces =
            new CopyOnWriteArrayList<>();

    /**
     * Registers a DD TraceInterceptor that forwards completed traces to the Braintrust trace
     * consumer. Call this during BT agent premain, after DD agent has initialized.
     *
     * <p>returns true if successful
     */
    public static boolean registerDDTraceInterceptor() {
        return GlobalTracer.get()
                .addTraceInterceptor(
                        new TraceInterceptor() {
                            @Override
                            public Collection<? extends MutableSpan> onTraceComplete(
                                    Collection<? extends MutableSpan> trace) {
                                List<MutableSpan> snapshot = List.copyOf(trace);
                                if (traceConsumer == null) {
                                    synchronized (DDBridge.class) {
                                        if (traceConsumer
                                                == null) { // in case another thread set the
                                            // consumer
                                            bufferedTraces.add(snapshot);
                                        } else {
                                            traceConsumer.accept(snapshot);
                                        }
                                    }
                                } else {
                                    traceConsumer.accept(snapshot);
                                }
                                return trace;
                            }

                            @Override
                            public int priority() {
                                // High priority number = runs later in the interceptor chain,
                                // so we see the final form of the spans.
                                return 999;
                            }
                        });
    }

    /**
     * Sets the consumer that processes completed DD traces. Any traces that were buffered before
     * this call are immediately drained to the consumer.
     *
     * <p>Called by BraintrustAgent once the agent internals and span processor are ready.
     */
    public static synchronized void setTraceConsumer(Consumer<List<MutableSpan>> consumer) {
        if (traceConsumer != null) {
            throw new IllegalStateException("trace consumer already set");
        }
        traceConsumer = consumer;
        // Drain buffered traces
        for (List<MutableSpan> trace : bufferedTraces) {
            consumer.accept(trace);
        }
        bufferedTraces.clear();
    }

    public static class BridgeInMemorySpanExporter implements SpanExporter {
        private final Queue<SpanData> finishedSpanItems = new ConcurrentLinkedQueue<>();
        private final Lock lock = new ReentrantLock();
        private final Condition spansAdded = lock.newCondition();
        private boolean isStopped = false;

        public BridgeInMemorySpanExporter() {}

        public List<SpanData> getFinishedSpanItems(int minSpanCount) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            lock.lock();
            try {
                while (finishedSpanItems.size() < minSpanCount) {
                    long remainingNanos = deadline - System.nanoTime();
                    if (remainingNanos <= 0) {
                        throw new RuntimeException(
                                String.format(
                                        "Timeout waiting for spans: expected at least %d spans, but"
                                                + " got %d after 30 seconds",
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
}
