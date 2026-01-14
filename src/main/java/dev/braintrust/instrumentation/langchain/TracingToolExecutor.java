package dev.braintrust.instrumentation.langchain;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/** A ToolExecutor wrapper that creates a span around tool execution */
@Slf4j
class TracingToolExecutor implements ToolExecutor {
    static final String TYPE_TOOL_JSON = "{\"type\":\"tool\"}";

    private final ToolExecutor delegate;
    private final String toolName;
    private final Tracer tracer;

    TracingToolExecutor(ToolExecutor delegate, String toolName, Tracer tracer) {
        this.delegate = delegate;
        this.toolName = toolName;
        this.tracer = tracer;
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        Span span = tracer.spanBuilder(toolName).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            String result = delegate.execute(request, memoryId);
            setSpanAttributes(span, request, result);
            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    @Override
    public ToolExecutionResult executeWithContext(
            ToolExecutionRequest request, InvocationContext context) {
        Span span = tracer.spanBuilder(toolName).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            ToolExecutionResult result = delegate.executeWithContext(request, context);
            setSpanAttributes(span, request, result.resultText());
            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private void setSpanAttributes(
            Span span, ToolExecutionRequest request, @Nullable String toolCallResult) {
        try {
            span.setAttribute("braintrust.span_attributes", TYPE_TOOL_JSON);

            String args = request.arguments();
            if (args != null && !args.isEmpty()) {
                span.setAttribute("braintrust.input_json", args);
            }
            if (toolCallResult != null) {
                span.setAttribute("braintrust.output", toolCallResult);
            }
        } catch (Exception e) {
            log.debug("Failed to set tool span attributes", e);
        }
    }
}
