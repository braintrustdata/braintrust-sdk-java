package dev.braintrust.gradle.muzzle

/**
 * A single muzzle check directive — either a "pass" or "fail" assertion
 * against a range of library versions.
 */
class MuzzleDirective {
    /** Maven groupId */
    String group

    /** Maven artifactId */
    String module

    /**
     * Maven/Aether version range. Examples:
     * <ul>
     *   <li>{@code "[2.0,)"} — 2.0 and above</li>
     *   <li>{@code "[2.0,3.0)"} — 2.0 up to (not including) 3.0</li>
     *   <li>{@code "[,]"} — all versions</li>
     * </ul>
     */
    String versions

    /** true for pass{} directives, false for fail{} directives */
    boolean assertPass = true

    /** Versions to skip (e.g. known-broken releases) */
    Set<String> skipVersions = []

    /** Additional dependencies to add to the test classpath */
    List<String> additionalDependencies = []

    /** Transitive dependencies to exclude from the test classpath */
    List<String> excludedDependencies = []

    /**
     * Fully-qualified class names of {@code InstrumentationModule} implementations to skip when
     * checking this directive. Use this when a single Gradle project contains modules for multiple
     * libraries and a given directive's classpath only satisfies one of them.
     */
    List<String> ignoredInstrumentation = []

    /**
     * Explicit list of versions to check instead of resolving a range from Maven Central.
     * When set, {@code versions} is ignored and no network fetch is performed.
     */
    List<String> pinnedVersions = []

    void pinVersions(String... versions) {
        pinnedVersions.addAll(versions)
    }

    void skipVersions(String... versions) {
        skipVersions.addAll(versions)
    }

    void extraDependency(String dep) {
        additionalDependencies.add(dep)
    }

    void excludeDependency(String dep) {
        excludedDependencies.add(dep)
    }

    @Override
    String toString() {
        def versionStr = pinnedVersions ? "pinned[${pinnedVersions.join(', ')}]" : versions
        "${assertPass ? 'pass' : 'fail'} { ${group}:${module}:${versionStr} }"
    }
}
