package dev.braintrust.instrumentation.langchain;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import org.jspecify.annotations.NonNull;

class TracingProxy {
    /**
     * Use a java {@link Proxy} to wrap a service interface methods with spans
     *
     * <p>Each interface method will create a span with the same name as the method
     */
    @SuppressWarnings("unchecked")
    public static <T> @NonNull T create(Class<T> serviceInterface, T service, Tracer tracer) {
        return (T)
                Proxy.newProxyInstance(
                        serviceInterface.getClassLoader(),
                        new Class<?>[] {serviceInterface},
                        (proxy, method, args) -> {
                            // Skip Object methods (equals, hashCode, toString)
                            if (method.getDeclaringClass() == Object.class) {
                                return method.invoke(service, args);
                            }

                            Span span = tracer.spanBuilder(method.getName()).startSpan();
                            try (Scope ignored = span.makeCurrent()) {
                                // Use setAccessible to handle non-public interfaces
                                method.setAccessible(true);
                                return method.invoke(service, args);
                            } catch (InvocationTargetException e) {
                                Throwable cause = e.getCause();
                                span.setStatus(StatusCode.ERROR, cause.getMessage());
                                span.recordException(cause);
                                throw cause;
                            } catch (Exception e) {
                                span.setStatus(StatusCode.ERROR, e.getMessage());
                                span.recordException(e);
                                throw e;
                            } finally {
                                span.end();
                            }
                        });
    }

    private TracingProxy() {}
}
