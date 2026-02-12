package dev.braintrust.gradle.muzzle

import groovy.xml.XmlSlurper

/**
 * Fetches available versions from Maven Central and filters them by version range.
 * Uses {@code maven-metadata.xml} from the Maven Central repository — no external
 * dependencies needed.
 */
class MavenVersions {

    private static final String MAVEN_CENTRAL = 'https://repo1.maven.org/maven2'

    /**
     * Fetches all versions of the given artifact from Maven Central, filters by the given
     * version range, and returns them sorted oldest-first.
     *
     * @param group Maven groupId
     * @param module Maven artifactId
     * @param versionRange Aether-style version range (e.g. "[2.0,)", "[1.0,2.0)")
     * @param skipVersions versions to exclude
     * @return sorted list of matching version strings
     */
    static List<String> resolve(String group, String module, String versionRange, Set<String> skipVersions) {
        def allVersions = fetchVersions(group, module)
        def range = parseRange(versionRange)

        return allVersions
                .findAll { v -> !isPreRelease(v) }
                .findAll { v -> !skipVersions.contains(v) }
                .findAll { v -> range.contains(v) }
                .sort { a, b -> compareVersions(a, b) }
    }

    /**
     * Fetches all available versions from Maven Central's maven-metadata.xml.
     */
    static List<String> fetchVersions(String group, String module) {
        def groupPath = group.replace('.', '/')
        def url = "${MAVEN_CENTRAL}/${groupPath}/${module}/maven-metadata.xml"

        try {
            def xml = new XmlSlurper().parse(url)
            return xml.versioning.versions.version*.text()
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to fetch versions for ${group}:${module} from Maven Central: ${e.message}", e)
        }
    }

    /**
     * Returns true for pre-release versions (alpha, beta, RC, SNAPSHOT, milestone, etc.)
     */
    static boolean isPreRelease(String version) {
        def lower = version.toLowerCase()
        return lower.contains('-alpha') ||
                lower.contains('-beta') ||
                lower.contains('-rc') ||
                lower.contains('-snapshot') ||
                lower.contains('-milestone') ||
                lower.contains('-m') && lower =~ /.*-m\d+.*/ ||
                lower.contains('-dev') ||
                lower.contains('.alpha') ||
                lower.contains('.beta') ||
                lower.contains('.rc') ||
                lower.contains('.snapshot')
    }

    // --- Version range parsing ---

    /**
     * Parses a Maven/Aether version range string into a VersionRange.
     *
     * Supported formats:
     * <ul>
     *   <li>{@code [2.0,)} — >= 2.0</li>
     *   <li>{@code [2.0,3.0)} — >= 2.0 AND < 3.0</li>
     *   <li>{@code [2.0,3.0]} — >= 2.0 AND <= 3.0</li>
     *   <li>{@code (2.0,3.0)} — > 2.0 AND < 3.0</li>
     *   <li>{@code [,]} — all versions</li>
     * </ul>
     */
    static VersionRange parseRange(String range) {
        range = range.trim()
        if (range.length() < 3) {
            throw new IllegalArgumentException("Invalid version range: ${range}")
        }

        boolean lowerInclusive = range.startsWith('[')
        boolean upperInclusive = range.endsWith(']')

        if (!range.startsWith('[') && !range.startsWith('(')) {
            throw new IllegalArgumentException("Version range must start with '[' or '(': ${range}")
        }
        if (!range.endsWith(']') && !range.endsWith(')')) {
            throw new IllegalArgumentException("Version range must end with ']' or ')': ${range}")
        }

        def inner = range.substring(1, range.length() - 1)
        def parts = inner.split(',', 2)
        if (parts.length != 2) {
            throw new IllegalArgumentException("Version range must contain a comma: ${range}")
        }

        def lower = parts[0].trim()
        def upper = parts[1].trim()

        return new VersionRange(
                lower.isEmpty() ? null : lower,
                lowerInclusive,
                upper.isEmpty() ? null : upper,
                upperInclusive
        )
    }

    /**
     * A version range with optional lower and upper bounds.
     */
    static class VersionRange {
        final String lower
        final boolean lowerInclusive
        final String upper
        final boolean upperInclusive

        VersionRange(String lower, boolean lowerInclusive, String upper, boolean upperInclusive) {
            this.lower = lower
            this.lowerInclusive = lowerInclusive
            this.upper = upper
            this.upperInclusive = upperInclusive
        }

        boolean contains(String version) {
            if (lower != null) {
                int cmp = compareVersions(version, lower)
                if (lowerInclusive ? cmp < 0 : cmp <= 0) {
                    return false
                }
            }
            if (upper != null) {
                int cmp = compareVersions(version, upper)
                if (upperInclusive ? cmp > 0 : cmp >= 0) {
                    return false
                }
            }
            return true
        }

        @Override
        String toString() {
            "${lowerInclusive ? '[' : '('}${lower ?: ''},${upper ?: ''}${upperInclusive ? ']' : ')'}"
        }
    }

    /**
     * Compares two version strings using numeric segment comparison.
     * Non-numeric segments are compared lexicographically.
     *
     * @return negative if a < b, 0 if equal, positive if a > b
     */
    static int compareVersions(String a, String b) {
        def aParts = a.split('[.\\-]')
        def bParts = b.split('[.\\-]')
        int len = Math.max(aParts.length, bParts.length)

        for (int i = 0; i < len; i++) {
            def ap = i < aParts.length ? aParts[i] : '0'
            def bp = i < bParts.length ? bParts[i] : '0'

            // Try numeric comparison first
            try {
                int aNum = Integer.parseInt(ap)
                int bNum = Integer.parseInt(bp)
                if (aNum != bNum) return aNum - bNum
            } catch (NumberFormatException ignored) {
                // Fall back to lexicographic
                int cmp = ap.compareTo(bp)
                if (cmp != 0) return cmp
            }
        }
        return 0
    }
}
