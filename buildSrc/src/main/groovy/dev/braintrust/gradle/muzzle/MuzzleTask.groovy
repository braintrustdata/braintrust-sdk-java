package dev.braintrust.gradle.muzzle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Gradle task that runs muzzle checks against multiple library versions.
 *
 * <p>For each {@link MuzzleDirective}, it resolves all matching versions from Maven Central,
 * creates an isolated classloader for each version, and verifies that the instrumentation's
 * muzzle references (and helper classes) either match or don't match as expected.
 */
@CacheableTask
abstract class MuzzleTask extends DefaultTask {

    @Classpath
    abstract ConfigurableFileCollection getInstrumentationClasspath()

    @Classpath
    abstract ConfigurableFileCollection getBootstrapClasspath()

    @OutputFile
    abstract RegularFileProperty getReportFile()

    private List<Map> resolvedTargets

    @Input
    List<Map> getDirectiveInputs() {
        directives().collect { directive ->
            [
                    group: directive.group,
                    module: directive.module,
                    versions: directive.versions ?: '',
                    assertPass: directive.assertPass,
                    pinnedVersions: directive.pinnedVersions.toList(),
                    skipVersions: directive.skipVersions.toList().sort(),
                    additionalDependencies: directive.additionalDependencies.toList(),
                    excludedDependencies: directive.excludedDependencies.toList(),
                    ignoredInstrumentation: directive.ignoredInstrumentation.toList()
            ]
        }
    }

    @Input
    Map<String, String> getCheckerRuntime() {
        // The checker runs inside the Gradle JVM, not the Java compilation toolchain.
        ['java.version', 'java.runtime.version', 'java.vendor', 'java.vm.name',
         'java.vm.version', 'java.vm.vendor', 'os.name', 'os.arch'].collectEntries {
            [(it): System.getProperty(it, '')]
        }
    }

    @Input
    List<Map> getTargetInputs() {
        targetPlan().collect { target ->
            [directive: target.directive, coordinate: target.coordinate, artifacts: target.artifacts]
        }
    }

    @Classpath
    List<File> getTargetClasspath() {
        // Keep the file order as loaded. targetInputs also records per-target boundaries,
        // coordinates and artifact order, which a flattened classpath alone cannot express.
        targetPlan().collectMany { it.files }
    }

    private List<MuzzleDirective> directives() {
        project.extensions.getByType(MuzzleExtension).directives
    }

    private List<Map> targetPlan() {
        if (resolvedTargets != null) {
            return resolvedTargets
        }
        List<Map> targets = []
        def configuredDirectives = directives()
        for (int index = 0; index < configuredDirectives.size(); index++) {
            MuzzleDirective directive = configuredDirectives[index]
            List<String> versions
            try {
                versions = directive.pinnedVersions
                        ? directive.pinnedVersions.toList() : discoverVersions(directive)
            } catch (Exception e) {
                throw new GradleException("[muzzle] Failed to resolve versions for ${directive}: ${e.message}", e)
            }
            if (versions.isEmpty()) {
                logger.warn("[muzzle] No versions found for ${directive.group}:${directive.module} in range ${directive.versions}")
            }
            for (String version : versions) {
                String coordinate = "${directive.group}:${directive.module}:${version}"
                try {
                    def library = resolveLibraryVersion(directive, version)
                    targets.add([directive: index, coordinate: coordinate, version: version,
                                 files: library.files, artifacts: library.artifacts])
                } catch (Exception e) {
                    throw new GradleException("[muzzle] Failed to resolve ${coordinate}; refusing an incomplete check", e)
                }
            }
        }
        // Only memoize within this task instance. In particular, metadata discovery must
        // run again on the next invocation, even with an otherwise warm build cache.
        resolvedTargets = targets
        return resolvedTargets
    }

    protected List<String> discoverVersions(MuzzleDirective directive) {
        MavenVersions.resolve(directive.group, directive.module, directive.versions, directive.skipVersions)
    }

    MuzzleTask() {
        group = 'verification'
        description = 'Checks instrumentation muzzle references against library versions'
        reportFile.convention(project.layout.buildDirectory.file('reports/muzzle/result.txt'))
        // Discovery uses live project configurations and must not be replayed from a
        // configuration-cache snapshot. Build-cache result reuse is independent of this.
        notCompatibleWithConfigurationCache('Muzzle discovers current published targets before build-cache lookup')
    }

    @TaskAction
    void run() {
        // Resolve once before checking, using exactly the inputs Gradle fingerprinted.
        def targets = targetPlan()
        def report = reportFile.get().asFile
        java.nio.file.Files.deleteIfExists(report.toPath())
        def bootstrapUrls = bootstrapClasspath.files.collect { it.toURI().toURL() } as URL[]
        def bootstrapCL = new URLClassLoader(bootstrapUrls, (ClassLoader) null)
        def instrumentationUrls = instrumentationClasspath.files.collect { it.toURI().toURL() } as URL[]
        def instrumentationCL = new URLClassLoader(instrumentationUrls, ClassLoader.systemClassLoader)

        try {
            def configuredDirectives = directives()

            int totalVersions = 0
            int totalFailures = 0
            List<String> failureMessages = []

            for (def target : targets) {
                MuzzleDirective directive = configuredDirectives[target.directive]
                String version = target.version
                logger.lifecycle("[muzzle] Checking: ${directive.group}:${directive.module}:${version}")
                totalVersions++
                def result = checkVersion(target.files, directive, version, bootstrapCL, instrumentationCL, directive.ignoredInstrumentation as Set)

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

            logger.lifecycle("[muzzle] Checked ${totalVersions} version(s), ${totalFailures} failure(s)")

            if (totalFailures > 0) {
                throw new GradleException(
                        "[muzzle] ${totalFailures} version(s) failed:\n  " + failureMessages.join('\n  '))
            }
            // No timestamps, absolute paths or partial results: only completed checks
            // produce the output that Gradle may restore from its native build cache.
            report.parentFile.mkdirs()
            report.setText("Checked ${totalVersions} version(s), 0 failures\n" +
                    targets.collect { target ->
                        "${target.coordinate} ${configuredDirectives[target.directive].assertPass ? 'pass' : 'fail'}"
                    }.join('\n') + '\n', 'UTF-8')
        } finally {
            instrumentationCL?.close()
            bootstrapCL.close()
        }
    }

    /**
     * Resolves a specific version of the library, returning the JAR files.
     * Uses Gradle's dependency resolution for transitive deps.
     */
    private Map resolveLibraryVersion(MuzzleDirective directive, String version) {

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

        def files = config.resolve().toList()
        def artifactsByFile = config.resolvedConfiguration.resolvedArtifacts.collectEntries { artifact ->
            [(artifact.file): artifact.id.displayName]
        }
        return [files: files, artifacts: files.collect { file ->
            // Artifact identity is separate from @Classpath's content normalization.
            [id: artifactsByFile[file], name: file.name]
        }]
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
