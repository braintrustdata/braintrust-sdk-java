package dev.braintrust.instrumentation.anthropic.otel;

import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent;
import java.util.Comparator;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;

final class BetaTracingStreamedResponse implements StreamResponse<BetaRawMessageStreamEvent> {

    private final StreamResponse<BetaRawMessageStreamEvent> delegate;
    private final BetaStreamListener listener;

    BetaTracingStreamedResponse(
            StreamResponse<BetaRawMessageStreamEvent> delegate, BetaStreamListener listener) {
        this.delegate = delegate;
        this.listener = listener;
    }

    @Override
    public Stream<BetaRawMessageStreamEvent> stream() {
        return StreamSupport.stream(new TracingSpliterator(delegate.stream().spliterator()), false);
    }

    @Override
    public void close() {
        listener.endSpan(null);
        delegate.close();
    }

    private class TracingSpliterator implements Spliterator<BetaRawMessageStreamEvent> {

        private final Spliterator<BetaRawMessageStreamEvent> delegateSpliterator;

        private TracingSpliterator(Spliterator<BetaRawMessageStreamEvent> delegateSpliterator) {
            this.delegateSpliterator = delegateSpliterator;
        }

        @Override
        public boolean tryAdvance(Consumer<? super BetaRawMessageStreamEvent> action) {
            boolean eventReceived =
                    delegateSpliterator.tryAdvance(
                            event -> {
                                listener.onEvent(event);
                                action.accept(event);
                            });
            if (!eventReceived) {
                listener.endSpan(null);
            }
            return eventReceived;
        }

        @Override
        @Nullable
        public Spliterator<BetaRawMessageStreamEvent> trySplit() {
            // do not support parallelism to reliably catch the last event
            return null;
        }

        @Override
        public long estimateSize() {
            return delegateSpliterator.estimateSize();
        }

        @Override
        public long getExactSizeIfKnown() {
            return delegateSpliterator.getExactSizeIfKnown();
        }

        @Override
        public int characteristics() {
            return delegateSpliterator.characteristics();
        }

        @Override
        public Comparator<? super BetaRawMessageStreamEvent> getComparator() {
            return delegateSpliterator.getComparator();
        }
    }
}
