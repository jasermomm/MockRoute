package com.jasermomm.mockroute.core

import com.jasermomm.mockroute.data.Accent
import com.jasermomm.mockroute.data.JsonCodec
import com.jasermomm.mockroute.data.StoredData
import com.jasermomm.mockroute.data.ThemeMode
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class GpxAndLocationTest {
    @Test fun gpxRoundTripPreservesGeometryAndAltitude() {
        val points = listOf(GeoPoint(30.0, 31.0, 12.5), GeoPoint(30.1, 31.2, 14.0))
        val encoded = GpxCodec.encode("A & B", points)
        assertTrue(encoded.contains("A &amp; B"))
        val decoded = GpxCodec.decode(encoded).getOrThrow()
        assertEquals(points, decoded)
    }

    @Test fun malformedGpxReturnsFriendlyFailure() {
        assertTrue(GpxCodec.decode("<gpx><trk></gpx>").isFailure)
        assertTrue(GpxCodec.decode("<trkpt lat=\"999\" lon=\"0\"></trkpt>").isFailure)
    }

    @Test fun jsonBackupRoundTripPreservesSavedContent() {
        val start = GeoPoint(30.0444, 31.2357, 22.0)
        val end = GeoPoint(29.9792, 31.1342, 15.0)
        val config = SimulationConfig(
            mode = SimulationMode.TRAVEL,
            controlPoints = listOf(ControlPoint("a", start, "Start"), ControlPoint("b", end, "Destination")),
            geometry = listOf(start, end),
            durationMs = 123_000,
            realismPercent = 25,
            name = "Cairo test",
        )
        val original = StoredData(
            savedPlaces = listOf(SavedPlace("p1", "Pyramid", end, "QA target", 42L)),
            savedRoutes = listOf(SavedRoute("r1", "Cairo test", config, 43L)),
            recentPlaces = listOf(SavedPlace("p2", "Downtown", start, updatedAt = 44L)),
            recentRoutes = listOf(SavedRoute("r2", "Recent", config, 45L)),
        )

        val decoded = JsonCodec.stored(JSONObject(JsonCodec.stored(original).toString()))
        assertEquals(original, decoded)
    }

    @Test fun themeAndAccentPersistenceValuesRecoverSafely() {
        assertEquals(ThemeMode.DARK, JsonCodec.enumValueOr("DARK", ThemeMode.SYSTEM))
        assertEquals(Accent.TEAL, JsonCodec.enumValueOr("TEAL", Accent.BLUE))
        assertEquals(ThemeMode.SYSTEM, JsonCodec.enumValueOr("UNKNOWN", ThemeMode.SYSTEM))
        assertEquals(Accent.BLUE, JsonCodec.enumValueOr("", Accent.BLUE))
    }

    @Test fun realLocationRejectsStaleMockAndInvalidCandidates() {
        val now = 1_000_000L
        val candidates = listOf(
            RealLocationCandidate("fused", GeoPoint(30.0, 31.0), now, 0, 2f, true),
            RealLocationCandidate("network", GeoPoint(30.0, 31.0), now - 500_000, 0, 5f, false),
            RealLocationCandidate("gps", GeoPoint(30.1, 31.1), now - 1_000, 0, 3f, false),
        )
        assertEquals("gps", RealLocationSelection.choose(candidates, now)?.provider)
    }

    @Test fun providerPreferenceIsFusedThenNetworkThenGps() {
        val now = 10_000L
        val candidates = listOf(
            RealLocationCandidate("gps", GeoPoint(1.0, 1.0), now, 0, 1f, false),
            RealLocationCandidate("network", GeoPoint(2.0, 2.0), now, 0, 20f, false),
            RealLocationCandidate("fused", GeoPoint(3.0, 3.0), now, 0, 50f, false),
        )
        assertEquals("fused", RealLocationSelection.choose(candidates, now)?.provider)
    }
}
