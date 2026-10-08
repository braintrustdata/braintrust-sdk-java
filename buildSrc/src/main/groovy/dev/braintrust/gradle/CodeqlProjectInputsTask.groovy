package dev.braintrust.gradle

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile

/** Resolves inputs in the owning project, without requesting any project artifact builds. */
abstract class CodeqlProjectInputsTask extends DefaultTask {
    @OutputFile
    abstract RegularFileProperty getOutputFile()

    CodeqlProjectInputsTask() {
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }

    @TaskAction
    void collectInputs() {
        def configurations = new TreeMap<String, Object>()
        def buildDirectories = project.rootProject.allprojects.collectEntries {
            [(it.path): it.layout.buildDirectory.get().asFile.toPath()]
        }
        [project: project.configurations, buildscript: project.buildscript.configurations].each { scope, container ->
            container.findAll { it.canBeResolved }.sort { it.name }.each { configuration ->
                // Fixed dependencies plus a fresh lookup are required before the offline scan.
                if (configuration.state == org.gradle.api.artifacts.Configuration.State.UNRESOLVED) {
                    configuration.resolutionStrategy.failOnNonReproducibleResolution()
                }
                def graph = configuration.incoming.resolutionResult
                graph.allDependencies.each { dependency ->
                    if (dependency instanceof UnresolvedDependencyResult) {
                        throw new GradleException("Cannot fingerprint ${project.path}:${configuration.name}", dependency.failure)
                    }
                }
                def modules = graph.allComponents.findAll { it.id instanceof ModuleComponentIdentifier }
                    .collect { it.id.displayName }.sort()
                def artifacts = configuration.incoming.artifactView {
                    componentFilter { !(it instanceof ProjectComponentIdentifier) }
                }.artifacts.artifacts.collect { artifact ->
                    def id = artifact.id.componentIdentifier
                    def input = [component: id instanceof ModuleComponentIdentifier ? id.displayName : 'file',
                                 name: artifact.file.name,
                                 attributes: CodeqlProjectInputsTask.attributes(artifact.variant.attributes)]
                    def generated = buildDirectories.find { owner, directory -> artifact.file.toPath().startsWith(directory) }
                    if (generated) {
                        // Source/configuration and producer dependencies are fingerprinted instead:
                        // these outputs may not exist until the cache-miss compilation runs.
                        input.attributes.remove('artifactType')
                        input.generated = "${generated.key}:${generated.value.relativize(artifact.file.toPath())}".toString()
                    } else {
                        input.file = artifact.file.absolutePath
                    }
                    input
                }
                configurations["${scope}:${configuration.name}".toString()] = [
                    attributes: CodeqlProjectInputsTask.attributes(configuration.attributes), modules: modules, artifacts: artifacts]
            }
        }
        def compilers = new TreeMap<String, Object>()
        project.tasks.withType(JavaCompile).each { compile ->
            def metadata = compile.javaCompiler.get().metadata
            compilers[compile.name] = [
                languageVersion: metadata.languageVersion.asInt(), vendor: metadata.vendor,
                javaRuntimeVersion: metadata.javaRuntimeVersion, jvmVersion: metadata.jvmVersion,
                releaseFile: new File(metadata.installationPath.asFile, 'release').absolutePath,
                sourceCompatibility: compile.sourceCompatibility, targetCompatibility: compile.targetCompatibility,
                release: compile.options.release.orNull, encoding: compile.options.encoding,
                compilerArgs: compile.options.compilerArgs,
                forkJvmArgs: compile.options.forkOptions.jvmArgs]
        }
        def output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.setText(JsonOutput.toJson([project: project.path, configurations: configurations, compilers: compilers]), 'UTF-8')
    }

    private static Map<String, String> attributes(container) {
        def result = new TreeMap<String, String>()
        container.keySet().each { key -> result[key.name] = container.getAttribute(key).toString() }
        result
    }
}
