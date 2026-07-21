package dev.braintrust.sdkspecimpl;

import java.util.Optional;

/**
 * A first-class client module in the spec runner: one concrete way of calling a provider (raw SDK,
 * framework wrapper, etc.). Each spec is expanded into one JUnit execution per registered client
 * that {@link #supports} it.
 *
 * <p>Clients whose target libraries conflict with the main test classpath (e.g. two major versions
 * of the same library) can override {@link #isolation()}: the registry then runs the client inside
 * a child-first classloader built from a Gradle-provided classpath, loading {@code implClassName}
 * (which must also implement {@code SpecClient}) in that loader. See {@link
 * IsolatedClientDelegate}.
 */
public interface SpecClient {

    /** Unique client identifier, e.g. {@code "springai-openai"}. Appears in JUnit display names. */
    String id();

    /** The spec provider this client serves, e.g. {@code "openai"}. */
    String provider();

    /** Whether this client can execute the given spec (endpoint/feature filtering). */
    default boolean supports(LlmSpanSpec spec) {
        return true;
    }

    /**
     * Execute all requests in the spec. Called inside an active OTel root span; implementations own
     * any cross-request state (e.g. multi-turn history).
     */
    void executeSpec(LlmSpanSpec spec, SpecClientContext ctx) throws Exception;

    /** Optional hook: run this client in an isolated child-first classloader. */
    default Optional<Isolation> isolation() {
        return Optional.empty();
    }

    /**
     * @param classpathSystemProperty system property holding the {@link java.io.File#pathSeparator
     *     path-separated} classpath for the child loader (set by btx/build.gradle)
     * @param implClassName the {@code SpecClient} implementation to load inside the child loader
     */
    record Isolation(String classpathSystemProperty, String implClassName) {}
}
