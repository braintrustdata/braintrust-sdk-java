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
 * <p>Then run: {@code ./gradlew :braintrust-java-agent:instrumentation:openai_2_8_0:muzzle}
 */
class MuzzlePlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        // Register the DSL extension
        def extension = project.extensions.create('muzzle', MuzzleExtension)

        // Instrumentation projects wire this into check. Their classes task already
        // includes generateMuzzle, so generated references exist before input snapshots.
        def muzzle = project.tasks.register('muzzle', MuzzleTask)
        project.afterEvaluate {
            // The plugin is also applied to bootstrap and other non-instrumentation
            // projects. Do not infer unrelated producers (or a bootstrap self-cycle).
            if (!extension.directives.isEmpty()) {
                muzzle.configure { task ->
                    task.dependsOn(project.tasks.named('classes'),
                            ':braintrust-java-agent:bootstrap:classes')
                    task.instrumentationClasspath.from(project.sourceSets.main.output,
                            project.configurations.compileClasspath)
                    task.bootstrapClasspath.from(project.provider {
                        def bootstrap = project.project(':braintrust-java-agent:bootstrap')
                        def agent = project.project(':braintrust-java-agent')
                        // Raw files deliberately avoid attaching root-project task
                        // dependencies through its configuration/source-set model.
                        bootstrap.sourceSets.main.output.files.toList() +
                                agent.configurations.bootstrapLibs.resolve().toList()
                    })
                }
            }
        }
    }
}
