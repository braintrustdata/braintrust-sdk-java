package dev.braintrust.gradle

import groovy.json.JsonOutput
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

import static org.junit.jupiter.api.Assertions.*

/** Real key generation and dependency resolution, using only local fixture inputs. */
class CodeqlCacheKeyTest {
    @TempDir
    File directory
    File projectDir
    File bundle

    private static void put(File root, String path, String text) {
        def file = new File(root, path)
        file.parentFile.mkdirs()
        file.setText(text, 'UTF-8')
    }

    private String git(String... arguments) {
        CodeqlCacheKeyTask.command(projectDir, ['git'] + arguments.toList()).trim()
    }

    private void artifact(String content) {
        def base = 'repo/fixture/external/1.0/external-1.0'
        put(projectDir, "${base}.pom", '''<project><modelVersion>4.0.0</modelVersion>
<groupId>fixture</groupId><artifactId>external</artifactId><version>1.0</version></project>''')
        new JarOutputStream(new FileOutputStream(new File(projectDir, "${base}.jar"))).withCloseable { jar ->
            def entry = new JarEntry('input.txt')
            entry.time = 0
            jar.putNextEntry(entry)
            jar.write(content.getBytes('UTF-8'))
            jar.closeEntry()
        }
    }

    @BeforeEach
    void fixture() {
        projectDir = new File(directory, 'project')
        bundle = new File(directory, 'bundle')
        projectDir.mkdirs()
        bundle.mkdirs()
        put(projectDir, 'settings.gradle', "rootProject.name = 'codeql-fixture'\ninclude ':lib'\n")
        put(projectDir, 'gradle.properties', 'org.gradle.workers.max=2\n')
        put(projectDir, '.gitignore', '.gradle/\nbuild/\nrepo/\ntestkit/\nspec.yaml\n')
        put(projectDir, 'spec.yaml', 'openapi: 3.0.0\n')
        def classpath = System.getProperty('codeql.test.classpath').split(File.pathSeparator)
            .findAll { new File(it).exists() }
            .collect { "'${it.replace('\\', '\\\\').replace("'", "\\'")}'" }.join(',')
        put(projectDir, 'build.gradle', "buildscript { dependencies { classpath files(${classpath}) } }\n" + '''
import dev.braintrust.gradle.CodeqlCacheKeyTask
import dev.braintrust.gradle.CodeqlProjectInputsTask
allprojects {
    apply plugin: 'java'
    version = 'git rev-parse HEAD'.execute(null, rootDir).text.trim()
    repositories { maven { url = rootProject.uri('repo') } }
    tasks.withType(JavaCompile).configureEach {
        doFirst { throw new GradleException('Application compilation must not run during lookup') }
    }
}
dependencies {
    implementation 'fixture:external:1.0'
    implementation project(':lib')
    compileOnly files(layout.buildDirectory.dir('generated/classes'))
}
def resetKey = tasks.register('resetKey', Delete) {
    delete layout.buildDirectory.file('codeql/cache-key.txt')
}
def collectors = allprojects.collect { owner ->
    owner.tasks.register('codeqlProjectInputs', CodeqlProjectInputsTask) {
        dependsOn resetKey
        outputFile = owner.layout.buildDirectory.file('codeql/project-inputs.json')
    }
}
tasks.register('codeqlCacheKey', CodeqlCacheKeyTask) {
    projectInputs.from(collectors)
    openApiSpec = layout.projectDirectory.file('spec.yaml')
    specSnapshot = layout.buildDirectory.file('codeql/spec-input/openapi/spec.yaml')
    keyFile = layout.buildDirectory.file('codeql/cache-key.txt')
    manifestFile = layout.buildDirectory.file('codeql/cache-inputs.json')
}
''')
        // These sources are intentionally not compilable. Resolution must not request project JARs.
        put(projectDir, 'src/main/java/Broken.java', 'not compilable\n')
        put(projectDir, 'lib/src/main/java/Broken.java', 'also not compilable\n')
        artifact('first')
        put(bundle, 'java-queries/qlpack.yml', 'name: codeql/java-queries\nversion: 1.0.0\n')
        put(bundle, 'java-queries/query.ql', 'select 1\n')
        put(bundle, 'java-all/qlpack.yml', 'name: codeql/java-all\nversion: 1.0.0\n')
        put(bundle, 'version.json', JsonOutput.toJson([version: '1.0.0', sha: 'fixture-cli-sha',
            unpackedLocation: bundle.absolutePath, configFileFound: false]))
        put(bundle, 'packs.json', JsonOutput.toJson([
            'codeql/java-queries': [new File(bundle, 'java-queries').absolutePath],
            'codeql/java-all': [new File(bundle, 'java-all').absolutePath]]))
        put(bundle, 'codeql', '''#!/bin/sh
set -eu
bundle=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
case "$1" in
  version) cat "$bundle/version.json" ;;
  resolve) cat "$bundle/packs.json" ;;
  *) exit 2 ;;
esac
''')
        assertTrue(new File(bundle, 'codeql').setExecutable(true))
        git('init')
        git('config', 'user.email', 'fixture@example.invalid')
        git('config', 'user.name', 'Fixture')
        git('add', '.')
        git('commit', '-m', 'Initial content')
    }

    private GradleRunner runner() {
        def environment = new HashMap<String, String>(System.getenv())
        environment.CODEQL_PATH = new File(bundle, 'codeql').absolutePath
        GradleRunner.create().withProjectDir(projectDir)
            .withTestKitDir(new File(projectDir, 'testkit'))
            .withEnvironment(environment)
            .withArguments('codeqlCacheKey', '--refresh-dependencies', '--no-configuration-cache',
                           '--no-build-cache', '--rerun-tasks', '--stacktrace')
    }

    private String key() {
        runner().build()
        def value = new File(projectDir, 'build/codeql/cache-key.txt').getText('UTF-8')
        assertTrue(value ==~ /[0-9a-f]{64}\n/)
        value
    }

    @Test
    void historyOnlyCommitsAndInstallLocationsDoNotChangeKey() {
        def before = key()
        def commit = git('rev-parse', 'HEAD')
        git('commit', '--allow-empty', '-m', 'Different history, same source tree')
        assertNotEquals(commit, git('rev-parse', 'HEAD'))
        assertEquals(before, key())
        put(projectDir, 'build/generated/classes/Compiled.class', 'generated output')
        assertEquals(before, key())
        def moved = new File(directory, 'relocated-bundle')
        assertTrue(bundle.renameTo(moved))
        bundle = moved
        put(bundle, 'version.json', JsonOutput.toJson([version: '1.0.0', sha: 'fixture-cli-sha',
            unpackedLocation: bundle.absolutePath, configFileFound: false]))
        put(bundle, 'packs.json', JsonOutput.toJson([
            'codeql/java-queries': [new File(bundle, 'java-queries').absolutePath],
            'codeql/java-all': [new File(bundle, 'java-all').absolutePath]]))
        assertEquals(before, key())
    }

    @Test
    void trackedAndUntrackedContentInvalidateKey() {
        def initial = key()
        put(projectDir, 'src/main/java/Broken.java', 'different source\n')
        def edited = key()
        assertNotEquals(initial, edited)
        put(projectDir, 'src/main/java/New.java', 'new source\n')
        assertNotEquals(edited, key())
    }

    @Test
    void artifactSpecAndQueryContentInvalidateWithoutCoordinateChanges() {
        def initial = key()
        artifact('replacement at identical coordinates')
        def changedArtifact = key()
        assertNotEquals(initial, changedArtifact)
        put(projectDir, 'spec.yaml', 'openapi: 3.1.0\n')
        def changedSpec = key()
        assertNotEquals(changedArtifact, changedSpec)
        put(bundle, 'java-queries/query.ql', 'select 2\n')
        def changedQuery = key()
        assertNotEquals(changedSpec, changedQuery)
        put(bundle, 'version.json', JsonOutput.toJson([version: '1.0.1', sha: 'updated-cli-sha',
            unpackedLocation: bundle.absolutePath, configFileFound: false]))
        assertNotEquals(changedQuery, key())
    }

    @Test
    void unresolvedInputsRemovePreviousKey() {
        key()
        new File(projectDir, 'build.gradle').append("\ndependencies { implementation 'fixture:missing:1.0' }\n", 'UTF-8')
        def result = runner().buildAndFail()
        assertTrue(result.output.contains('fixture:missing:1.0'), result.output)
        assertFalse(new File(projectDir, 'build/codeql/cache-key.txt').exists())
    }

    @Test
    void dynamicVersionsFailBeforeLookup() {
        key()
        new File(projectDir, 'build.gradle').append("\ndependencies { implementation 'fixture:external:+' }\n", 'UTF-8')
        runner().buildAndFail()
        assertFalse(new File(projectDir, 'build/codeql/cache-key.txt').exists())
    }
}
