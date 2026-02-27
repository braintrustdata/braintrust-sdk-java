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

    private static void validate(MuzzleDirective directive) {
        if (!directive.group) {
            throw new IllegalArgumentException("muzzle directive requires 'group'")
        }
        if (!directive.module) {
            throw new IllegalArgumentException("muzzle directive requires 'module'")
        }
        if (!directive.versions) {
            throw new IllegalArgumentException("muzzle directive requires 'versions'")
        }
    }
}
