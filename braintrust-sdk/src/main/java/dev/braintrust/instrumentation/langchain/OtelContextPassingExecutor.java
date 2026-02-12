package dev.braintrust.instrumentation.langchain;

import io.opentelemetry.context.Context;
import java.util.concurrent.Executor;
import org.jspecify.annotations.NonNull;

/**
 * An executor that links open telemetry spans across threads
 *
 * <p>Any tasks submitted to the executor will point to the parent context that was present at the
 * time of task submission. Or, if no parent context was present tasks will create spans as they
 * normally would (or would not) without this executor.
 */
class OtelContextPassingExecutor implements Executor {
    private final Executor underlying;

    public OtelContextPassingExecutor(Executor executor) {
        this.underlying = executor;
    }

    @Override
    public void execute(@NonNull Runnable command) {
        var context = Context.current();
        underlying.execute(
                () -> {
                    try (var ignored = context.makeCurrent()) {
                        command.run();
                    }
                });
    }
}
