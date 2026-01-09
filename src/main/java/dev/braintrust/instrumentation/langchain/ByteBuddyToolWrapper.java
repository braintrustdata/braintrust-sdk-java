package dev.braintrust.instrumentation.langchain;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.*;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * Uses ByteBuddy to create runtime subclass proxies that intercept @Tool methods and add
 * OpenTelemetry spans with Braintrust attributes.
 */
@Slf4j
class ByteBuddyToolWrapper {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private final Tracer tracer;

    ByteBuddyToolWrapper(OpenTelemetry otel) {
        this.tracer = otel.getTracer("braintrust");
    }

    @SuppressWarnings("unchecked")
    public <T> T wrap(T originalTools) throws Exception {
        Class<?> toolClass = originalTools.getClass();

        // Create subclass with interceptor, preserving all annotations
        Class<?> proxyClass =
                new ByteBuddy()
                        .subclass(toolClass)
                        .method(ElementMatchers.isAnnotatedWith(Tool.class))
                        .intercept(
                                MethodDelegation.to(
                                        new ToolMethodInterceptor(tracer, originalTools)))
                        .attribute(
                                net.bytebuddy.implementation.attribute.MethodAttributeAppender
                                        .ForInstrumentedMethod.INCLUDING_RECEIVER)
                        .make()
                        .load(toolClass.getClassLoader())
                        .getLoaded();

        // Create instance - ByteBuddy subclass will delegate to original
        return (T) proxyClass.getDeclaredConstructor().newInstance();
    }

    /** Interceptor that wraps @Tool method calls with OpenTelemetry spans */
    public static class ToolMethodInterceptor {
        private final Tracer tracer;
        private final Object originalTools;

        public ToolMethodInterceptor(Tracer tracer, Object originalTools) {
            this.tracer = tracer;
            this.originalTools = originalTools;
        }

        @RuntimeType
        public Object intercept(
                @Origin Method method, @AllArguments Object[] args, @SuperCall Callable<?> zuper)
                throws Exception {

            String toolName = method.getName();

            // Build input map from parameters
            Map<String, Object> inputMap = new HashMap<>();
            var parameters = method.getParameters();
            for (int i = 0; i < parameters.length && i < args.length; i++) {
                inputMap.put(parameters[i].getName(), args[i]);
            }

            Span span = tracer.spanBuilder(toolName).startSpan();
            try (Scope scope = span.makeCurrent()) {
                // Set Braintrust span attributes
                span.setAttribute(
                        "braintrust.span_attributes",
                        json(Map.of("type", "tool", "name", toolName)));

                // Set input
                span.setAttribute("braintrust.input_json", json(inputMap));

                // Execute method and measure time
                long startTime = System.nanoTime();
                Object result = zuper.call(); // Call original method
                long endTime = System.nanoTime();

                // Set output
                span.setAttribute("braintrust.output_json", json(result));

                // Set metrics
                double executionTime = (endTime - startTime) / 1_000_000_000.0;
                span.setAttribute(
                        "braintrust.metrics", json(Map.of("execution_time", executionTime)));

                return result;
            } catch (Throwable t) {
                span.setStatus(StatusCode.ERROR, t.getMessage());
                span.recordException(t);
                throw t;
            } finally {
                span.end();
            }
        }

        @SneakyThrows
        private static String json(Object o) {
            return JSON_MAPPER.writeValueAsString(o);
        }
    }
}
