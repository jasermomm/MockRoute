package com.jasermomm.mockroute.core

import org.junit.Assert.*
import org.junit.Test

class SimulationEngineTest {
    private class FakeClock(var now: Long = 0L) : MonotonicClock { override fun nowMs() = now }

    private fun config(
        completion: CompletionBehavior = CompletionBehavior.STOP,
        realism: Int = 0,
        geometry: List<GeoPoint> = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.1)),
        mode: SimulationMode = SimulationMode.TRAVEL,
    ) = SimulationConfig(
        mode = mode,
        controlPoints = listOf(ControlPoint("a", geometry.first()), ControlPoint("b", geometry.last())),
        geometry = geometry,
        durationMs = 10_000L,
        realismPercent = realism,
        completion = completion,
        driveRouteValidated = mode != SimulationMode.DRIVE || geometry.size >= 2,
    )

    @Test fun elapsedTimeNotCallbackCountControlsProgress() {
        val clock = FakeClock()
        val engine = SimulationEngine(config(), clock)
        repeat(100) { engine.frame() }
        assertEquals(0.0, engine.frame().progress, 0.0)
        clock.now = 5_000
        assertEquals(0.5, engine.frame().progress, 1e-9)
    }

    @Test fun pauseResumeHoldsExactPositionAndTime() {
        val clock = FakeClock()
        val engine = SimulationEngine(config(), clock)
        clock.now = 3_000
        val before = engine.frame()
        engine.pause()
        clock.now = 9_000
        val paused = engine.frame()
        assertEquals(before.progress, paused.progress, 0.0)
        assertEquals(before.point, paused.point)
        assertEquals(0.0, paused.speedMps, 0.0)
        engine.resume()
        clock.now = 11_000
        assertEquals(0.5, engine.frame().progress, 1e-9)
    }

    @Test fun seekUpdatesAllDerivedState() {
        val clock = FakeClock()
        val engine = SimulationEngine(config(), clock)
        engine.seek(0.7)
        val frame = engine.frame()
        assertEquals(0.7, frame.progress, 1e-8)
        assertEquals(7_000L, frame.elapsedMs)
        assertEquals(3_000L, frame.remainingMs)
    }

    @Test fun stopAndHoldArriveExactlyAtDestination() {
        listOf(CompletionBehavior.STOP, CompletionBehavior.HOLD).forEach { completion ->
            val clock = FakeClock()
            val engine = SimulationEngine(config(completion), clock)
            clock.now = 15_000
            val frame = engine.frame()
            assertEquals(1.0, frame.progress, 0.0)
            assertEquals(GeoPoint(0.0, 0.1), frame.point)
            assertEquals(completion == CompletionBehavior.STOP, frame.completed)
        }
    }

    @Test fun restartRepeatsForwardWithoutReversing() {
        val clock = FakeClock()
        val engine = SimulationEngine(config(CompletionBehavior.RESTART), clock)
        clock.now = 12_500
        val frame = engine.frame()
        assertEquals(0.25, frame.progress, 1e-9)
        assertFalse(frame.completed)
        assertTrue(frame.point.longitude in 0.02..0.03)
    }

    @Test fun driveEngineTraversesRoutedGeometry() {
        val geometry = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.1, 0.0), GeoPoint(0.1, 0.1))
        val clock = FakeClock()
        val engine = SimulationEngine(config(geometry = geometry, mode = SimulationMode.DRIVE), clock)
        clock.now = 5_000
        val point = engine.frame().point
        assertEquals(0.1, point.latitude, 0.002)
        assertEquals(0.0, point.longitude, 0.002)
    }

    @Test fun randomnessDisabledIsDeterministic() {
        val point = GeoPoint(30.0, 31.0)
        repeat(1_000) { index -> assertEquals(point, SmoothRealism.apply(point, index / 999.0, 0)) }
    }

    @Test fun smoothRandomnessIsBoundedDeterministicAndHasNoDrift() {
        val point = GeoPoint(30.0, 31.0)
        var maximum = 0.0
        repeat(20_001) { index ->
            val progress = index / 20_000.0
            val a = SmoothRealism.apply(point, progress, 100)
            val b = SmoothRealism.apply(point, progress, 100)
            assertEquals(a, b)
            maximum = maxOf(maximum, GeoMath.haversineMeters(point, a))
        }
        assertTrue(maximum <= 15.0)
        assertEquals(point, SmoothRealism.apply(point, 1.0, 100))
    }

    @Test fun configValidationRejectsBadAndUnroutedDriveConfigs() {
        assertNull(ConfigValidator.error(config()))
        assertNotNull(ConfigValidator.error(config().copy(durationMs = 0)))
        assertNotNull(ConfigValidator.error(config(mode = SimulationMode.DRIVE).copy(driveRouteValidated = false)))
        assertNotNull(ConfigValidator.error(config().copy(realismPercent = 101)))
        assertNotNull(ConfigValidator.error(config().copy(updateIntervalMs = 100)))
    }
}
