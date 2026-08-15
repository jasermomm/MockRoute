package com.jasermomm.mockroute.core

import org.junit.Assert.*
import org.junit.Test

class SpeedProfilesTest {
    @Test fun linearInterpolationAndIntegration() {
        val profile = SpeedProfile(points = listOf(SpeedPoint(0.0, 0.0), SpeedPoint(1.0, 2.0)))
        assertEquals(1.0, SpeedProfiles.valueAt(profile, 0.5), 1e-9)
        assertEquals(1.0, SpeedProfiles.integral(profile), 1e-9)
        assertEquals(0.25, SpeedProfiles.integral(profile, 0.5), 1e-9)
    }

    @Test fun durationLockedProgressIsNormalizedExactly() {
        SpeedPreset.entries.forEach { preset ->
            val profile = SpeedProfiles.preset(preset)
            assertEquals(0.0, SpeedProfiles.durationLockedProgress(profile, 0.0), 0.0)
            assertEquals(1.0, SpeedProfiles.durationLockedProgress(profile, 1.0), 0.0)
            var previous = 0.0
            repeat(1_001) { index ->
                val value = SpeedProfiles.durationLockedProgress(profile, index / 1_000.0)
                assertTrue(value + 1e-12 >= previous)
                previous = value
            }
        }
    }

    @Test fun normalizationSortsClampsAndAddsEndpoints() {
        val normalized = SpeedProfiles.normalize(
            SpeedProfile(points = listOf(SpeedPoint(0.8, -2.0), SpeedPoint(0.2, 3.0))),
        )
        assertEquals(0.0, normalized.points.first().timeFraction, 0.0)
        assertEquals(1.0, normalized.points.last().timeFraction, 0.0)
        assertTrue(normalized.points.all { it.value >= 0.0 })
    }

    @Test fun zeroSpeedRegionCreatesAPlateau() {
        val profile = SpeedProfile(points = listOf(
            SpeedPoint(0.0, 1.0), SpeedPoint(0.25, 0.0), SpeedPoint(0.75, 0.0), SpeedPoint(1.0, 1.0),
        ))
        val a = SpeedProfiles.durationLockedProgress(profile, 0.25)
        val b = SpeedProfiles.durationLockedProgress(profile, 0.75)
        assertEquals(a, b, 1e-9)
    }

    @Test fun allZeroProfileFallsBackToTimeProgress() {
        val profile = SpeedProfile(points = listOf(SpeedPoint(0.0, 0.0), SpeedPoint(1.0, 0.0)))
        assertEquals(0.4, SpeedProfiles.durationLockedProgress(profile, 0.4), 1e-9)
        assertEquals(0.0, SpeedProfiles.durationLockedSpeedMps(profile, 0.4, 100.0, 10_000L), 0.0)
    }

    @Test fun speedLockedEtaUsesAverageGraphSpeed() {
        val profile = SpeedProfile(mode = ProfileMode.SPEED_LOCKED, points = listOf(SpeedPoint(0.0, 10.0), SpeedPoint(1.0, 10.0)))
        assertEquals(10_000L, SpeedProfiles.speedLockedEtaMs(profile, 100.0))
        val zero = profile.copy(points = listOf(SpeedPoint(0.0, 0.0), SpeedPoint(1.0, 0.0)))
        assertNull(SpeedProfiles.speedLockedEtaMs(zero, 100.0))
    }

    @Test fun progressInverseRoundTrips() {
        val profile = SpeedProfiles.preset(SpeedPreset.CITY)
        repeat(101) { index ->
            val expected = index / 100.0
            val time = SpeedProfiles.timeFractionForProgress(profile, expected)
            assertEquals(expected, SpeedProfiles.durationLockedProgress(profile, time), 1e-8)
        }
    }
}
