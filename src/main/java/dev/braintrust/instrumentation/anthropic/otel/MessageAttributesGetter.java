package dev.braintrust.instrumentation.anthropic.otel;

import static java.util.Collections.emptyList;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import io.opentelemetry.instrumentation.api.incubator.semconv.genai.GenAiAttributesGetter;
import java.util.List;
import org.jetbrains.annotations.Nullable;

enum MessageAttributesGetter implements GenAiAttributesGetter<MessageCreateParams, Message> {
    INSTANCE;

    @Override
    public String getOperationName(MessageCreateParams request) {
        return BraintrustAnthropicSpanAttributes.OPERATION_CHAT;
    }

    @Override
    public String getSystem(MessageCreateParams request) {
        return BraintrustAnthropicSpanAttributes.SYSTEM_ANTHROPIC;
    }

    @Override
    public String getRequestModel(MessageCreateParams request) {
        return request.model().asString();
    }

    @Nullable
    @Override
    public Long getRequestSeed(MessageCreateParams request) {
        return null;
    }

    @Nullable
    @Override
    public List<String> getRequestEncodingFormats(MessageCreateParams request) {
        return null;
    }

    @Nullable
    @Override
    public Double getRequestFrequencyPenalty(MessageCreateParams request) {
        return null;
    }

    @Nullable
    @Override
    public Long getRequestMaxTokens(MessageCreateParams request) {
        // maxTokens() returns a primitive long, so we convert to Long
        long maxTokens = request.maxTokens();
        return maxTokens > 0 ? maxTokens : null;
    }

    @Nullable
    @Override
    public Double getRequestPresencePenalty(MessageCreateParams request) {
        return null;
    }

    @Nullable
    @Override
    public List<String> getRequestStopSequences(MessageCreateParams request) {
        return request.stopSequences().orElse(null);
    }

    @Nullable
    @Override
    public Double getRequestTemperature(MessageCreateParams request) {
        return request.temperature().orElse(null);
    }

    @Nullable
    @Override
    public Double getRequestTopK(MessageCreateParams request) {
        return request.topK().map(Long::doubleValue).orElse(null);
    }

    @Nullable
    @Override
    public Double getRequestTopP(MessageCreateParams request) {
        return request.topP().orElse(null);
    }

    @Override
    public List<String> getResponseFinishReasons(
            MessageCreateParams request, @Nullable Message response) {
        if (response == null) {
            return emptyList();
        }
        return response.stopReason().map(reason -> List.of(reason.asString())).orElse(emptyList());
    }

    @Override
    @Nullable
    public String getResponseId(MessageCreateParams request, @Nullable Message response) {
        if (response == null) {
            return null;
        }
        return response.id();
    }

    @Override
    @Nullable
    public String getResponseModel(MessageCreateParams request, @Nullable Message response) {
        if (response == null) {
            return null;
        }
        return response.model().asString();
    }

    @Override
    @Nullable
    public Long getUsageInputTokens(MessageCreateParams request, @Nullable Message response) {
        if (response == null) {
            return null;
        }
        return response.usage().inputTokens();
    }

    @Override
    @Nullable
    public Long getUsageOutputTokens(MessageCreateParams request, @Nullable Message response) {
        if (response == null) {
            return null;
        }
        return response.usage().outputTokens();
    }
}
