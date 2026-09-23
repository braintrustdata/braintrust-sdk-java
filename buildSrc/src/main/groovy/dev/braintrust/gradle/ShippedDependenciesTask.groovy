package dev.braintrust.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Writes the external module coordinates of a project's shipped configurations, one per line.
 *
 * Registered per project so each graph is resolved in its owning project's context.
 */
abstract class ShippedDependenciesTask extends DefaultTask {

    /** Root component of each shipped configuration, keyed by configuration name. */
    @Input
    abstract MapProperty<String, ResolvedComponentResult> getRootComponents()

    @OutputFile
    abstract RegularFileProperty getOutputFile()

    ShippedDependenciesTask() {
        description = 'Lists the external dependency versions shipped by this project'
    }

    @TaskAction
    void writeCoordinates() {
        def packages = new TreeSet<String>()
        rootComponents.get().each { configuration, root ->
            def seen = new HashSet<ResolvedComponentResult>()
            def pending = new ArrayDeque<ResolvedComponentResult>([root])
            while (!pending.isEmpty()) {
                def component = pending.poll()
                if (!seen.add(component)) {
                    continue
                }
                if (component.id instanceof ModuleComponentIdentifier) {
                    def id = (ModuleComponentIdentifier) component.id
                    packages.add("${id.group}:${id.module}@${id.version}".toString())
                }
                component.dependencies.each { dependency ->
                    if (dependency instanceof UnresolvedDependencyResult) {
                        throw new GradleException("Cannot scan ${path}:${configuration}", dependency.failure)
                    }
                    pending.add(((ResolvedDependencyResult) dependency).selected)
                }
            }
        }
        outputFile.get().asFile.text = packages.collect { it + '\n' }.join('')
    }
}
