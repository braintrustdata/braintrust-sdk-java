package dev.braintrust.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.OffsetDateTime

/** Live, page-by-page revalidation; cached responses are candidates, never offline answers. */
class DependencyAdvisories {
    static final String API_VERSION = '2022-11-28'
    static final int MAX_URL_LENGTH = 5800
    static final int MAX_PACKAGES = 1000
    static final Map POLICY = [format: 1, apiVersion: API_VERSION, type: 'reviewed', ecosystem: 'maven', perPage: 100]
    private static final String PREFIX = 'https://api.github.com/advisories?type=reviewed&ecosystem=maven&per_page=100&affects='

    static String identity(Collection<File> inputs) {
        if (inputs.isEmpty()) {
            throw new GradleException('Scanner implementation/policy inputs are missing.')
        }
        def digest = MessageDigest.getInstance('SHA-256')
        inputs.sort { it.name }.each { file ->
            if (!file.isFile()) {
                throw new GradleException("Scanner implementation/policy input is missing: ${file}")
            }
            digest.update(file.name.getBytes('UTF-8'))
            digest.update((byte) 0)
            digest.update(file.bytes)
            digest.update((byte) 0)
        }
        digest.digest().encodeHex().toString()
    }

    static List<Map> batches(Collection<String> coordinates) {
        def sorted = new TreeSet<String>(coordinates)
        if (sorted.isEmpty()) {
            throw new GradleException('The shipped dependency graph is empty; refusing to report a clean scan.')
        }
        def result = []
        def current = []
        def url = new StringBuilder(PREFIX)
        sorted.each { coordinate ->
            if (!(coordinate ==~ /[^\s,:@]+:[^\s,:@]+@[^\s,@]+/)) {
                throw new GradleException("Invalid shipped dependency coordinate: ${coordinate}")
            }
            String encoded = URLEncoder.encode(coordinate, 'UTF-8')
            if (PREFIX.length() + encoded.length() > MAX_URL_LENGTH) {
                throw new GradleException("Dependency coordinate exceeds the advisory URL limit: ${coordinate}")
            }
            int separatorLength = current.isEmpty() ? 0 : 3 // Percent-encoded comma.
            if (current.size() == MAX_PACKAGES || url.length() + separatorLength + encoded.length() > MAX_URL_LENGTH) {
                result.add([coordinates: current, url: url.toString()])
                current = []
                url.setLength(PREFIX.length())
            }
            if (!current.isEmpty()) {
                url.append('%2C')
            }
            current.add(coordinate)
            url.append(encoded)
        }
        if (!current.isEmpty()) {
            result.add([coordinates: current, url: url.toString()])
        }
        result
    }

    static void clearCompletion(File directory) {
        Files.deleteIfExists(new File(directory, 'complete').toPath())
    }

    /** Transport returns [exitCode, stdout, stderr] from gh api --include (including its HTTP status). */
    static Map scan(Collection<String> coordinates, String implementationIdentity, File directory,
                    Closure transport, Closure log) {
        clearCompletion(directory)
        int pages = 0
        int hits = 0
        int completedBatches = 0
        def chunks = batches(coordinates)
        def cacheFile = new File(directory, 'cache.json')
        def previous = loadCache(cacheFile, implementationIdentity)
        def nextPages = new TreeMap<String, Map>()
        def advisories = new TreeMap<String, Map>()
        log.call("Checking ${coordinates.toSet().size()} shipped dependency versions in ${chunks.size()} advisory batches; ${previous.size()} candidate cached pages.")
        try {
            chunks.eachWithIndex { batch, index ->
                String url = batch.url
                def seen = new HashSet<String>()
                int batchPages = 0
                int batchHits = 0
                while (url != null) {
                    validateUrl(url, batch.url)
                    if (!seen.add(url)) {
                        throw new GradleException('Pagination loop in GitHub advisory response.')
                    }
                    def candidate = previous[url]
                    if (!validCandidate(candidate, batch.coordinates, batch.url) ||
                            (candidate?.next == null && candidate?.body?.size() == POLICY.perPage)) {
                        // A full terminal page can gain a next Link without changing its body/ETag.
                        // Fetch it unconditionally so an omitted Link on 304 cannot hide a new page.
                        candidate = null
                    }
                    def response = parseResponse(transport.call(url, candidate?.etag))
                    Map page
                    if (response.status == 304) {
                        // gh reports a genuine 304 as exit 1. Other failures must not become hits.
                        if (!candidate || !candidate.etag || !(response.exitCode in [0, 1]) ||
                                (response.exitCode == 1 && response.stderr.trim() != 'gh: HTTP 304') ||
                                !response.body.trim().isEmpty()) {
                            throw new GradleException('Unusable HTTP 304: no matching validated cache response or gh transport failed.')
                        }
                        page = [coordinates: candidate.coordinates, etag: candidate.etag,
                                next: candidate.next, body: candidate.body]
                        if (response.headers.containsKey('link')) {
                            page.next = nextLink(response.headers.link)
                            if (page.next != null) {
                                validateUrl(page.next, batch.url)
                            }
                        }
                        hits++
                        batchHits++
                    } else if (response.status == 200 && response.exitCode == 0) {
                        def body = new JsonSlurper().parseText(response.body)
                        validateBody(body)
                        def next = nextLink(response.headers.link)
                        if (next != null) {
                            validateUrl(next, batch.url)
                        }
                        String etag = response.headers.etag
                        if (etag != null && !(etag ==~ /(?:W\/)?"[^"\r\n]*"/)) {
                            throw new GradleException('Malformed ETag in GitHub advisory response.')
                        }
                        page = [coordinates: batch.coordinates, etag: etag, next: next, body: body]
                    } else {
                        throw new GradleException("GitHub advisory request failed (HTTP ${response.status}, gh exit ${response.exitCode}); check gh authentication, permissions and API availability.")
                    }
                    nextPages[url] = page
                    page.body.each { advisory ->
                        def existing = advisories[advisory.ghsa_id]
                        // Concurrent API updates can expose conflicting duplicates across pages/batches.
                        // A later withdrawn copy must not erase an active match already observed.
                        if (existing == null || existing.withdrawn_at != null || advisory.withdrawn_at == null) {
                            advisories[advisory.ghsa_id] = advisory
                        }
                    }
                    pages++
                    batchPages++
                    url = page.next
                }
                completedBatches++
                log.call("Advisory batch ${index + 1}/${chunks.size()}: ${batch.coordinates.size()} versions, ${batchPages} pages, ${batchHits} revalidated cache hits.")
            }
            def names = coordinates.collect { it.substring(0, it.lastIndexOf('@')) }.toSet()
            def findings = advisories.values().findAll { it.withdrawn_at == null }.collect { advisory ->
                def relevant = advisory.vulnerabilities.findAll {
                    it.package.ecosystem == 'maven' && names.contains(it.package.name)
                }.collect { it.package.name }.toSorted().unique()
                if (relevant.isEmpty()) {
                    throw new GradleException("GitHub advisory ${advisory.ghsa_id} has no relevant Maven package in its response.")
                }
                [advisory: advisory, packages: relevant]
            }
            Files.createDirectories(directory.toPath())
            def temporary = Files.createTempFile(directory.toPath(), 'cache-', '.tmp')
            try {
                Files.writeString(temporary, JsonOutput.toJson(canonical([policy: POLICY, identity: implementationIdentity, pages: nextPages])) + '\n')
                try {
                    Files.move(temporary, cacheFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
            Files.writeString(new File(directory, 'complete').toPath(), 'complete\n')
            log.call("Advisory scan complete: ${completedBatches} batches, ${pages} pages, ${hits} revalidated cache hits, ${findings.size()} active GHSAs.")
            return [findings: findings, batches: chunks.size(), pages: pages, hits: hits]
        } catch (Exception e) {
            clearCompletion(directory)
            // Do not echo gh output: it may contain credentials or other sensitive transport details.
            String detail = e instanceof GradleException ? e.message :
                    "${e.class.simpleName} while fetching, validating or saving advisory responses; check API availability and cache directory permissions."
            throw new GradleException("Dependency scan incomplete after ${completedBatches}/${chunks.size()} batches, ${pages} pages, ${hits} cache hits: ${detail}")
        }
    }

    private static Object canonical(def value) {
        if (value instanceof Map) {
            def sorted = new TreeMap<String, Object>()
            value.each { key, item -> sorted[key] = canonical(item) }
            return sorted
        }
        if (value instanceof List) {
            return value.collect { canonical(it) }
        }
        value
    }

    private static Map loadCache(File file, String identity) {
        if (!file.isFile()) {
            return [:]
        }
        try {
            def value = new JsonSlurper().parse(file, 'UTF-8')
            if (value instanceof Map && value.policy == POLICY && value.identity == identity && value.pages instanceof Map) {
                return value.pages
            }
        } catch (Exception ignored) {
            // A corrupt cache is only a miss. A fresh, complete live scan is still required.
        }
        [:]
    }

    private static boolean validCandidate(def candidate, List coordinates, String batchUrl) {
        try {
            if (!(candidate instanceof Map) || candidate.coordinates != coordinates ||
                    !(candidate.etag instanceof String) || !(candidate.etag ==~ /(?:W\/)?"[^"\r\n]*"/) ||
                    !candidate.containsKey('next')) {
                return false
            }
            validateBody(candidate.body)
            if (candidate.next != null) {
                validateUrl(candidate.next, batchUrl)
            }
            return true
        } catch (Exception ignored) {
            return false
        }
    }

    private static Map parseResponse(def result) {
        if (!(result instanceof Map) || !(result.exitCode instanceof Number) ||
                !(result.stdout instanceof String) || !(result.stderr instanceof String)) {
            throw new GradleException('Invalid gh transport response.')
        }
        String text = result.stdout.replace('\r\n', '\n')
        int separator = text.indexOf('\n\n')
        if (separator < 0) {
            throw new GradleException("Missing HTTP headers from gh (exit ${result.exitCode}); check authentication and connectivity.")
        }
        def lines = text.substring(0, separator).split('\n')
        def status = lines[0] =~ /^HTTP\/\d+(?:\.\d+)?\s+(\d{3})(?:\s+.*)?$/
        if (!status.matches()) {
            throw new GradleException('Malformed HTTP status from gh.')
        }
        def headers = [:]
        lines.drop(1).each { line ->
            int colon = line.indexOf(':')
            if (colon <= 0) {
                throw new GradleException('Malformed HTTP header from gh.')
            }
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT)
            String value = line.substring(colon + 1).trim()
            if (headers.containsKey(name)) {
                if (name == 'etag') {
                    throw new GradleException('Duplicate ETag header from gh.')
                }
                if (name == 'link') {
                    value = headers[name] + ', ' + value
                }
            }
            headers[name] = value
        }
        [status: Integer.parseInt(status[0][1]), headers: headers,
         body: text.substring(separator + 2), exitCode: result.exitCode, stderr: result.stderr]
    }

    private static String nextLink(String link) {
        if (link == null) {
            return null
        }
        String next = null
        // Commas within angle brackets belong to URLs, not to Link separators.
        def links = link.split(/,(?=\s*<)/)
        links.each { part ->
            def match = part.trim() =~ /^<([^<>]+)>;\s*rel="([a-z ]+)"$/
            if (!match.matches()) {
                throw new GradleException('Malformed GitHub advisory pagination Link header.')
            }
            if (match[0][2].split(' ').contains('next')) {
                if (next != null) {
                    throw new GradleException('Multiple next pages in GitHub advisory response.')
                }
                next = match[0][1]
            }
        }
        next
    }

    private static void validateUrl(String url, String batchUrl) {
        def uri = new URI(url)
        if (uri.scheme != 'https' || uri.rawAuthority != 'api.github.com' ||
                uri.rawPath != '/advisories' || uri.rawFragment != null || uri.rawQuery == null) {
            throw new GradleException('Unsafe GitHub advisory pagination URL; expected https://api.github.com/advisories.')
        }
        def parameters = queryParameters(uri)
        def expected = queryParameters(new URI(batchUrl))
        if (expected.any { key, value -> parameters[key] != value } ||
                parameters.keySet().any { !expected.containsKey(it) && !(it in ['after', 'before']) } ||
                (parameters.containsKey('after') && parameters.containsKey('before')) ||
                ['after', 'before'].any { parameters.containsKey(it) && parameters[it].isBlank() }) {
            throw new GradleException('GitHub advisory pagination changed the dependency query or filters.')
        }
    }

    private static Map queryParameters(URI uri) {
        def parameters = [:]
        uri.rawQuery.split('&').each { part ->
            def pair = part.split('=', 2)
            if (pair.length != 2) {
                throw new GradleException('Malformed advisory pagination query.')
            }
            String name = URLDecoder.decode(pair[0], 'UTF-8')
            if (parameters.containsKey(name)) {
                throw new GradleException('Duplicate advisory pagination query parameter.')
            }
            parameters[name] = URLDecoder.decode(pair[1], 'UTF-8')
        }
        parameters
    }

    private static void validateBody(def body) {
        if (!(body instanceof List) || body.size() > POLICY.perPage) {
            throw new GradleException('GitHub advisory response must be an array of at most 100 entries.')
        }
        body.each { advisory ->
            if (!(advisory instanceof Map) || !(advisory.ghsa_id instanceof String) ||
                    !(advisory.ghsa_id ==~ /GHSA-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{4}/) ||
                    !(advisory.summary instanceof String) || advisory.summary.isBlank() ||
                    !(advisory.severity in ['unknown', 'low', 'medium', 'high', 'critical']) ||
                    advisory.html_url != "https://github.com/advisories/${advisory.ghsa_id}".toString() ||
                    !advisory.containsKey('withdrawn_at') ||
                    !(advisory.vulnerabilities instanceof List) || advisory.vulnerabilities.isEmpty()) {
                throw new GradleException('Malformed or incomplete GitHub advisory schema.')
            }
            if (advisory.withdrawn_at != null) {
                if (!(advisory.withdrawn_at instanceof String)) {
                    throw new GradleException('Malformed advisory withdrawal timestamp.')
                }
                OffsetDateTime.parse(advisory.withdrawn_at)
            }
            advisory.vulnerabilities.each { vulnerability ->
                if (!(vulnerability instanceof Map) || !(vulnerability.package instanceof Map) ||
                        !(vulnerability.package.ecosystem instanceof String) || vulnerability.package.ecosystem.isBlank() ||
                        !(vulnerability.package.name instanceof String) || vulnerability.package.name.isBlank()) {
                    throw new GradleException('Malformed or incomplete GitHub advisory vulnerability schema.')
                }
            }
        }
    }

    static Map request(String url, String etag) {
        def command = ['gh', 'api', '--hostname', 'github.com', '--method', 'GET', '--include',
                       '-H', 'Accept: application/vnd.github+json', '-H', "X-GitHub-Api-Version: ${API_VERSION}".toString()]
        if (etag != null) {
            command.addAll(['-H', "If-None-Match: ${etag}".toString()])
        }
        command.add(url)
        def stdout = new StringBuilder()
        def stderr = new StringBuilder()
        Process process
        try {
            process = new ProcessBuilder(command).start()
        } catch (IOException ignored) {
            throw new GradleException('Cannot start gh; install the GitHub CLI and authenticate it before scanning dependencies.')
        }
        try {
            process.waitForProcessOutput(stdout, stderr)
            [exitCode: process.exitValue(), stdout: stdout.toString(), stderr: stderr.toString()]
        } finally {
            process.destroy()
        }
    }
}
