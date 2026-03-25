package dev.braintrust.instrumentation.springai.v1_0_0;

/**
 * A three-argument consumer analogous to {@link java.util.function.BiConsumer}.
 *
 * @param <T> the type of the first argument
 * @param <U> the type of the second argument
 * @param <V> the type of the third argument
 */
@FunctionalInterface
interface TriConsumer<T, U, V> {
    void accept(T t, U u, V v);
}
