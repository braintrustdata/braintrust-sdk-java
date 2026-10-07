package dev.braintrust.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Exact, history-independent inputs for the standalone, full-repository CodeQL scan. */
abstract class CodeqlCacheKeyTask extends DefaultTask {
    @InputFiles
    abstract ConfigurableFileCollection getProjectInputs()

    // The fetch task owns this file. Do not fingerprint it before that task runs.
    @Internal
    abstract RegularFileProperty getOpenApiSpec()

    @OutputFile
    abstract RegularFileProperty getKeyFile()

    @OutputFile
    abstract RegularFileProperty getManifestFile()

    @OutputFile
    abstract RegularFileProperty getSpecSnapshot()

    private final Map<String, String> digests = [:]
    private final byte[] digestBuffer = new byte[65536]

    CodeqlCacheKeyTask() {
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }

    @TaskAction
    void writeKey() {
        Files.deleteIfExists(keyFile.get().asFile.toPath())
        Files.deleteIfExists(manifestFile.get().asFile.toPath())
        digests.clear()
        if (!project.gradle.startParameter.refreshDependencies || project.gradle.startParameter.offline) {
            throw new GradleException('codeqlCacheKey requires --refresh-dependencies and an online dependency lookup')
        }
        def projects = projectInputs.files.collect { input ->
            def data = new JsonSlurper().parse(input, 'UTF-8')
            data.configurations.values().each { configuration ->
                configuration.artifacts.each { artifact ->
                    if (artifact.file) artifact.sha256 = digestPath(new File(artifact.remove('file').toString()))
                }
            }
            data.compilers.values().each { compiler ->
                compiler.releaseSha256 = digestFile(new File(compiler.remove('releaseFile').toString()))
            }
            data
        }.sort { it.project }

        def snapshot = specSnapshot.get().asFile
        snapshot.parentFile.mkdirs()
        Files.copy(openApiSpec.get().asFile.toPath(), snapshot.toPath(), StandardCopyOption.REPLACE_EXISTING)
        def manifest = [schema: 1, source: sourceInputs(project.rootDir), projects: projects,
                        openApiSpec: digestFile(snapshot), codeql: codeqlInputs(),
                        runtime: runtimeInputs()]
        def canonical = canonicalJson(manifest)
        def output = manifestFile.get().asFile
        output.parentFile.mkdirs()
        output.setText(JsonOutput.prettyPrint(canonical) + '\n', 'UTF-8')
        // Publish the key last: failures never produce a usable cache lookup key.
        keyFile.get().asFile.setText(sha256(canonical.getBytes('UTF-8')) + '\n', 'UTF-8')
    }

    Map sourceInputs(File root) {
        def tree = command(root, ['git', 'rev-parse', 'HEAD^{tree}']).trim()
        // Binary diff includes both staged and unstaged content relative to HEAD, not its commit ID.
        def diff = commandBytes(root, ['git', '-c', 'core.quotePath=false', 'diff', '--no-ext-diff',
                                       '--no-textconv', '--binary', '--ignore-submodules=none', 'HEAD', '--'])
        def tracked = command(root, ['git', 'ls-files', '--stage', '-z'])
        if (tracked.split('\u0000').any { it.startsWith('160000 ') }) {
            throw new GradleException('CodeQL cache fingerprint does not support submodule worktrees')
        }
        def untracked = new TreeMap<String, String>()
        command(root, ['git', 'ls-files', '--others', '--exclude-standard', '-z']).split('\u0000')
            .findAll { !it.isEmpty() }.each { name -> untracked[name] = digestPath(new File(root, name)) }
        [tree: tree, trackedDiff: sha256(diff), untracked: untracked]
    }

    Map codeqlInputs() {
        def executable = System.getenv('CODEQL_PATH')
        if (!executable || !new File(executable).isFile()) {
            throw new GradleException('CODEQL_PATH must name the installed CodeQL executable')
        }
        def version = new JsonSlurper().parseText(command(project.rootDir, [executable, 'version', '--format=json']))
        if (!version.version || !version.sha || !version.unpackedLocation) {
            throw new GradleException('CodeQL version output is missing version, sha, or unpackedLocation')
        }
        if (version.configFileFound) {
            throw new GradleException('Custom CodeQL CLI configuration is unsupported for cached scans')
        }
        def installation = new File(version.unpackedLocation.toString()).canonicalFile
        def packs = new JsonSlurper().parseText(command(project.rootDir,
            [executable, 'resolve', 'qlpacks', '--format=json']))
        if (!(packs instanceof Map) || !packs['codeql/java-queries'] || !packs['codeql/java-all']) {
            throw new GradleException('The installed CodeQL bundle must contain Java query and library packs')
        }
        def packInputs = new TreeMap<String, Object>()
        packs.each { name, locations ->
            if (!(locations instanceof List) || locations.isEmpty()) {
                throw new GradleException("No resolved CodeQL pack locations for ${name}")
            }
            packInputs[name.toString()] = locations.collect { location ->
                def directory = new File(location.toString()).canonicalFile
                if (!directory.toPath().startsWith(installation.toPath())) {
                    throw new GradleException("Non-bundled CodeQL pack is unsupported: ${name}")
                }
                def metadata = new TreeMap<String, String>()
                ['qlpack.yml', 'codeql-pack.yml', 'codeql-pack.lock.yml'].each { filename ->
                    def file = new File(directory, filename)
                    if (file.isFile()) metadata[filename] = digestFile(file)
                }
                // legacy-upgrades is not a versioned query pack.
                if (metadata.isEmpty() && name != 'legacy-upgrades') {
                    throw new GradleException("Missing metadata for CodeQL pack ${name}")
                }
                [metadata: metadata,
                 content: name == 'codeql/java-queries' || name == 'codeql/java-all' ? digestPath(directory) : null]
            }.unique { canonicalJson(it) }.sort { canonicalJson(it) }
        }
        // Java query-pack content includes its bundled transitive library packs under .codeql/libraries.
        [version: version.version, sha: version.sha, executable: digestFile(new File(executable)),
         suite: 'java-code-scanning.qls', packs: packInputs]
    }

    Map runtimeInputs() {
        def properties = new TreeMap<String, String>()
        ['java.version', 'java.vendor', 'java.runtime.version', 'java.vm.name', 'java.vm.version',
         'java.vm.vendor', 'os.name', 'os.version', 'os.arch', 'file.encoding', 'user.language',
         'user.country', 'user.timezone'].each { name -> properties[name] = System.getProperty(name, '') }
        def environment = new TreeMap<String, String>()
        System.getenv().each { name, value ->
            if (name.startsWith('CODEQL_EXTRACTOR_') || name.startsWith('LGTM_') ||
                name in ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'GRADLE_OPTS',
                         'LANG', 'LC_ALL', 'TZ', 'ImageOS', 'ImageVersion']) {
                // Hash values: diagnostics must not disclose options containing credentials.
                environment[name] = sha256(value.getBytes('UTF-8'))
            }
        }
        [gradle: project.gradle.gradleVersion, properties: properties, environment: environment,
         projectProperties: project.gradle.startParameter.projectProperties.collectEntries { name, value ->
             [(name): sha256(value.getBytes('UTF-8'))]
         }]
    }

    String digestPath(File file) {
        if (file.isFile()) return digestFile(file)
        if (!file.isDirectory()) throw new GradleException("Missing fingerprint input: ${file}")
        def entries = new TreeMap<String, String>()
        Files.walk(file.toPath(), FileVisitOption.FOLLOW_LINKS).withCloseable { paths ->
            paths.filter { !Files.isDirectory(it) }.forEach { path ->
                entries[file.toPath().relativize(path).toString().replace(File.separator, '/')] = digestFile(path.toFile())
            }
        }
        sha256(canonicalJson(entries).getBytes('UTF-8'))
    }

    String digestFile(File file) {
        def path = file.canonicalPath
        if (!digests.containsKey(path)) {
            if (!file.isFile()) throw new GradleException("Missing fingerprint input: ${file}")
            def digest = MessageDigest.getInstance('SHA-256')
            byte[] buffer = digestBuffer
            file.withInputStream { input ->
                int count
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count)
            }
            digests[path] = digest.digest().encodeHex().toString()
        }
        digests[path]
    }

    static String canonicalJson(Object input) {
        JsonOutput.toJson(canonicalize(input))
    }

    private static Object canonicalize(Object input) {
        if (input instanceof Map) {
            def sorted = new TreeMap<String, Object>()
            input.each { key, value -> sorted[key.toString()] = canonicalize(value) }
            return sorted
        }
        if (input instanceof Collection) return input.collect { canonicalize(it) }
        input
    }

    static String sha256(byte[] input) {
        MessageDigest.getInstance('SHA-256').digest(input).encodeHex().toString()
    }

    static String command(File directory, List<String> arguments) {
        new String(commandBytes(directory, arguments), 'UTF-8')
    }

    private static byte[] commandBytes(File directory, List<String> arguments) {
        def process = new ProcessBuilder(arguments).directory(directory).start()
        def stdout = new ByteArrayOutputStream()
        def stderr = new ByteArrayOutputStream()
        process.waitForProcessOutput(stdout, stderr)
        if (process.exitValue() != 0) {
            throw new GradleException("Cannot fingerprint input (${arguments.join(' ')}): ${stderr.toString('UTF-8')}")
        }
        stdout.toByteArray()
    }
}
