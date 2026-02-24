package dev.braintrust.agent.muzzle;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isSubTypeOf;
import static net.bytebuddy.matcher.ElementMatchers.not;

import dev.braintrust.agent.instrumentation.InstrumentationModule;
import java.io.File;
import java.io.IOException;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;

/**
 * ByteBuddy build-time {@link Plugin} that generates {@code $Muzzle} side-classes for each
 * concrete {@link InstrumentationModule} subclass found during compilation.
 *
 * <p>The plugin matches concrete subclasses of {@code InstrumentationModule}, instantiates them
 * reflectively, scans their advice bytecode via {@link ReferenceCreator}, and writes a
 * {@code $Muzzle.class} file to the output directory containing a pre-computed
 * {@link ReferenceMatcher}.
 *
 * <p>The module class itself is NOT modified — the generated class is a sibling file.
 *
 * <p>This plugin is invoked by Gradle via ByteBuddy's {@code Plugin.Engine} after compilation.
 *
 * @see MuzzleGenerator
 */
public class MuzzleGradlePlugin extends Plugin.ForElementMatcher {

    private final File targetDir;

    public MuzzleGradlePlugin(File targetDir) {
        super(not(isAbstract()).and(isSubTypeOf(InstrumentationModule.class)));
        this.targetDir = targetDir;
    }

    @Override
    public DynamicType.Builder<?> apply(
            DynamicType.Builder<?> builder,
            TypeDescription typeDescription,
            ClassFileLocator classFileLocator) {

        // Instantiate the module reflectively to access its typeInstrumentations() and helpers
        InstrumentationModule module;
        try {
            module = (InstrumentationModule) Thread.currentThread()
                    .getContextClassLoader()
                    .loadClass(typeDescription.getName())
                    .getConstructor()
                    .newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(
                    "Failed to instantiate " + typeDescription.getName() + " for muzzle generation", e);
        }

        MuzzleGenerator.generate(
                module, targetDir, Thread.currentThread().getContextClassLoader());

        // Return builder unmodified — we don't change the module class itself
        return builder;
    }

    @Override
    public void close() throws IOException {
        // no-op
    }
}
