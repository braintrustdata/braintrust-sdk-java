package dev.braintrust.gradle.muzzle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

import static org.junit.jupiter.api.Assertions.*

/** Real Gradle build-cache round trips, with only local Maven artifacts and checker fixtures. */
class MuzzleTaskCacheTest {
    @TempDir
    File directory

    private File report(String task = 'muzzle') {
        new File(directory, "build/reports/muzzle/${task == 'muzzle' ? 'result' : task}.txt")
    }

    private void put(String path, String text) {
        def file = new File(directory, path)
        file.parentFile.mkdirs()
        file.setText(text, 'UTF-8')
    }

    private void artifact(String module, String version, Map<String, String> entries, String dependencies = '') {
        def base = "repo/fixture/${module}/${version}/${module}-${version}"
        put("${base}.pom", """<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>
<artifactId>${module}</artifactId><version>${version}</version><dependencies>${dependencies}</dependencies></project>""")
        def file = new File(directory, "${base}.jar")
        new JarOutputStream(new FileOutputStream(file)).withCloseable { jar ->
            entries.each { name, value ->
                def entry = new JarEntry(name)
                entry.time = 0
                jar.putNextEntry(entry)
                jar.write(value.getBytes('UTF-8'))
                jar.closeEntry()
            }
        }
    }

    @BeforeEach
    void fixture() {
        put('settings.gradle', '''
rootProject.name = 'muzzle-cache-fixture'
include ':braintrust-java-agent:bootstrap'
buildCache { local { directory = new File(settingsDir, 'cache') } }
''')
        put('gradle.properties', 'org.gradle.caching=true\norg.gradle.workers.max=2\n')
        put('versions.txt', '1.0\n')
        put('directive.properties', 'range=[1.0,)\npins=1.0\nassertPass=true\n')
        put('build.gradle', '''
import dev.braintrust.gradle.muzzle.MuzzleTask
import dev.braintrust.gradle.muzzle.MuzzleDirective
import dev.braintrust.gradle.muzzle.MavenVersions
import org.gradle.api.tasks.CacheableTask

plugins {
    id 'java'
    id 'dev.braintrust.muzzle'
}

repositories { maven { url = uri('repo') } }
project(':braintrust-java-agent') { configurations { bootstrapLibs } }
project(':braintrust-java-agent:bootstrap') {
    apply plugin: 'java'
    apply plugin: 'dev.braintrust.muzzle'
}

def settings = new Properties()
file('directive.properties').withInputStream { settings.load(it) }
def check = new MuzzleDirective(
    group: 'fixture', module: 'target', versions: settings.getProperty('range'),
    assertPass: settings.getProperty('assertPass', 'true').toBoolean(),
    pinnedVersions: settings.getProperty('pins', '').tokenize(','),
    skipVersions: settings.getProperty('skip', '').tokenize(',') as Set,
    additionalDependencies: settings.getProperty('extra', '').tokenize(','),
    excludedDependencies: settings.getProperty('excluded', '').tokenize(','),
    ignoredInstrumentation: settings.getProperty('ignored', '').tokenize(','))
muzzle.directives.add(check)
dependencies { compileOnly files('dependency.jar') }
project(':braintrust-java-agent').dependencies.add('bootstrapLibs', files('bootstrap.jar'))

// Keep generated references separate from compiler-owned outputs.
def generatedMuzzleDir = layout.buildDirectory.dir('generated/muzzle')
tasks.register('generateMuzzle') {
    dependsOn tasks.named('compileJava')
    outputs.dir(generatedMuzzleDir)
    doLast {
        def marker = new File(generatedMuzzleDir.get().asFile, 'generated.txt')
        marker.parentFile.mkdirs()
        marker.text = 'generated'
    }
}
sourceSets.main.output.dir(generatedMuzzleDir, builtBy: 'generateMuzzle')
tasks.named('classes') { dependsOn tasks.named('generateMuzzle') }

@CacheableTask
abstract class DiscoveredMuzzleTask extends MuzzleTask {
    @Override
    protected List<String> discoverVersions(MuzzleDirective directive) {
        logger.lifecycle('Reading fresh fixture metadata')
        def range = MavenVersions.parseRange(directive.versions)
        project.file('versions.txt').readLines().findAll {
            it && range.contains(it) && !directive.skipVersions.contains(it)
        }
    }
}
tasks.register('discoveredMuzzle', DiscoveredMuzzleTask) {
    dependsOn tasks.named('classes'), ':braintrust-java-agent:bootstrap:classes'
    instrumentationClasspath.from(sourceSets.main.output, configurations.compileClasspath)
    bootstrapClasspath.from(provider {
        project(':braintrust-java-agent:bootstrap').sourceSets.main.output.files.toList()
    })
    reportFile = layout.buildDirectory.file('reports/muzzle/discoveredMuzzle.txt')
}
''')
        put('src/main/java/dev/braintrust/instrumentation/InstrumentationModule.java', '''
package dev.braintrust.instrumentation;
public abstract class InstrumentationModule {
    public abstract String name();
    public abstract Matcher classLoaderMatcher();
    public String[] getHelperClassNames() { return new String[0]; }
    public String[] getMuzzleIgnoredClassNames() { return new String[0]; }
    public static class Matcher {
        public boolean matches(ClassLoader loader) { return loader.getResource("compatible.txt") != null; }
        public String[] getMismatchedReferenceSources(ClassLoader loader) { return new String[] {"fixture mismatch"}; }
    }
}
''')
        put('src/main/java/fixture/TestModule.java', '''
package fixture;
import dev.braintrust.instrumentation.InstrumentationModule;
public class TestModule extends InstrumentationModule {
    public String name() { return "fixture"; }
    public Matcher classLoaderMatcher() { return new Matcher(); }
    public static class Muzzle {
        public static Matcher create() { return new Matcher(); }
    }
}
''')
        put('src/main/resources/META-INF/services/dev.braintrust.instrumentation.InstrumentationModule', 'fixture.TestModule\n')
        put('braintrust-java-agent/bootstrap/src/main/java/fixture/Bootstrap.java',
                'package fixture; public class Bootstrap { public static int value() { return 1; } }\n')
        artifact('dependency', '1.0', ['dependency.txt': 'one'])
        new File(directory, 'dependency.jar').bytes = new File(directory, 'repo/fixture/dependency/1.0/dependency-1.0.jar').bytes
        artifact('bootstrap-library', '1.0', ['bootstrap-library.txt': 'one'])
        new File(directory, 'bootstrap.jar').bytes = new File(directory, 'repo/fixture/bootstrap-library/1.0/bootstrap-library-1.0.jar').bytes
        artifact('transitive', '1.0', ['transitive.txt': 'one'])
        artifact('target', '1.0', ['compatible.txt': 'one'], '''
<dependency><groupId>fixture</groupId><artifactId>transitive</artifactId><version>1.0</version></dependency>''')
        artifact('target', '2.0', ['compatible.txt': 'two'])
        artifact('target', '3.0', ['incompatible.txt': 'three'])
    }

    private GradleRunner runner(String task) {
        GradleRunner.create()
                .withProjectDir(directory)
                .withTestKitDir(new File(directory, 'testkit'))
                .withPluginClasspath(System.getProperty('muzzle.test.classpath').split(File.pathSeparator).collect { new File(it) })
                .withArguments(task, '--build-cache', '--offline', '--stacktrace', '--no-configuration-cache')
    }

    private def initialSuccess(String task = 'muzzle') {
        def result = runner(task).build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":${task}").outcome, result.output)
        assertTrue(report(task).isFile())
        return result
    }

    @Test
    void restoresCompletedResultAfterOutputDeletion() {
        def initial = initialSuccess()
        def bytes = report().bytes
        assertTrue(report().delete())
        def result = runner('muzzle').build()
        assertEquals(TaskOutcome.FROM_CACHE, result.task(':muzzle').outcome, initial.output + result.output)
        assertArrayEquals(bytes, report().bytes)
    }

    @ParameterizedTest
    @ValueSource(strings = ['code', 'resource', 'dependency', 'bootstrap', 'bootstrapLibrary', 'generated', 'transitive'])
    void changingClasspathInputsRunsChecksAgain(String input) {
        initialSuccess()
        switch (input) {
            case 'code':
                def source = new File(directory, 'src/main/java/fixture/TestModule.java')
                source.text = source.text.replace('return "fixture"', 'return "changed"')
                break
            case 'resource':
                put('src/main/resources/instrumentation.txt', 'changed')
                break
            case 'dependency':
                artifact('dependency', '2.0', ['dependency.txt': 'two'])
                new File(directory, 'dependency.jar').bytes = new File(directory, 'repo/fixture/dependency/2.0/dependency-2.0.jar').bytes
                break
            case 'bootstrap':
                put('braintrust-java-agent/bootstrap/src/main/java/fixture/Bootstrap.java',
                        'package fixture; public class Bootstrap { public static int value() { return 2; } }\n')
                break
            case 'bootstrapLibrary':
                artifact('bootstrap-library', '1.0', ['bootstrap-library.txt': 'changed'])
                new File(directory, 'bootstrap.jar').bytes = new File(directory, 'repo/fixture/bootstrap-library/1.0/bootstrap-library-1.0.jar').bytes
                break
            case 'generated':
                // Keep the task implementation unchanged; mutate an additional generated classpath resource.
                put('build/generated/muzzle/extra-generated.txt', 'changed')
                break
            case 'transitive':
                artifact('transitive', '1.0', ['transitive.txt': 'changed'])
                break
        }
        assertTrue(report().delete())
        assertEquals(TaskOutcome.SUCCESS, runner('muzzle').build().task(':muzzle').outcome)
    }

    @ParameterizedTest
    @ValueSource(strings = ['range=[0.0,)\npins=1.0', 'range=[1.0,)\npins=1.0\nskip=9.0',
            'range=[1.0,)\npins=1.0\nextra=fixture:dependency:1.0',
            'range=[1.0,)\npins=1.0\nexcluded=fixture:transitive',
            'range=[1.0,)\npins=1.0\nignored=fixture.NotPresent',
            'range=[1.0,)\npins=2.0'])
    void changingDirectiveInputsRunsChecksAgain(String properties) {
        initialSuccess()
        put('directive.properties', properties + '\nassertPass=true\n')
        assertTrue(report().delete())
        assertEquals(TaskOutcome.SUCCESS, runner('muzzle').build().task(':muzzle').outcome)
    }

    @Test
    void discoversNewTargetsBeforeCacheLookup() {
        put('directive.properties', 'range=[1.0,)\nassertPass=true\n')
        initialSuccess('discoveredMuzzle')
        assertTrue(report('discoveredMuzzle').delete())
        def hit = runner('discoveredMuzzle').build()
        assertEquals(TaskOutcome.FROM_CACHE, hit.task(':discoveredMuzzle').outcome)
        put('versions.txt', '1.0\n2.0\n')
        assertTrue(report('discoveredMuzzle').delete())
        def changed = runner('discoveredMuzzle').build()
        assertEquals(TaskOutcome.SUCCESS, changed.task(':discoveredMuzzle').outcome)
        assertTrue(report('discoveredMuzzle').text.contains('fixture:target:2.0 pass'))
    }

    @Test
    void emptyDiscoveredSetRemainsValidButDoesNotHideNewTargets() {
        put('directive.properties', 'range=[1.0,)\nassertPass=true\n')
        put('versions.txt', '')
        initialSuccess('discoveredMuzzle')
        put('versions.txt', '1.0\n')
        assertTrue(report('discoveredMuzzle').delete())
        assertEquals(TaskOutcome.SUCCESS, runner('discoveredMuzzle').build().task(':discoveredMuzzle').outcome)
        assertTrue(report('discoveredMuzzle').text.contains('Checked 1 version(s)'))
    }

    @Test
    void mismatchNeverBecomesCachedSuccess() {
        initialSuccess()
        put('directive.properties', 'range=[1.0,)\npins=3.0\nassertPass=true\n')
        assertEquals(TaskOutcome.FAILED, runner('muzzle').buildAndFail().task(':muzzle').outcome)
        assertFalse(report().exists())
        assertEquals(TaskOutcome.FAILED, runner('muzzle').buildAndFail().task(':muzzle').outcome)
        assertFalse(report().exists())
        put('directive.properties', 'range=[1.0,)\npins=3.0\nassertPass=false\n')
        initialSuccess()
        assertTrue(report().delete())
        assertEquals(TaskOutcome.FROM_CACHE, runner('muzzle').build().task(':muzzle').outcome)
    }

    @Test
    void changedPassFailExpectationCannotReuseSuccess() {
        initialSuccess()
        put('directive.properties', 'range=[1.0,)\npins=1.0\nassertPass=false\n')
        assertEquals(TaskOutcome.FAILED, runner('muzzle').buildAndFail().task(':muzzle').outcome)
        assertFalse(report().exists())
    }

    @Test
    void unresolvedConcreteTargetFailsBeforeCacheReuse() {
        initialSuccess()
        assertTrue(report().delete())
        put('directive.properties', 'range=[1.0,)\npins=1.0,9.0\nassertPass=true\n')
        def result = runner('muzzle').buildAndFail()
        assertTrue(result.output.contains('refusing an incomplete check'))
        assertFalse(report().exists())
        assertTrue(runner('muzzle').buildAndFail().output.contains('refusing an incomplete check'))
    }
}
