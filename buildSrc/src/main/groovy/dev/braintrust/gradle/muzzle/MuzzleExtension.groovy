package dev.braintrust.gradle.muzzle

import org.gradle.api.Action

/**
 * Gradle extension that provides the {@code muzzle { pass { } fail { } }} DSL.
 */
class MuzzleExtension {

    final List<MuzzleDirective> directives = []

    /**
     * Declares that the instrumentation's references MUST match for all versions in the range.
     */
    void pass(Action<MuzzleDirective> action) {
        def directive = new MuzzleDirective()
        directive.assertPass = true
        action.execute(directive)
        validate(directive)
        directives.add(directive)
    }

    /**
     * Declares that the instrumentation's references must NOT match for all versions in the range.
     */
    void fail(Action<MuzzleDirective> action) {
        def directive = new MuzzleDirective()
        directive.assertPass = false
        action.execute(directive)
        validate(directive)
        directives.add(directive)
    }

    /**
     * Returns the minimum passing version without resolving artifacts.
     * Multiple ranges/pin lists for one artifact form a union; different artifacts in the same
     * instrumentation module must agree on their minimum because they share one origin version.
     */
    String getMinimumVersion() {
        def passing = directives.findAll { it.assertPass }
        if (passing.isEmpty()) {
            throw new IllegalArgumentException('An instrumentation origin requires a passing muzzle directive')
        }
        def minima = passing.groupBy { "${it.group}:${it.module}" }.collectEntries { artifact, checks ->
            def candidates = checks.collectMany { directive ->
                if (directive.pinnedVersions) {
                    return directive.pinnedVersions
                }
                def range = MavenVersions.parseRange(directive.versions)
                if (!range.lower || !range.lowerInclusive || directive.skipVersions.contains(range.lower)) {
                    throw new IllegalArgumentException(
                            "Cannot determine minimum passing version for ${directive}: use an included lower bound or pinVersions")
                }
                return [range.lower]
            }
            [(artifact): candidates.min { a, b -> MavenVersions.compareVersions(a, b) }]
        }
        def versions = minima.values().toSet()
        if (versions.size() != 1) {
            throw new IllegalArgumentException("Muzzle artifacts must share one instrumentation origin version: ${minima}")
        }
        return versions.first()
    }

    private static void validate(MuzzleDirective directive) {
        if (!directive.group) {
            throw new IllegalArgumentException("muzzle directive requires 'group'")
        }
        if (!directive.module) {
            throw new IllegalArgumentException("muzzle directive requires 'module'")
        }
        if (!directive.pinnedVersions && !directive.versions) {
            throw new IllegalArgumentException("muzzle directive requires either 'versions' or 'pinVersions'")
        }
    }
}
