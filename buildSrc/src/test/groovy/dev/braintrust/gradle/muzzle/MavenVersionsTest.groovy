package dev.braintrust.gradle.muzzle

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class MavenVersionsTest {
    @Test
    void sampleKeepsSmallRanges() {
        assertEquals([], MavenVersions.sample([]))
        assertEquals(['1.0'], MavenVersions.sample(['1.0']))
        assertEquals(['1.0', '2.0'], MavenVersions.sample(['1.0', '2.0']))
        assertEquals(['1.0', '2.0', '3.0'], MavenVersions.sample(['1.0', '2.0', '3.0']))
    }

    @Test
    void sampleTakesMinMiddleMax() {
        assertEquals(['1.0', '3.0', '4.0'], MavenVersions.sample(['1.0', '2.0', '3.0', '4.0']))
        assertEquals(['1.0', '3.0', '5.0'], MavenVersions.sample(['1.0', '2.0', '3.0', '4.0', '5.0']))
    }
}
