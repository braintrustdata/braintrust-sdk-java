package dev.braintrust.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.TaskAction

abstract class CheckDependenciesTask extends DefaultTask {

    /** Coordinate lists written by each shipped project's {@link ShippedDependenciesTask}. */
    @InputFiles
    abstract ConfigurableFileCollection getShippedDependencies()

    /** Sources and shipped-graph policy defining the compatibility of cached API responses. */
    @InputFiles
    abstract ConfigurableFileCollection getScannerSourceFiles()

    @LocalState
    abstract DirectoryProperty getAdvisoryCacheDirectory()

    CheckDependenciesTask() {
        group = 'verification'
        description = 'Checks shipped dependencies against GitHub-reviewed advisories (requires authenticated gh)'
        // Advisories change independently of the inputs, so never treat a previous scan as up to date.
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        advisoryCacheDirectory.convention(project.rootProject.layout.buildDirectory.dir('dependency-advisories'))
        scannerSourceFiles.from(
                project.rootProject.file('buildSrc/src/main/groovy/dev/braintrust/gradle/CheckDependenciesTask.groovy'),
                project.rootProject.file('buildSrc/src/main/groovy/dev/braintrust/gradle/DependencyAdvisories.groovy'),
                project.rootProject.file('.github/dependency-graph.json'))
    }

    @TaskAction
    void checkDependencies() {
        def directory = advisoryCacheDirectory.get().asFile
        DependencyAdvisories.clearCompletion(directory)
        logger.lifecycle('Resolving shipped dependencies from the current working tree (including uncommitted changes)...')
        def packages = shippedPackages()
        def result = DependencyAdvisories.scan(packages,
                DependencyAdvisories.identity(scannerSourceFiles.files), directory,
                { url, etag -> requestAdvisories(url, etag) },
                { message -> logger.lifecycle(message.toString()) })
        if (result.findings.isEmpty()) {
            logger.lifecycle("PASS: No matching GitHub-reviewed advisories in ${packages.size()} shipped dependency versions.")
            return
        }
        result.findings.each { finding ->
            def advisory = finding.advisory
            logger.lifecycle("\n${advisory.severity.toUpperCase(Locale.ROOT)} ${advisory.ghsa_id}: ${advisory.summary}")
            logger.lifecycle("  Relevant packages: ${finding.packages.join(', ')}")
            logger.lifecycle("  ${advisory.html_url}")
        }
        throw new GradleException("${result.findings.size()} active GitHub-reviewed advisories match the shipped dependency graph.")
    }

    private SortedSet<String> shippedPackages() {
        def packages = new TreeSet<String>()
        shippedDependencies.files.each { file ->
            file.readLines().findAll { !it.isBlank() }.each { packages.add(it) }
        }
        if (packages.isEmpty()) {
            throw new GradleException('The shipped dependency graph is empty; refusing to report a clean scan.')
        }
        return packages
    }

    /** Kept injectable for task integration tests; production transport remains authenticated gh. */
    protected Map requestAdvisories(String url, String etag) {
        DependencyAdvisories.request(url, etag)
    }
}
