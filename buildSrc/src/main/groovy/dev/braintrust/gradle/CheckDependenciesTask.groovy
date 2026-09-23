package dev.braintrust.gradle

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction

import java.util.concurrent.Callable
import java.util.concurrent.Executors

abstract class CheckDependenciesTask extends DefaultTask {

    /** Coordinate lists written by each shipped project's {@link ShippedDependenciesTask}. */
    @InputFiles
    abstract ConfigurableFileCollection getShippedDependencies()

    CheckDependenciesTask() {
        group = 'verification'
        description = 'Checks shipped dependencies against GitHub-reviewed advisories (requires authenticated gh)'
        // Advisories change independently of the inputs, so never treat a previous scan as up to date.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    void checkDependencies() {
        logger.lifecycle('Resolving shipped dependencies from the current working tree (including uncommitted changes)...')
        def packages = shippedPackages()
        logger.lifecycle("Checking ${packages.size()} shipped dependency versions against GitHub-reviewed advisories...")

        def executor = Executors.newFixedThreadPool(6)
        List<Map> results
        try {
            def futures = packages.collect { coordinate ->
                executor.submit({ -> CheckDependenciesTask.advisories(coordinate) } as Callable<Map>)
            }
            // An API failure must never be reported as a clean or complete scan.
            results = futures.collect { it.get() }
        } catch (Exception e) {
            throw new GradleException("Dependency scan incomplete: ${e.cause?.message ?: e.message}", e)
        } finally {
            executor.shutdownNow()
        }

        def findings = results.findAll { !it.advisories.isEmpty() }
        if (findings.isEmpty()) {
            logger.lifecycle("PASS: No matching GitHub-reviewed advisories in ${packages.size()} shipped dependency versions.")
            return
        }
        findings.each { finding ->
            logger.lifecycle("\n${finding.coordinate}")
            finding.advisories.each { advisory ->
                logger.lifecycle("  ${advisory.severity.toUpperCase(Locale.ROOT)} ${advisory.cve_id ?: advisory.ghsa_id}: ${advisory.summary}")
                logger.lifecycle("  ${advisory.html_url}")
            }
        }
        int count = findings.sum { it.advisories.size() }
        throw new GradleException("${count} advisory matches across ${findings.size()} shipped dependency versions.")
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

    private static Map advisories(String coordinate) {
        def stdout = new StringBuilder()
        def stderr = new StringBuilder()
        def process = new ProcessBuilder([
                'gh', 'api', '--hostname', 'github.com', '--method', 'GET',
                '--paginate', '--slurp', '/advisories',
                '-f', 'type=reviewed', '-f', 'ecosystem=maven',
                '-f', "affects=${coordinate}".toString(), '-f', 'per_page=100'
        ]).start()
        try {
            process.waitForProcessOutput(stdout, stderr)
            if (process.exitValue() != 0) {
                throw new GradleException("gh exited with status ${process.exitValue()} for ${coordinate}:\n${stdout}\n${stderr}")
            }
        } finally {
            process.destroy()
        }
        def pages = new JsonSlurper().parseText(stdout.toString())
        if (!(pages instanceof List) || pages.any { !(it instanceof List) }) {
            throw new GradleException("Invalid GitHub advisory response for ${coordinate}")
        }
        def active = [:]
        pages.flatten().each { advisory ->
            if (!(advisory instanceof Map) || !advisory.ghsa_id) {
                throw new GradleException("Invalid GitHub advisory for ${coordinate}")
            }
            if (!advisory.withdrawn_at) {
                active[advisory.ghsa_id] = advisory
            }
        }
        return [coordinate: coordinate, advisories: active.values().sort { it.ghsa_id }]
    }
}
