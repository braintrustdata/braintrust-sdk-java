package dev.braintrust.instrumentation.awsbedrock.v2_30_0;

import dev.braintrust.instrumentation.ConverseStreamAccumulator;
import dev.braintrust.instrumentation.InstrumentationSemConv;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.Context.BeforeExecution;
import software.amazon.awssdk.core.interceptor.Context.ModifyHttpRequest;
import software.amazon.awssdk.core.interceptor.Context.ModifyHttpResponse;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.eventstream.Message;
import software.amazon.eventstream.MessageDecoder;

/**
 * AWS SDK ExecutionInterceptor that creates OpenTelemetry spans for Bedrock Converse calls,
 * capturing the raw request and response bodies via {@link InstrumentationSemConv}.
 */
@Slf4j
class BraintrustBedrockInterceptor implements ExecutionInterceptor {
    private static final String INSTRUMENTATION_NAME = "braintrust-aws-bedrock";

    private static final ExecutionAttribute<Span> SPAN_ATTRIBUTE =
            new ExecutionAttribute<>("braintrust.span");
    private static final ExecutionAttribute<String> MODEL_ID_ATTRIBUTE =
            new ExecutionAttribute<>("braintrust.modelId");

    private final Tracer tracer;

    BraintrustBedrockInterceptor(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    private static final Set<String> INSTRUMENTED_OPERATIONS = Set.of("Converse", "ConverseStream");

    @Override
    public void beforeExecution(BeforeExecution context, ExecutionAttributes executionAttributes) {
        String operationName =
                executionAttributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME);

        // Only instrument Converse and ConverseStream — other Bedrock operations
        // (InvokeModel, ApplyGuardrail, etc.) are not LLM calls we know how to tag.
        if (!INSTRUMENTED_OPERATIONS.contains(operationName)) {
            return;
        }

        SdkRequest sdkRequest = context.request();
        String modelId = extractModelId(sdkRequest);

        Span span = tracer.spanBuilder(operationName).setParent(Context.current()).startSpan();
        executionAttributes.putAttribute(SPAN_ATTRIBUTE, span);
        if (modelId != null) {
            executionAttributes.putAttribute(MODEL_ID_ATTRIBUTE, modelId);
        }
    }

    @Override
    public SdkHttpRequest modifyHttpRequest(
            ModifyHttpRequest context, ExecutionAttributes executionAttributes) {
        Span span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
        if (span == null) {
            return context.httpRequest();
        }

        SdkHttpRequest httpRequest = context.httpRequest();
        String modelId = executionAttributes.getAttribute(MODEL_ID_ATTRIBUTE);

        if (modelId == null) {
            modelId = extractModelIdFromPath(httpRequest.encodedPath());
            if (modelId != null) {
                executionAttributes.putAttribute(MODEL_ID_ATTRIBUTE, modelId);
            }
        }

        String requestBody = null;
        if (context.requestBody().isPresent()) {
            try (InputStream is = context.requestBody().get().contentStreamProvider().newStream()) {
                requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.debug("Failed to capture request body", e);
            }
        }

        String baseUrl = httpRequest.protocol() + "://" + httpRequest.host();
        List<String> pathSegments =
                Arrays.stream(httpRequest.encodedPath().split("/"))
                        .filter(s -> !s.isEmpty())
                        .toList();

        InstrumentationSemConv.tagLLMSpanRequest(
                span,
                InstrumentationSemConv.PROVIDER_NAME_BEDROCK,
                baseUrl,
                pathSegments,
                "POST",
                requestBody,
                modelId);

        return httpRequest;
    }

    @Override
    public Optional<InputStream> modifyHttpResponseContent(
            ModifyHttpResponse context, ExecutionAttributes executionAttributes) {
        Span span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
        if (span == null) {
            return context.responseBody();
        }

        // Only intercept successful responses. On 4xx/5xx the SDK needs to read the body
        // itself to parse the AWS error message — consuming it here would swallow that.
        if (context.httpResponse().statusCode() >= 300) {
            return context.responseBody();
        }

        Optional<InputStream> body = context.responseBody();
        if (body.isPresent()) {
            try {
                final byte[] bytes = body.get().readAllBytes();
                try {
                    String responseBodyStr = new String(bytes, StandardCharsets.UTF_8);
                    InstrumentationSemConv.tagLLMSpanResponse(
                            tracer,
                            span,
                            InstrumentationSemConv.PROVIDER_NAME_BEDROCK,
                            responseBodyStr);
                } catch (Exception e) {
                    log.debug("Failed to capture response body", e);
                }
                return Optional.of(new ByteArrayInputStream(bytes));
            } catch (IOException e) {
                // unlikely this will happen, but if we get here there's no sensible recovery
                throw new RuntimeException("failed to ready response body bytes", e);
            }
        }
        return body;
    }

    /**
     * Intercepts the async response stream for {@code converseStream} calls. Tees the reactive
     * {@link Publisher} so that bytes are fed to a {@link MessageDecoder} as they arrive, and on
     * completion the decoded event-stream frames are used to tag the span.
     */
    @Override
    public Optional<Publisher<ByteBuffer>> modifyAsyncHttpResponseContent(
            software.amazon.awssdk.core.interceptor.Context.ModifyHttpResponse context,
            ExecutionAttributes executionAttributes) {
        Span span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
        Optional<Publisher<ByteBuffer>> publisherOpt = context.responsePublisher();
        if (span == null || publisherOpt.isEmpty()) {
            return publisherOpt;
        }

        // Only intercept successful responses — error responses must flow through untouched
        // so the SDK can parse the AWS error body.
        if (context.httpResponse().statusCode() >= 300) {
            return publisherOpt;
        }

        Publisher<ByteBuffer> original = publisherOpt.get();
        Publisher<ByteBuffer> teed =
                subscriber -> original.subscribe(new TeeingSubscriber(subscriber, span, tracer));
        return Optional.of(teed);
    }

    @Override
    public void afterExecution(
            software.amazon.awssdk.core.interceptor.Context.AfterExecution context,
            ExecutionAttributes executionAttributes) {
        endSpan(executionAttributes, null);
    }

    @Override
    public void onExecutionFailure(
            software.amazon.awssdk.core.interceptor.Context.FailedExecution context,
            ExecutionAttributes executionAttributes) {
        endSpan(executionAttributes, context.exception());
    }

    private static void endSpan(
            ExecutionAttributes executionAttributes, @javax.annotation.Nullable Throwable error) {
        Span span = executionAttributes.getAttribute(SPAN_ATTRIBUTE);
        if (span == null) {
            return;
        }
        if (error != null) {
            InstrumentationSemConv.tagLLMSpanResponse(span, error);
        }
        span.end();
    }

    private static String extractModelId(SdkRequest request) {
        if (request instanceof ConverseRequest r) return r.modelId();
        if (request instanceof ConverseStreamRequest r) return r.modelId();
        return null;
    }

    private static String extractModelIdFromPath(String path) {
        if (path != null && path.startsWith("/model/")) {
            int start = "/model/".length();
            int end = path.indexOf("/", start);
            if (end > start) {
                return java.net.URLDecoder.decode(
                        path.substring(start, end), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * Tees the reactive byte stream into a {@link MessageDecoder}, forwarding each decoded
     * event-stream frame to a {@link ConverseStreamAccumulator}. On completion the accumulator's
     * reconstructed Converse-shaped body is handed to semconv, so a streaming span carries the same
     * content-block shapes — text, tool use, reasoning — as a synchronous one.
     */
    private static class TeeingSubscriber implements Subscriber<ByteBuffer> {
        private final Subscriber<? super ByteBuffer> downstream;
        private final Span span;
        private final Tracer tracer;
        private final MessageDecoder decoder = new MessageDecoder();
        private final ConverseStreamAccumulator accumulator = new ConverseStreamAccumulator();

        private long startNanos;
        private Long timeToFirstTokenNanos = null;

        TeeingSubscriber(Subscriber<? super ByteBuffer> downstream, Span span, Tracer tracer) {
            this.downstream = downstream;
            this.span = span;
            this.tracer = tracer;
        }

        @Override
        public void onSubscribe(Subscription s) {
            startNanos = System.nanoTime();
            downstream.onSubscribe(s);
        }

        @Override
        public void onNext(ByteBuffer buf) {
            byte[] copy = new byte[buf.remaining()];
            buf.duplicate().get(copy);
            try {
                decoder.feed(copy);
                for (Message msg : decoder.getDecodedMessages()) {
                    var header = msg.getHeaders().get(":event-type");
                    if (header == null) continue;
                    String eventType = header.getString();
                    // First content frame marks time-to-first-token, whether the model opened with
                    // text, a tool call, or a reasoning block.
                    if (timeToFirstTokenNanos == null
                            && ("contentBlockDelta".equals(eventType)
                                    || "contentBlockStart".equals(eventType))) {
                        timeToFirstTokenNanos = System.nanoTime() - startNanos;
                    }
                    accumulator.accept(
                            eventType, new String(msg.getPayload(), StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                log.debug("Failed to feed event-stream decoder", e);
            }
            downstream.onNext(buf);
        }

        @Override
        public void onError(Throwable t) {
            downstream.onError(t);
        }

        @Override
        public void onComplete() {
            try {
                InstrumentationSemConv.tagLLMSpanResponse(
                        tracer,
                        span,
                        InstrumentationSemConv.PROVIDER_NAME_BEDROCK,
                        accumulator.build(),
                        timeToFirstTokenNanos);
            } catch (Exception e) {
                log.debug("Failed to tag span from streaming response", e);
            } finally {
                downstream.onComplete();
            }
        }
    }
}
