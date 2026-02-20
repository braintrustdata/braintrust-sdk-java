package dev.braintrust.agent.instrumentation;

import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.description.type.TypeDescription;

import java.util.List;

import static net.bytebuddy.matcher.ElementMatchers.any;

/**
 * A group of related instrumentations for a single library or framework.
 *
 * <p>Each module declares a name (for logging and enable/disable configuration), a classloader
 * matcher (to skip classloaders that don't contain the target library), and a list of
 * {@link TypeInstrumentation}s that define the actual bytecode transformations.
 *
 * <p>Implementations are discovered via {@link java.util.ServiceLoader} at agent startup.
 */
public abstract class InstrumentationModule {

    private final String name;

    protected InstrumentationModule(String name) {
        this.name = name;
    }

    /** The name of this instrumentation module, used for logging and configuration. */
    public final String name() {
        return name;
    }

    /**
     * Returns a matcher that rejects classloaders which definitely don't contain the target
     * library. This is an optimization — if the matcher doesn't match, none of this module's
     * type instrumentations will be applied for classes loaded by that classloader.
     *
     * <p>The default matches all classloaders.
     */
    public ElementMatcher<ClassLoader> classLoaderMatcher() {
        return any();
    }

    /**
     * Returns the list of type instrumentations that this module provides.
     */
    public abstract List<TypeInstrumentation> typeInstrumentations();
}
