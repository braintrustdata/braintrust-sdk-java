package dev.braintrust.system;

import datadog.trace.api.GlobalTracer;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Bridge between Datadog's tracing system and Braintrust.
 */
public class DDBridge {

    // FIXME fix up threading and add proper protection for tracer

    public static final AtomicReference<TracerProvider> tracerProvider = new AtomicReference<>();
    public static final Map<String, List<SpanData>> bridgedSpans = new ConcurrentHashMap<>();

    /**
     * Consumer that receives completed DD traces. Set by BraintrustAgent once
     * the agent internals are loaded. Until set, traces are buffered.
     */
    private static volatile Consumer<List<MutableSpan>> traceConsumer;

    /** Buffer for traces that arrive before the consumer is set. */
    public static final CopyOnWriteArrayList<List<MutableSpan>> bufferedTraces = new CopyOnWriteArrayList<>();

    /**
     * Registers a DD TraceInterceptor that forwards completed traces to
     * the Braintrust trace consumer. Call this during BT agent premain,
     * after DD agent has initialized.
     *
     * returns true if successful
     */
    public static boolean registerDDTraceInterceptor() {
        return GlobalTracer.get().addTraceInterceptor(new TraceInterceptor() {
            @Override
            public Collection<? extends MutableSpan> onTraceComplete(
                    Collection<? extends MutableSpan> trace) {
                List<MutableSpan> snapshot = List.copyOf(trace);
                if (traceConsumer == null) {
                    synchronized (DDBridge.class) {
                        if (traceConsumer == null) { // in case another thread set the consumer
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
     * Sets the consumer that processes completed DD traces. Any traces that
     * were buffered before this call are immediately drained to the consumer.
     *
     * <p>Called by BraintrustAgent once the agent internals and span processor
     * are ready.
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
}
