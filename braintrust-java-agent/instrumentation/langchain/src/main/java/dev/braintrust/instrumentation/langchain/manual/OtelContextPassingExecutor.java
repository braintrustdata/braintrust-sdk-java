package dev.braintrust.instrumentation.langchain.manual;

import io.opentelemetry.context.Context;
import java.util.concurrent.Executor;

/**
 * An executor that links open telemetry spans across threads.
 *
 * <p>Any tasks submitted to the executor will point to the parent context that was present at the
 * time of task submission.
 */
class OtelContextPassingExecutor implements Executor {
    private final Executor underlying;

    public OtelContextPassingExecutor(Executor executor) {
        this.underlying = executor;
    }

    @Override
    public void execute(Runnable command) {
        var context = Context.current();
        underlying.execute(
                () -> {
                    try (var ignored = context.makeCurrent()) {
                        command.run();
                    }
                });
    }
}
