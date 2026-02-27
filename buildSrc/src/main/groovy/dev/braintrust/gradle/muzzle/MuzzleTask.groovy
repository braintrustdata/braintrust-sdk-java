package dev.braintrust.gradle.muzzle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction

/**
 * Gradle task that runs muzzle checks against multiple library versions.
 *
 * <p>For each {@link MuzzleDirective}, it resolves all matching versions from Maven Central,
 * creates an isolated classloader for each version, and verifies that the instrumentation's
 * muzzle references (and helper classes) either match or don't match as expected.
 */
class MuzzleTask extends DefaultTask {

    MuzzleTask() {
        group = 'verification'
        description = 'Checks instrumentation muzzle references against library versions'
    }

    @TaskAction
    void run() {
        def extension = project.extensions.findByType(MuzzleExtension)
        if (!extension || extension.directives.isEmpty()) {
            logger.lifecycle('[muzzle] No muzzle directives configured — skipping')
            return
        }

        // Build the instrumentation classpath — the project's compiled classes + instrumentation-api + bytebuddy.
        // This is what we load InstrumentationModules from.
        def instrumentationClasspath = collectInstrumentationClasspath()

        int totalVersions = 0
        int totalFailures = 0
        List<String> failureMessages = []

        for (MuzzleDirective directive : extension.directives) {
            logger.lifecycle("[muzzle] Checking: ${directive}")

            List<String> versions
            try {
                versions = MavenVersions.resolve(
                        directive.group, directive.module, directive.versions, directive.skipVersions)
            } catch (Exception e) {
                throw new GradleException("[muzzle] Failed to resolve versions for ${directive}: ${e.message}", e)
            }

            if (versions.isEmpty()) {
                logger.warn("[muzzle] No versions found for ${directive.group}:${directive.module} in range ${directive.versions}")
                continue
            }

            logger.lifecycle("[muzzle] Found ${versions.size()} version(s) to check")

            for (String version : versions) {
                totalVersions++

                List<File> libraryJars
                try {
                    libraryJars = resolveLibraryVersion(directive, version)
                } catch (Exception e) {
                    logger.warn("[muzzle] Failed to resolve ${directive.group}:${directive.module}:${version}: ${e.message}")
                    continue
                }

                def result = checkVersion(instrumentationClasspath, libraryJars, directive, version)

                if (result.passed && directive.assertPass) {
                    logger.lifecycle("[muzzle]   ${version} PASS")
                } else if (!result.passed && !directive.assertPass) {
                    logger.lifecycle("[muzzle]   ${version} PASS (expected failure, correctly failed)")
                } else if (!result.passed && directive.assertPass) {
                    totalFailures++
                    def msg = "[muzzle]   ${version} FAIL — expected to pass but got mismatches:"
                    logger.error(msg)
                    result.messages.each { logger.error("[muzzle]     ${it}") }
                    failureMessages.add("${directive.group}:${directive.module}:${version} — ${result.messages.join('; ')}")
                } else {
                    // passed but assertPass=false
                    totalFailures++
                    def msg = "[muzzle]   ${version} FAIL — expected to fail but muzzle passed"
                    logger.error(msg)
                    failureMessages.add("${directive.group}:${directive.module}:${version} — unexpectedly passed")
                }
            }
        }

        logger.lifecycle("[muzzle] Checked ${totalVersions} version(s), ${totalFailures} failure(s)")

        if (totalFailures > 0) {
            throw new GradleException(
                    "[muzzle] ${totalFailures} version(s) failed:\n  " + failureMessages.join('\n  '))
        }
    }

    /**
     * Collects the classpath needed to load InstrumentationModule classes from the current project.
     * Includes the project's own compiled classes, instrumentation-api, and ByteBuddy.
     */
    private List<File> collectInstrumentationClasspath() {
        def files = []

        // The project's own compiled classes
        project.sourceSets.main.output.classesDirs.each { files.add(it) }

        // Add compile classpath (includes instrumentation-api, bytebuddy, etc.)
        project.configurations.compileClasspath.resolve().each { files.add(it) }

        return files
    }

    /**
     * Resolves a specific version of the library, returning the JAR files.
     * Uses Gradle's dependency resolution for transitive deps.
     */
    private List<File> resolveLibraryVersion(MuzzleDirective directive, String version) {
        def configName = "muzzleCheck_${directive.group}_${directive.module}_${version}".replace('.', '_').replace('-', '_')

        // Create a detached configuration for this specific version
        def deps = []
        deps.add(project.dependencies.create("${directive.group}:${directive.module}:${version}"))

        // Add extra dependencies
        directive.additionalDependencies.each { dep ->
            deps.add(project.dependencies.create(dep))
        }

        Configuration config = project.configurations.detachedConfiguration(deps as org.gradle.api.artifacts.Dependency[])

        // Apply exclusions
        directive.excludedDependencies.each { excl ->
            def parts = excl.split(':')
            if (parts.length >= 2) {
                config.exclude(group: parts[0], module: parts[1])
            }
        }

        config.transitive = true
        config.resolutionStrategy.failOnNonReproducibleResolution()

        return config.resolve().toList()
    }

    /**
     * Checks a single library version against the instrumentation's muzzle references.
     */
    private CheckResult checkVersion(
            List<File> instrumentationClasspath,
            List<File> libraryJars,
            MuzzleDirective directive,
            String version) {

        // Create isolated classloaders
        def instrumentationUrls = instrumentationClasspath.collect { it.toURI().toURL() } as URL[]
        def libraryUrls = libraryJars.collect { it.toURI().toURL() } as URL[]

        // Instrumentation classloader: agent code + instrumentation-api + bytebuddy
        // Parent = system classloader (for JDK classes)
        def instrumentationCL = new URLClassLoader(instrumentationUrls, ClassLoader.systemClassLoader)

        // Library classloader: just the library JARs + transitive deps
        // Parent = null (bootstrap only) so it's fully isolated
        def libraryCL = new URLClassLoader(libraryUrls, (ClassLoader) null)

        try {
            return doCheck(instrumentationCL, libraryCL)
        } finally {
            instrumentationCL.close()
            libraryCL.close()
        }
    }

    /**
     * Performs the actual muzzle check: loads modules via ServiceLoader, checks references,
     * verifies helper injection.
     */
    private CheckResult doCheck(URLClassLoader instrumentationCL, URLClassLoader libraryCL) {
        def messages = []

        // Load classes from the instrumentation classloader
        def moduleClass = instrumentationCL.loadClass('dev.braintrust.agent.instrumentation.InstrumentationModule')
        def serviceLoader = ServiceLoader.load(moduleClass, instrumentationCL)

        boolean anyModule = false
        boolean allPassed = true

        for (def module : serviceLoader) {
            anyModule = true
            def moduleName = module.name()

            // 1. Check classLoaderMatcher
            def classLoaderMatcher = module.classLoaderMatcher()
            boolean clMatch = classLoaderMatcher.matches(libraryCL)
            if (!clMatch) {
                messages.add("classLoaderMatcher rejected library classloader for module '${moduleName}'")
                allPassed = false
                continue
            }

            // 2. Load muzzle references (prefer $Muzzle, fall back to runtime)
            def referenceMatcher = loadMuzzleReferences(module, instrumentationCL)
            if (referenceMatcher == null) {
                messages.add("Could not load muzzle references for module '${moduleName}'")
                allPassed = false
                continue
            }

            // 3. Check references against the library classloader
            boolean refsMatch = referenceMatcher.matches(libraryCL)
            if (!refsMatch) {
                def mismatches = referenceMatcher.getMismatchedReferenceSources(libraryCL)
                mismatches.each { messages.add("${it}") }
                allPassed = false
                continue
            }

            // 4. Verify helper class injection
            def helperNames = module.getHelperClassNames()
            for (String helperName : helperNames) {
                try {
                    def resourceName = helperName.replace('.', '/') + '.class'
                    def helperStream = instrumentationCL.getResourceAsStream(resourceName)
                    if (helperStream == null) {
                        messages.add("Helper class not found: ${helperName}")
                        allPassed = false
                        continue
                    }
                    def helperBytes = helperStream.readAllBytes()
                    helperStream.close()

                    // Try to define the helper in a classloader that delegates to the library CL
                    // This verifies that the helper's dependencies are satisfied by the library
                    def testCL = new HelperTestClassLoader(libraryCL, helperName, helperBytes)
                    def defined = testCL.loadClass(helperName)

                    // Force resolution of the class to catch linkage errors
                    defined.getDeclaredMethods()
                    defined.getDeclaredFields()

                } catch (Throwable t) {
                    messages.add("Helper injection would fail for ${helperName}: ${t.class.simpleName}: ${t.message}")
                    allPassed = false
                }
            }
        }

        if (!anyModule) {
            messages.add("No InstrumentationModule implementations found via ServiceLoader")
            allPassed = false
        }

        return new CheckResult(passed: allPassed, messages: messages)
    }

    /**
     * Loads muzzle references — tries $Muzzle class first, falls back to runtime scanning.
     */
    private Object loadMuzzleReferences(Object module, ClassLoader instrumentationCL) {
        def moduleClassName = module.getClass().getName()
        def muzzleClassName = moduleClassName + '$Muzzle'

        try {
            def muzzleClass = instrumentationCL.loadClass(muzzleClassName)
            return muzzleClass.getMethod('create').invoke(null)
        } catch (ClassNotFoundException ignored) {
            // Fall back to runtime scanning
            logger.info("[muzzle] No \$Muzzle class for ${module.name()}, using runtime scanning")
        } catch (Exception e) {
            logger.warn("[muzzle] Failed to load \$Muzzle for ${module.name()}: ${e.message}")
        }

        // Runtime fallback: use InstrumentationInstaller.loadStaticMuzzleReferences approach
        // We reflectively call the buildMuzzleReferencesAtRuntime equivalent
        try {
            def installerClass = instrumentationCL.loadClass(
                    'dev.braintrust.agent.instrumentation.InstrumentationInstaller')
            def method = installerClass.getDeclaredMethod(
                    'loadStaticMuzzleReferences',
                    instrumentationCL.loadClass('dev.braintrust.agent.instrumentation.InstrumentationModule'),
                    ClassLoader.class)
            method.setAccessible(true)
            def result = method.invoke(null, module, instrumentationCL)
            if (result != null) return result
        } catch (Exception ignored) {}

        // Last resort: build at runtime
        try {
            def generatorClass = instrumentationCL.loadClass('dev.braintrust.agent.muzzle.MuzzleGenerator')
            def collectMethod = generatorClass.getDeclaredMethod('collectReferences',
                    instrumentationCL.loadClass('dev.braintrust.agent.instrumentation.InstrumentationModule'),
                    ClassLoader.class)
            collectMethod.setAccessible(true)
            def refs = collectMethod.invoke(null, module, instrumentationCL)

            def matcherClass = instrumentationCL.loadClass('dev.braintrust.agent.muzzle.ReferenceMatcher')
            return matcherClass.getConstructors()[0].newInstance(refs)
        } catch (Exception e) {
            logger.warn("[muzzle] Failed to build references at runtime for ${module.name()}: ${e.message}")
            return null
        }
    }

    /**
     * A classloader that can define a single helper class and delegates everything else
     * to the library classloader. This tests whether the helper's dependencies are
     * satisfied by the library version.
     */
    private static class HelperTestClassLoader extends ClassLoader {
        private final String helperName
        private final byte[] helperBytes

        HelperTestClassLoader(ClassLoader libraryParent, String helperName, byte[] helperBytes) {
            super(libraryParent)
            this.helperName = helperName
            this.helperBytes = helperBytes
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name == helperName) {
                return defineClass(name, helperBytes, 0, helperBytes.length)
            }
            throw new ClassNotFoundException(name)
        }
    }

    static class CheckResult {
        boolean passed
        List<String> messages = []
    }
}
