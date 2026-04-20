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
        // Create a bootstrap classloader for this task run.
        def bootstrapUrls = collectBootstrapClasspath()
        def bootstrapCL = new URLClassLoader(bootstrapUrls as URL[], (ClassLoader) null)

        // Initialize the instrumentation classloader once per task run — it doesn't
        // change between library versions.
        def instrumentationClasspath = collectInstrumentationClasspath()
        def instrumentationUrls = instrumentationClasspath.collect { it.toURI().toURL() } as URL[]
        def instrumentationCL = new URLClassLoader(instrumentationUrls, ClassLoader.systemClassLoader)

        try {
            def extension = project.extensions.findByType(MuzzleExtension)
            if (!extension || extension.directives.isEmpty()) {
                logger.lifecycle('[muzzle] No muzzle directives configured — skipping')
                return
            }

            int totalVersions = 0
            int totalFailures = 0
            List<String> failureMessages = []

            for (MuzzleDirective directive : extension.directives) {
                logger.lifecycle("[muzzle] Checking: ${directive}")

                List<String> versions
                if (directive.pinnedVersions) {
                    versions = directive.pinnedVersions
                    logger.lifecycle("[muzzle] Using ${versions.size()} pinned version(s)")
                } else {
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

                    def result = checkVersion(libraryJars, directive, version, bootstrapCL, instrumentationCL, directive.ignoredInstrumentation as Set)

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
        } finally {
            instrumentationCL?.close()
            bootstrapCL.close()
        }
    }

    /**
     * Collects the classpath needed to load InstrumentationModule classes from the current project.
     * Includes the project's own compiled classes, instrumenter, and ByteBuddy.
     */
    private List<File> collectInstrumentationClasspath() {
        def files = []

        // The project's own compiled classes
        project.sourceSets.main.output.classesDirs.each { files.add(it) }

        // Add compile classpath (includes instrumenter, bytebuddy, etc.)
        project.configurations.compileClasspath.resolve().each { files.add(it) }

        return files
    }

    /**
     * Collects the bootstrap classpath: the bootstrap module's compiled classes + bootstrapLibs
     * (OTel API and its transitive deps). This simulates what ends up on the real JVM bootstrap
     * classpath when the agent runs.
     */
    private List<URL> collectBootstrapClasspath() {
        def agentProject = project.project(':braintrust-java-agent')
        def urls = []

        // Bootstrap module compiled classes
        def bootstrapProject = project.project(':braintrust-java-agent:bootstrap')
        bootstrapProject.sourceSets.main.output.classesDirs.each { urls.add(it.toURI().toURL()) }

        // bootstrapLibs configuration (OTel API + transitive deps like context, common)
        agentProject.configurations.bootstrapLibs.resolve().each { urls.add(it.toURI().toURL()) }

        return urls
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

        // Add extra dependencies — if no version specified, use the same version being checked
        directive.additionalDependencies.each { dep ->
            def parts = dep.split(':')
            if (parts.length == 2) {
                // group:module only — use the version under test
                deps.add(project.dependencies.create("${dep}:${version}"))
            } else {
                deps.add(project.dependencies.create(dep))
            }
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
            List<File> libraryJars,
            MuzzleDirective directive,
            String version,
            URLClassLoader bootstrapCL,
            URLClassLoader instrumentationCL,
            Set<String> ignoredInstrumentation = []) {

        def libraryUrls = libraryJars.collect { it.toURI().toURL() } as URL[]

        // Library classloader: just the library JARs + transitive deps
        // Parent = bootstrap placeholder so it sees OTel API etc.
        def libraryCL = new URLClassLoader(libraryUrls, bootstrapCL)

        try {
            return doCheck(instrumentationCL, libraryCL, ignoredInstrumentation)
        } finally {
            libraryCL.close()
        }
    }

    /**
     * Performs the actual muzzle check: loads modules via ServiceLoader, checks references,
     * verifies helper injection.
     */
    private CheckResult doCheck(URLClassLoader instrumentationCL, URLClassLoader libraryCL, Set<String> ignoredInstrumentation = []) {
        def messages = []

        // Load classes from the instrumentation classloader
        def moduleClass = instrumentationCL.loadClass('dev.braintrust.instrumentation.InstrumentationModule')
        def serviceLoader = ServiceLoader.load(moduleClass, instrumentationCL)

        boolean anyModule = false
        boolean allPassed = true

        for (def module : serviceLoader) {
            anyModule = true
            def moduleName = module.name()

            // 0. Skip modules explicitly excluded for this directive
            if (ignoredInstrumentation.contains(module.getClass().getName())) {
                logger.info("[muzzle]   module '${moduleName}' skipped (listed in ignoredInstrumentation)")
                continue
            }

            // 1. Check classLoaderMatcher
            def classLoaderMatcher = module.classLoaderMatcher()
            boolean clMatch = classLoaderMatcher.matches(libraryCL)
            if (!clMatch) {
                messages.add("classLoaderMatcher rejected library classloader for module '${moduleName}'")
                allPassed = false
                continue
            }

            // 2. Read all helper class bytes so they can be made available during muzzle checks.
            //    At runtime, helpers are injected into the target classloader before advice runs,
            //    so the muzzle check should see them too.
            def helperNames = module.getHelperClassNames()
            Map<String, byte[]> allHelperBytes = [:]
            for (String helperName : helperNames) {
                def resourceName = helperName.replace('.', '/') + '.class'
                def helperStream = instrumentationCL.getResourceAsStream(resourceName)
                if (helperStream == null) {
                    messages.add("Helper class not found: ${helperName}")
                    allPassed = false
                } else {
                    allHelperBytes[helperName] = helperStream.readAllBytes()
                    helperStream.close()
                }
            }

            // Build a classloader that layers helpers on top of the library CL,
            // simulating what the app classloader looks like after helper injection.
            def libraryWithHelpersCL = allHelperBytes.isEmpty()
                    ? libraryCL
                    : new HelperTestClassLoader(libraryCL, allHelperBytes)

            // 3. Load muzzle references (prefer $Muzzle, fall back to runtime)
            def referenceMatcher = loadMuzzleReferences(module, instrumentationCL)
            if (referenceMatcher == null) {
                messages.add("Could not load muzzle references for module '${moduleName}'")
                allPassed = false
                continue
            }

            // 4. Check references against library + helpers
            boolean refsMatch = referenceMatcher.matches(libraryWithHelpersCL)
            if (!refsMatch) {
                def mismatches = referenceMatcher.getMismatchedReferenceSources(libraryWithHelpersCL)
                mismatches.each { messages.add("${it}") }
                allPassed = false
                continue
            }

            // 5. Verify helper classes can actually be loaded (catches linkage errors)
            def ignoredClasses = module.getMuzzleIgnoredClassNames() as Set
            if (!allHelperBytes.isEmpty()) {
                for (String helperName : helperNames) {
                    try {
                        def defined = libraryWithHelpersCL.loadClass(helperName)
                        // Force resolution to catch linkage errors
                        defined.getDeclaredMethods()
                        defined.getDeclaredFields()
                    } catch (NoClassDefFoundError t) {
                        // Check if the missing class is in the ignored set
                        def missingClass = t.message?.replace('/', '.')
                        if (ignoredClasses.contains(missingClass)) {
                            // Expected — this class is explicitly ignored by the module
                        } else {
                            messages.add("Helper injection would fail for ${helperName}: ${t.class.simpleName}: ${t.message}")
                            allPassed = false
                        }
                    } catch (Throwable t) {
                        messages.add("Helper injection would fail for ${helperName}: ${t.class.simpleName}: ${t.message}")
                        allPassed = false
                    }
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
                    'dev.braintrust.instrumentation.InstrumentationInstaller')
            def method = installerClass.getDeclaredMethod(
                    'loadStaticMuzzleReferences',
                    instrumentationCL.loadClass('dev.braintrust.instrumentation.InstrumentationModule'),
                    ClassLoader.class)
            method.setAccessible(true)
            def result = method.invoke(null, module, instrumentationCL)
            if (result != null) return result
        } catch (Exception ignored) {}

        // Last resort: build at runtime
        try {
            def generatorClass = instrumentationCL.loadClass('dev.braintrust.instrumentation.muzzle.MuzzleGenerator')
            def collectMethod = generatorClass.getDeclaredMethod('collectReferences',
                    instrumentationCL.loadClass('dev.braintrust.instrumentation.InstrumentationModule'),
                    ClassLoader.class)
            collectMethod.setAccessible(true)
            def refs = collectMethod.invoke(null, module, instrumentationCL)

            def matcherClass = instrumentationCL.loadClass('dev.braintrust.instrumentation.muzzle.ReferenceMatcher')
            return matcherClass.getConstructors()[0].newInstance(refs)
        } catch (Exception e) {
            logger.warn("[muzzle] Failed to build references at runtime for ${module.name()}: ${e.message}")
            return null
        }
    }

    /**
     * A classloader that layers helper classes on top of a library classloader.
     * Extends URLClassLoader with the same URLs so that helpers and library classes
     * share the same runtime package — required for package-private access.
     */
    private static class HelperTestClassLoader extends URLClassLoader {
        private final Map<String, byte[]> helperBytes

        HelperTestClassLoader(URLClassLoader libraryCL, Map<String, byte[]> helperBytes) {
            super(libraryCL.getURLs(), libraryCL.getParent())
            this.helperBytes = helperBytes
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            def bytes = helperBytes.get(name)
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length)
            }
            return super.findClass(name)
        }
    }

    static class CheckResult {
        boolean passed
        List<String> messages = []
    }
}
