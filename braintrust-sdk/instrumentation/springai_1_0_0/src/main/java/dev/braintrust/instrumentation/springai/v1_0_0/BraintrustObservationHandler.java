package dev.braintrust.instrumentation.springai.v1_0_0;

import dev.braintrust.instrumentation.InstrumentationSemConv;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import javax.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.observation.ChatModelObservationContext;

/**
 * Provider-agnostic Micrometer observation handler for Spring AI chat model calls.
 *
 * <p>Starts an OTel span on observation start and ends it on stop/error. Provider-specific request
 * and response tagging is delegated to the supplied {@code tagRequest} and {@code tagResponse}
 * callbacks so that OpenAI and Anthropic can each supply the correct format.
 */
@Slf4j
final class BraintrustObservationHandler
        implements ObservationHandler<ChatModelObservationContext> {
    private static final String OBSERVATION_SPAN_KEY =
            BraintrustObservationHandler.class.getName() + ".span";

    private static final String START_NANOS_KEY =
            BraintrustObservationHandler.class.getName() + ".startNanos";
    static final String TTFT_NANOS_KEY =
            BraintrustObservationHandler.class.getName() + ".ttftNanos";

    private final Tracer tracer;
    private final TriConsumer<BraintrustObservationHandler, Span, ChatModelObservationContext>
            tagRequest;
    private final TriConsumer<BraintrustObservationHandler, Span, ChatModelObservationContext>
            tagResponse;
    private final String baseUrl;

    BraintrustObservationHandler(
            Tracer tracer,
            String baseUrl,
            TriConsumer<BraintrustObservationHandler, Span, ChatModelObservationContext> tagRequest,
            TriConsumer<BraintrustObservationHandler, Span, ChatModelObservationContext>
                    tagResponse) {
        this.tracer = tracer;
        this.baseUrl = baseUrl;
        this.tagRequest = tagRequest;
        this.tagResponse = tagResponse;
    }

    String getBaseUrl() {
        return this.baseUrl;
    }

    @Override
    public boolean supportsContext(@Nonnull Observation.Context context) {
        return context instanceof ChatModelObservationContext;
    }

    @Override
    public void onStart(@Nonnull ChatModelObservationContext context) {
        try {
            context.put(START_NANOS_KEY, System.nanoTime());
            Span span = tracer.spanBuilder(InstrumentationSemConv.UNSET_LLM_SPAN_NAME).startSpan();
            context.put(OBSERVATION_SPAN_KEY, span);
            tagRequest.accept(this, span, context);
        } catch (Exception e) {
            log.debug("instrumentation error", e);
        }
    }

    @Override
    public void onEvent(
            @Nonnull Observation.Event event, @Nonnull ChatModelObservationContext context) {
        try {
            setTTFTIfAbsent(context);
        } catch (Exception e) {
            log.debug("instrumentation error", e);
        }
    }

    @Override
    public void onError(@Nonnull ChatModelObservationContext context) {
        try {
            Span span = context.get(OBSERVATION_SPAN_KEY);
            if (span != null && context.getError() != null) {
                InstrumentationSemConv.tagLLMSpanResponse(span, context.getError());
            }
        } catch (Exception e) {
            log.debug("instrumentation error", e);
        }
    }

    @Override
    public void onStop(@Nonnull ChatModelObservationContext context) {
        try {
            Span span = context.get(OBSERVATION_SPAN_KEY);
            if (span == null) {
                return;
            }
            try {
                if (context.getResponse() != null) {
                    setTTFTIfAbsent(context);
                    tagResponse.accept(this, span, context);
                }
            } finally {
                span.end();
            }
        } catch (Exception e) {
            log.debug("instrumentation error", e);
        }
    }

    private void setTTFTIfAbsent(ChatModelObservationContext context) {
        if (context.get(TTFT_NANOS_KEY) == null) {
            synchronized (this) {
                if (context.get(TTFT_NANOS_KEY) == null) {
                    Long startNanos = context.get(START_NANOS_KEY);
                    if (startNanos != null) {
                        context.put(TTFT_NANOS_KEY, System.nanoTime() - startNanos);
                    }
                }
            }
        }
    }
}
