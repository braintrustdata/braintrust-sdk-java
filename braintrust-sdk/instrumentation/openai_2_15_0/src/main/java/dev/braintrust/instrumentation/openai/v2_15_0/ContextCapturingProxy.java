package dev.braintrust.instrumentation.openai.v2_15_0;

import com.openai.core.Params;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Captures the caller's OTel context at the service-call boundary of a wrapped openai-java client.
 *
 * <p>openai-java dispatches async requests through CompletableFuture continuations on the common
 * pool, so by the time {@link TracingHttpClient#executeAsync} runs, the caller's thread-local
 * context (e.g. an active application span) is gone. The service-method invocation itself, however,
 * happens on the caller's thread — and the only data that travels from there into the HTTP layer is
 * the request. So this proxy rewrites any {@link Params} argument to carry the current span as an
 * internal {@code traceparent}-format header, which {@link TracingHttpClient} extracts (and strips
 * from the outgoing request) to parent the LLM span.
 *
 * <p>Purely reflective and API-shape based, so it works for every service and endpoint: methods
 * returning other {@code com.openai} interfaces (service accessors like {@code chat()}, {@code
 * async()}) return proxied instances, so context capture follows the whole call graph.
 */
@Slf4j
final class ContextCapturingProxy implements InvocationHandler {

    /** Internal correlation header; never sent — TracingHttpClient removes it. */
    static final String CONTEXT_HEADER = "x-braintrust-instrumentation-context";

    /** Per-params-class reflection handles: [toBuilder, putAdditionalHeader, build]. */
    private static final Map<Class<?>, Method[]> PARAMS_METHODS = new ConcurrentHashMap<>();

    private static final Method[] UNSUPPORTED = new Method[0];

    private final Object delegate;

    private ContextCapturingProxy(Object delegate) {
        this.delegate = delegate;
    }

    /** Wraps {@code delegate} in a context-capturing proxy of {@code iface}. Idempotent. */
    @SuppressWarnings("unchecked")
    static <T> T wrap(T delegate, Class<T> iface) {
        if (delegate == null || isContextCapturingProxy(delegate)) {
            return delegate;
        }
        return (T)
                Proxy.newProxyInstance(
                        iface.getClassLoader(),
                        new Class<?>[] {iface},
                        new ContextCapturingProxy(delegate));
    }

    static boolean isContextCapturingProxy(Object o) {
        return o != null
                && Proxy.isProxyClass(o.getClass())
                && Proxy.getInvocationHandler(o) instanceof ContextCapturingProxy;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // equals/hashCode/toString are the only Object methods routed to an InvocationHandler.
        // They must not be forwarded like service methods: delegate.equals(proxy) is false, so a
        // forwarded equals would make proxy.equals(proxy) false — violating reflexivity and
        // breaking map/set usage of wrapped clients.
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0] || delegateEquals(args[0]);
                case "hashCode" -> delegate.hashCode();
                default -> "ContextCapturingProxy(" + delegate + ")"; // toString
            };
        }
        Object[] invokeArgs = injectContextHeader(args);
        Object result;
        try {
            result = method.invoke(delegate, invokeArgs);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
        // Follow the service graph: accessors like chat(), completions(), async() return
        // com.openai interfaces whose methods must also capture context.
        Class<?> returnType = method.getReturnType();
        if (result != null
                && returnType.isInterface()
                && returnType.getName().startsWith("com.openai.")) {
            return Proxy.newProxyInstance(
                    returnType.getClassLoader(),
                    new Class<?>[] {returnType},
                    new ContextCapturingProxy(result));
        }
        return result;
    }

    /** Two proxies are equal iff their delegates are (consistent with delegate-based hashCode). */
    private boolean delegateEquals(Object other) {
        return isContextCapturingProxy(other)
                && delegate.equals(
                        ((ContextCapturingProxy) Proxy.getInvocationHandler(other)).delegate);
    }

    /** Rewrites any {@link Params} argument to carry the current span as an internal header. */
    private Object[] injectContextHeader(Object[] args) {
        if (args == null) {
            return null;
        }
        String traceparent = currentTraceparent();
        if (traceparent == null) {
            return args;
        }
        Object[] result = args;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Params params) {
                Object rewritten = withContextHeader(params, traceparent);
                if (rewritten != null) {
                    if (result == args) {
                        result = args.clone();
                    }
                    result[i] = rewritten;
                }
            }
        }
        return result;
    }

    private static String currentTraceparent() {
        SpanContext spanContext = Span.current().getSpanContext();
        if (!spanContext.isValid()) {
            return null;
        }
        return "00-"
                + spanContext.getTraceId()
                + "-"
                + spanContext.getSpanId()
                + "-"
                + spanContext.getTraceFlags().asHex();
    }

    /**
     * {@code params.toBuilder().putAdditionalHeader(CONTEXT_HEADER, traceparent).build()}, done
     * reflectively so it works for every generated params type. Returns {@code null} (leaving the
     * original untouched) when the shape doesn't match.
     */
    private static Object withContextHeader(Params params, String traceparent) {
        Method[] methods =
                PARAMS_METHODS.computeIfAbsent(
                        params.getClass(), ContextCapturingProxy::resolveParamsMethods);
        if (methods == UNSUPPORTED) {
            return null;
        }
        try {
            Object builder = methods[0].invoke(params);
            methods[1].invoke(builder, CONTEXT_HEADER, traceparent);
            return methods[2].invoke(builder);
        } catch (Exception e) {
            log.debug("failed to inject context header into {}", params.getClass().getName(), e);
            return null;
        }
    }

    private static Method[] resolveParamsMethods(Class<?> paramsClass) {
        try {
            Method toBuilder = paramsClass.getMethod("toBuilder");
            Class<?> builderClass = toBuilder.getReturnType();
            Method putHeader =
                    builderClass.getMethod("putAdditionalHeader", String.class, String.class);
            Method build = builderClass.getMethod("build");
            return new Method[] {toBuilder, putHeader, build};
        } catch (NoSuchMethodException e) {
            log.debug("params type {} has no header builder shape", paramsClass.getName());
            return UNSUPPORTED;
        }
    }
}
