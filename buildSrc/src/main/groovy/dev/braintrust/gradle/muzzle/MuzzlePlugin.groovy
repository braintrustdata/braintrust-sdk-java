package dev.braintrust.gradle.muzzle

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Gradle plugin that adds the {@code muzzle { pass { } fail { } }} DSL and a {@code muzzle} task
 * to instrumentation subprojects.
 *
 * <p>Usage in {@code build.gradle}:
 * <pre>
 * apply plugin: 'dev.braintrust.muzzle'
 *
 * muzzle {
 *   pass {
 *     group = 'com.openai'
 *     module = 'openai-java'
 *     versions = '[2.0,)'
 *   }
 *   fail {
 *     group = 'com.openai'
 *     module = 'openai-java'
 *     versions = '[0.1,2.0)'
 *   }
 * }
 * </pre>
 *
 * <p>Then run: {@code ./gradlew :braintrust-java-agent:instrumentation:openai:muzzle}
 */
class MuzzlePlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        // Register the DSL extension
        project.extensions.create('muzzle', MuzzleExtension)

        // Register the muzzle task (not part of check/build — run explicitly).
        // The task depends on 'classes' which already includes generateMuzzle
        // for instrumentation subprojects (wired in the parent build.gradle).
        project.tasks.register('muzzle', MuzzleTask) {
            dependsOn 'classes'
        }
    }
}
