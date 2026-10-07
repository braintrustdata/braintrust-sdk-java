package dev.braintrust.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.*

class CheckDependenciesTaskTest {
    @TempDir
    File directory
    File cacheDirectory
    List<Map> requests
    List responses
    static final List<String> COORDINATES = ['example:library@1.0']

    @BeforeEach
    void fixture() {
        cacheDirectory = new File(directory, 'cache')
        requests = []
        responses = []
    }

    private static Map advisory(String id = 'GHSA-aaaa-bbbb-cccc', String withdrawn = null) {
        [ghsa_id: id, cve_id: null, summary: 'Fixture vulnerability', severity: 'high',
         html_url: "https://github.com/advisories/${id}".toString(), withdrawn_at: withdrawn,
         vulnerabilities: [[package: [ecosystem: 'maven', name: 'example:library'],
                            vulnerable_version_range: '< 2.0']]]
    }

    private static Map ok(List body = [], String etag = '"first"', String link = null) {
        def headers = "HTTP/2.0 200 OK\r\nContent-Type: application/json\r\n"
        if (etag != null) headers += "ETag: ${etag}\r\n"
        if (link != null) headers += "Link: ${link}\r\n"
        [exitCode: 0, stdout: headers + '\r\n' + JsonOutput.toJson(body), stderr: '']
    }

    private static Map unchanged() {
        [exitCode: 1, stdout: 'HTTP/2.0 304 Not Modified\r\nETag: "first"\r\n\r\n', stderr: 'gh: HTTP 304\n']
    }

    private Map scan(List<String> coordinates = COORDINATES, String identity = 'implementation-one') {
        DependencyAdvisories.scan(coordinates, identity, cacheDirectory, { url, etag ->
            requests.add([url: url, etag: etag])
            assertFalse(responses.isEmpty(), 'Unexpected API request')
            def response = responses.remove(0)
            if (response instanceof Exception) throw response
            response
        }, { message -> })
    }

    private File cache() { new File(cacheDirectory, 'cache.json') }
    private File complete() { new File(cacheDirectory, 'complete') }
    private String firstUrl(List<String> coordinates = COORDINATES) { DependencyAdvisories.batches(coordinates)[0].url }

    @Test
    void coldAndWarmCleanAlwaysRevalidateAndKeepStableSnapshot() {
        responses.add(ok())
        def cold = scan()
        assertEquals(0, cold.hits)
        assertEquals([], cold.findings)
        assertEquals([[url: firstUrl(), etag: null]], requests)
        assertTrue(complete().isFile())
        def bytes = cache().bytes
        responses.add(unchanged())
        def warm = scan()
        assertEquals(1, warm.hits)
        assertEquals([], warm.findings)
        assertEquals('"first"', requests.last().etag)
        assertArrayEquals(bytes, cache().bytes)
    }

    @Test
    void coldAndWarmFindingsRetainUniqueAdvisoriesAndRelevantPackageNames() {
        responses.add(ok([advisory(), advisory()]))
        def cold = scan(['example:library@1.0', 'example:library@3.0', 'other:unaffected@1.0'])
        assertEquals(1, cold.findings.size())
        assertEquals(['example:library'], cold.findings[0].packages)
        def snapshot = cache().bytes
        responses.add(unchanged())
        def warm = scan(['example:library@1.0', 'example:library@3.0', 'other:unaffected@1.0'])
        assertEquals(cold.findings, warm.findings)
        assertEquals(1, warm.hits)
        assertArrayEquals(snapshot, cache().bytes)
        assertTrue(complete().exists())
    }

    @Test
    void conflictingDuplicatePagesCannotHideAnActiveFinding() {
        def second = firstUrl() + '&after=next%2Fpage%3D%3D'
        responses.addAll([
            ok([advisory()], '"first"', "<${second}>; rel=\"next\""),
            ok([advisory('GHSA-aaaa-bbbb-cccc', '2026-01-01T00:00:00Z')], '"second"')
        ])
        assertEquals(['GHSA-aaaa-bbbb-cccc'], scan().findings.collect { it.advisory.ghsa_id })
        responses.addAll([unchanged(), unchanged()])
        assertEquals(['GHSA-aaaa-bbbb-cccc'], scan().findings.collect { it.advisory.ghsa_id })
    }

    @Test
    void serverMatchedAdvisoryWithNullableRangeRemainsAFinding() {
        def finding = advisory()
        finding.vulnerabilities[0].vulnerable_version_range = null
        responses.add(ok([finding]))
        assertEquals(['GHSA-aaaa-bbbb-cccc'], scan().findings.collect { it.advisory.ghsa_id })
        responses.add(unchanged())
        assertEquals(['GHSA-aaaa-bbbb-cccc'], scan().findings.collect { it.advisory.ghsa_id })
    }

    @Test
    void changedDependenciesAndImplementationRequireFreshResponses() {
        responses.add(ok())
        scan()
        responses.add(ok())
        def changed = ['example:library@2.0']
        scan(changed)
        assertNull(requests.last().etag)
        assertEquals(firstUrl(changed), requests.last().url)
        responses.add(ok())
        scan(changed, 'implementation-two')
        assertNull(requests.last().etag)
        responses.add(unchanged())
        assertEquals(1, scan(changed, 'implementation-two').hits)
    }

    @Test
    void sourceAndPolicyEditsChangeIdentityAndPolicyMismatchIsNeverReused() {
        def source = new File(directory, 'Scanner.groovy')
        def policy = new File(directory, 'policy.json')
        source.text = 'scanner-one'
        policy.text = 'policy-one'
        def initial = DependencyAdvisories.identity([source, policy])
        assertEquals(initial, DependencyAdvisories.identity([policy, source]))
        responses.add(ok())
        scan(COORDINATES, initial)
        source.text = 'scanner-two'
        def changedSource = DependencyAdvisories.identity([source, policy])
        assertNotEquals(initial, changedSource)
        responses.add(ok())
        scan(COORDINATES, changedSource)
        assertNull(requests.last().etag)
        policy.text = 'policy-two'
        def changedPolicy = DependencyAdvisories.identity([source, policy])
        assertNotEquals(changedSource, changedPolicy)
        responses.add(ok())
        scan(COORDINATES, changedPolicy)
        assertNull(requests.last().etag)
        def stored = new JsonSlurper().parse(cache())
        stored.policy.apiVersion = 'unsupported'
        cache().text = JsonOutput.toJson(stored)
        responses.add(ok())
        scan(COORDINATES, changedPolicy)
        assertNull(requests.last().etag)
        assertThrows(GradleException) { DependencyAdvisories.identity([new File(directory, 'missing')]) }
    }

    @Test
    void newAndWithdrawnAdvisoriesReplaceCachedBodiesOn200() {
        responses.add(ok())
        assertTrue(scan().findings.isEmpty())
        responses.add(ok([advisory()], '"new"'))
        assertEquals(1, scan().findings.size())
        assertEquals('"first"', requests.last().etag)
        responses.add(ok([advisory('GHSA-aaaa-bbbb-cccc', '2026-01-01T00:00:00Z')], '"withdrawn"'))
        assertTrue(scan().findings.isEmpty())
        assertEquals('"new"', requests.last().etag)
        responses.add(unchanged())
        assertTrue(scan().findings.isEmpty())
        assertEquals('"withdrawn"', requests.last().etag)
    }

    @Test
    void unchangedFirstPageStillRevalidatesChangedLaterPage() {
        def second = firstUrl() + '&after=next%2Fpage%3D%3D'
        responses.addAll([ok([], '"first"', "<${second}>; rel=\"next\""), ok([], '"second"')])
        assertEquals(2, scan().pages)
        requests.clear()
        responses.addAll([unchanged(), ok([advisory()], '"changed-second"')])
        def warm = scan()
        assertEquals(1, warm.findings.size())
        assertEquals(1, warm.hits)
        assertEquals([[url: firstUrl(), etag: '"first"'], [url: second, etag: '"second"']], requests)
        responses.addAll([unchanged(), unchanged()])
        assertEquals(2, scan().hits)
    }

    @Test
    void fullTerminalPageRefreshesLinkEvenWhenItsBodyIsUnchanged() {
        def full = (0..<100).collect { advisory(String.format('GHSA-aaaa-bbbb-%04d', it)) }
        responses.add(ok(full))
        assertEquals(100, scan().findings.size())
        def second = firstUrl() + '&after=next%2Fpage%3D%3D'
        responses.addAll([ok(full, '"first"', "<${second}>; rel=\"next\""), ok([advisory()], '"second"')])
        requests.clear()
        def result = scan()
        assertNull(requests[0].etag, 'A full terminal page must refresh pagination metadata unconditionally')
        assertEquals(second, requests[1].url)
        assertEquals(101, result.findings.size())
        responses.addAll([unchanged(), unchanged()])
        assertEquals(2, scan().hits)
    }

    @Test
    void paginationMetadataProvidedOn304ReplacesCachedLinks() {
        def second = firstUrl() + '&after=next%2Fpage%3D%3D'
        responses.addAll([ok([], '"first"', "<${second}>; rel=\"next\""), ok([], '"second"')])
        scan()
        requests.clear()
        responses.add(unchanged() + [
            stdout: "HTTP/2.0 304 Not Modified\nLink: <${firstUrl()}>; rel=\"first\"\n\n".toString()])
        assertEquals(1, scan().pages)
        assertEquals(1, requests.size())
        responses.add(unchanged() + [
            stdout: 'HTTP/2.0 304 Not Modified\nLink: <https://evil.example/advisories>; rel="next"\n\n'])
        assertThrows(GradleException) { scan() }
        assertFalse(complete().exists())
    }

    @Test
    void unrelatedProcessFailureCannotMasqueradeAs304WithValidCandidate() {
        [unchanged() + [stderr: 'connection error'], unchanged() + [exitCode: 2]].each { response ->
            responses.add(ok())
            scan()
            def snapshot = cache().bytes
            responses.add(response)
            assertThrows(GradleException) { scan() }
            assertEquals('"first"', requests.last().etag)
            assertFalse(complete().exists())
            assertArrayEquals(snapshot, cache().bytes)
        }
    }

    @Test
    void failuresRemoveCompletionAndNeverPersistPartialScans() {
        def failures = [
            new IOException('transport unavailable'),
            [exitCode: 1, stdout: '', stderr: 'secret token must not be logged'],
            [exitCode: 1, stdout: 'HTTP/2.0 403 Forbidden\n\n{}', stderr: 'gh: forbidden'],
            [exitCode: 1, stdout: 'HTTP/2.0 200 OK\n\n[]', stderr: 'other failure'],
            [exitCode: 0, stdout: 'HTTP/2.0 200 OK\n\nnot json', stderr: ''],
            [exitCode: 0, stdout: 'HTTP/2.0 200 OK\n\n{}', stderr: ''],
            ok([[ghsa_id: 'GHSA-aaaa-bbbb-cccc']]),
            ok([advisory() + [withdrawn_at: false]]),
            ok([advisory() + [withdrawn_at: 'yesterday']]),
            ok([advisory() + [vulnerabilities: []]]),
            unchanged() + [stderr: 'gh: HTTP 304\nconnection failed'],
            unchanged() + [exitCode: 2],
            unchanged() + [stdout: 'HTTP/2.0 304 Not Modified\n\n[]']
        ]
        def second = firstUrl() + '&after=next%2Fpage%3D%3D'
        failures.each { failure ->
            responses.add(ok())
            scan()
            def snapshot = cache().bytes
            responses.addAll([ok([], '"changed"', "<${second}>; rel=\"next\""), failure])
            def exception = assertThrows(GradleException) { scan() }
            assertTrue(exception.message.contains('Dependency scan incomplete'), exception.message)
            assertFalse(exception.message.contains('secret token'))
            assertFalse(complete().exists())
            assertArrayEquals(snapshot, cache().bytes)
        }
    }

    @Test
    void unexpected304AndCorruptCandidatesCannotBecomeCleanResults() {
        responses.add(unchanged())
        assertThrows(GradleException) { scan() }
        assertFalse(cache().exists())
        assertFalse(complete().exists())
        responses.add(ok())
        scan()
        def saved = cache().text
        def corruptions = [
            { map -> map.pages[firstUrl()].body = [:] },
            { map -> map.pages[firstUrl()].coordinates = ['unrelated:package@1.0'] },
            { map -> map.pages[firstUrl()].next = 'https://evil.example/advisories' },
            { map -> map.pages[firstUrl()].etag = '"bad\r\nAuthorization: secret"' }
        ]
        corruptions.each { corrupt ->
            def stored = new JsonSlurper().parseText(saved)
            corrupt(stored)
            cache().text = JsonOutput.toJson(stored)
            responses.add(unchanged())
            assertThrows(GradleException) { scan() }
            assertNull(requests.last().etag)
            assertFalse(complete().exists())
        }
        cache().text = 'not json'
        responses.add(ok())
        assertTrue(scan().findings.isEmpty())
        assertNull(requests.last().etag)
    }

    @Test
    void unsafeMalformedAndLoopingPaginationFailsBeforeForwardingCredentials() {
        def badUrls = [
            'https://evil.example/advisories', 'http://api.github.com/advisories',
            'https://user@api.github.com/advisories', 'https://api.github.com:444/advisories',
            'https://api.github.com/other', firstUrl() + '#fragment',
            firstUrl().replace('type=reviewed', 'type=unreviewed'),
            firstUrl().replace('example', 'unrelated'), firstUrl() + '&after=',
            firstUrl() + '&after=next&before=previous',
            firstUrl() + '&affects=another%3Apackage%401'
        ]
        badUrls.each { url ->
            requests.clear()
            responses.add(ok([], '"first"', "<${url}>; rel=\"next\""))
            assertThrows(GradleException) { scan() }
            assertEquals(1, requests.size())
            assertFalse(complete().exists())
        }
        ['not a link', "<${firstUrl()}>; rel=\"next\", <${firstUrl()}>; rel=\"next\""].each { link ->
            responses.add(ok([], '"first"', link))
            assertThrows(GradleException) { scan() }
        }
        requests.clear()
        responses.add(ok([], '"first"', "<${firstUrl()}>; rel=\"next\""))
        assertThrows(GradleException) { scan() }
        assertEquals(1, requests.size())
        assertFalse(cache().exists())
    }

    @Test
    void absentEtagsAlwaysRequireFresh200AndEmptyGraphsRemoveCompletion() {
        responses.add(ok([], null))
        scan()
        responses.add(ok([], null))
        scan()
        assertNull(requests.last().etag)
        assertThrows(GradleException) { scan([]) }
        assertFalse(complete().exists())
        assertThrows(GradleException) { scan(['unresolved']) }
    }

    @Test
    void cacheWriteFailureCannotPublishCompletion() {
        assertTrue(cache().mkdirs())
        complete().text = 'previous complete\n'
        responses.add(ok())
        assertThrows(GradleException) { scan() }
        assertFalse(complete().exists())
    }

    @Test
    void failedLaterBatchCannotSaveEarlierSuccessfulBatch() {
        def coordinates = (0..<300).collect { "example:long-library-name-${it}@1.0".toString() }
        assertTrue(DependencyAdvisories.batches(coordinates).size() > 1)
        responses.addAll([ok(), new IOException('second batch failed')])
        assertThrows(GradleException) { scan(coordinates) }
        assertEquals(2, requests.size())
        assertFalse(cache().exists())
        assertFalse(complete().exists())
    }

    @Test
    void batchingHonorsEncodedLengthCountAndSortedUniqueCoordinates() {
        def coordinates = (0..<2200).collect { "example:library-${it}@1.0+metadata".toString() }
        def chunks = DependencyAdvisories.batches(coordinates.reverse() + coordinates[0])
        assertTrue(chunks.size() > 1)
        assertEquals(new TreeSet(coordinates).toList(), chunks.collectMany { it.coordinates })
        chunks.eachWithIndex { chunk, index ->
            assertTrue(chunk.url.length() <= DependencyAdvisories.MAX_URL_LENGTH)
            assertTrue(chunk.coordinates.size() <= DependencyAdvisories.MAX_PACKAGES)
            assertTrue(chunk.url.contains('%2Bmetadata'))
            def affects = chunk.url.substring(chunk.url.indexOf('&affects=') + '&affects='.length())
            assertEquals(chunk.coordinates.join(','), URLDecoder.decode(affects, 'UTF-8'))
            if (index + 1 < chunks.size()) {
                def expanded = chunk.coordinates + chunks[index + 1].coordinates[0]
                assertTrue(DependencyAdvisories.batches(expanded).size() > 1)
            }
        }
        def prefixLength = firstUrl(['a:b@c']).length() - URLEncoder.encode('a:b@c', 'UTF-8').length()
        def coordinate = 'a:b@' + 'v' * (DependencyAdvisories.MAX_URL_LENGTH - prefixLength - URLEncoder.encode('a:b@', 'UTF-8').length())
        assertEquals(DependencyAdvisories.MAX_URL_LENGTH, firstUrl([coordinate]).length())
        assertThrows(GradleException) { DependencyAdvisories.batches([coordinate + 'v']) }
        responses.addAll(chunks.collect { ok() })
        def result = scan(coordinates)
        assertEquals(chunks.size(), result.batches)
        assertEquals(chunks.size(), requests.size())
        requests.clear()
        responses.addAll(chunks.collect { unchanged() })
        assertEquals(chunks.size(), scan(coordinates).hits)
    }

    @Test
    void realGradleTaskAlwaysRunsCachesFindingsAndFailsClosedOnEmptyGraph() {
        def project = new File(directory, 'project')
        project.mkdirs()
        new File(project, 'settings.gradle').text = "rootProject.name = 'advisory-fixture'\n"
        new File(project, 'gradle.properties').text = 'org.gradle.workers.max=2\n'
        def classpath = System.getProperty('advisories.test.classpath').split(File.pathSeparator)
            .findAll { new File(it).exists() }
            .collect { "'${it.replace('\\', '\\\\').replace("'", "\\'")}'" }.join(',')
        new File(project, 'build.gradle').text = "buildscript { dependencies { classpath files(${classpath}) } }\n" + '''
import dev.braintrust.gradle.CheckDependenciesTask
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
abstract class FixtureCheck extends CheckDependenciesTask {
    @Override
    protected Map requestAdvisories(String url, String etag) {
        project.file('requests.jsonl').append(JsonOutput.toJson([url: url, etag: etag]) + '\\n', 'UTF-8')
        new JsonSlurper().parse(project.file('response.json')) as Map
    }
}
tasks.register('checkDependencies', FixtureCheck) {
    shippedDependencies.from(files('manifest.txt'))
    scannerSourceFiles.setFrom(files('scanner-input.txt'))
}
'''
        new File(project, 'scanner-input.txt').text = 'fixture implementation and policy'
        def manifest = new File(project, 'manifest.txt')
        manifest.text = COORDINATES.join('\n') + '\n'
        def response = new File(project, 'response.json')
        def runner = {
            GradleRunner.create().withProjectDir(project).withTestKitDir(new File(directory, 'testkit'))
                .withArguments('checkDependencies', '--build-cache', '--no-configuration-cache', '--stacktrace')
        }
        def taskCache = new File(project, 'build/dependency-advisories/cache.json')
        def marker = new File(project, 'build/dependency-advisories/complete')
        response.text = JsonOutput.toJson(ok([advisory()]))
        def cold = runner().buildAndFail()
        assertTrue(cold.output.contains('example:library'), cold.output)
        assertTrue(cold.output.contains('GHSA-aaaa-bbbb-cccc'), cold.output)
        assertTrue(marker.exists())
        assertTrue(taskCache.exists())
        def snapshot = taskCache.bytes
        response.text = JsonOutput.toJson(unchanged())
        def warm = runner().buildAndFail()
        assertTrue(warm.output.contains('GHSA-aaaa-bbbb-cccc'), warm.output)
        assertTrue(marker.exists())
        assertArrayEquals(snapshot, taskCache.bytes)
        response.text = JsonOutput.toJson(ok([advisory('GHSA-aaaa-bbbb-cccc', '2026-01-01T00:00:00Z')]))
        assertEquals(TaskOutcome.SUCCESS, runner().build().task(':checkDependencies').outcome)
        response.text = JsonOutput.toJson(unchanged())
        assertEquals(TaskOutcome.SUCCESS, runner().build().task(':checkDependencies').outcome)
        def calls = new File(project, 'requests.jsonl').readLines().collect { new JsonSlurper().parseText(it) }
        assertEquals(4, calls.size())
        assertEquals([null, '"first"', '"first"', '"first"'], calls.collect { it.etag })
        manifest.text = ''
        runner().buildAndFail()
        assertFalse(marker.exists())
    }
}
