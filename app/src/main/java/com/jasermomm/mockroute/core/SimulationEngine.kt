package com.jasermomm.mockroute.core

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

fun interface MonotonicClock { fun nowMs(): Long }

data class EngineFrame(
    val point: GeoPoint,
    val progress: Double,
    val elapsedMs: Long,
    val remainingMs: Long,
    val speedMps: Double,
    val bearingDegrees: Float,
    val completed: Boolean,
)

class SimulationEngine(
    val config: SimulationConfig,
    private val clock: MonotonicClock,
) {
    private var anchorMs: Long = clock.nowMs()
    private var baseElapsedMs: Long = 0L
    var isPaused: Boolean = false
        private set

    fun pause() {
        if (!isPaused) {
            baseElapsedMs = rawElapsed(clock.nowMs())
            isPaused = true
        }
    }

    fun resume() {
        if (isPaused) {
            anchorMs = clock.nowMs()
            isPaused = false
        }
    }

    fun seek(progress: Double) {
        val timeFraction = SpeedProfiles.timeFractionForProgress(config.speedProfile, progress)
        baseElapsedMs = (timeFraction * config.durationMs).toLong().coerceIn(0L, config.durationMs)
        anchorMs = clock.nowMs()
    }

    fun frame(): EngineFrame {
        val duration = config.durationMs.coerceAtLeast(1L)
        val totalElapsed = rawElapsed(clock.nowMs()).coerceAtLeast(0L)
        val completion = config.completion
        val cycleElapsed = when {
            config.mode == SimulationMode.STATIC -> 0L
            completion == CompletionBehavior.RESTART -> totalElapsed % duration
            else -> totalElapsed.coerceAtMost(duration)
        }
        val timeFraction = if (config.mode == SimulationMode.STATIC) 0.0
        else cycleElapsed.toDouble() / duration
        val progress = when {
            config.mode == SimulationMode.STATIC -> 0.0
            totalElapsed >= duration && completion != CompletionBehavior.RESTART -> 1.0
            else -> SpeedProfiles.durationLockedProgress(config.speedProfile, timeFraction)
        }
        val intended = config.route.pointAt(progress)
        val point = SmoothRealism.apply(intended, progress, config.realismPercent)
        val completed = config.mode != SimulationMode.STATIC && totalElapsed >= duration &&
            completion == CompletionBehavior.STOP
        val speed = if (isPaused || completed || (progress >= 1.0 && completion == CompletionBehavior.HOLD)) 0.0
        else SpeedProfiles.durationLockedSpeedMps(
            config.speedProfile, timeFraction, config.route.totalMeters, duration,
        )
        return EngineFrame(
            point = point,
            progress = progress,
            elapsedMs = if (config.mode == SimulationMode.STATIC) totalElapsed else cycleElapsed,
            remainingMs = if (config.mode == SimulationMode.STATIC) 0L else max(0L, duration - cycleElapsed),
            speedMps = speed,
            bearingDegrees = config.route.bearingAt(progress).toFloat(),
            completed = completed,
        )
    }

    private fun rawElapsed(now: Long): Long = baseElapsedMs + if (isPaused) 0L else now - anchorMs
}

object SmoothRealism {
    fun apply(point: GeoPoint, progress: Double, realismPercent: Int, seed: Long = 0x4d6f636b): GeoPoint {
        val amount = realismPercent.coerceIn(0, 100) / 100.0
        if (amount <= 0.0 || progress <= 0.0 || progress >= 1.0) return point
        val scaleMeters = 10.0 * amount
        val x = progress * 24.0
        val east = smoothNoise(x, seed) * scaleMeters
        val north = smoothNoise(x, seed xor -7046029254386353131L) * scaleMeters
        return GeoMath.offsetMeters(point, east, north)
    }

    private fun smoothNoise(x: Double, seed: Long): Double {
        val i = floor(x).toLong()
        val f = x - floor(x)
        val smooth = f * f * (3.0 - 2.0 * f)
        val a = lattice(i, seed)
        val b = lattice(i + 1, seed)
        return a + (b - a) * smooth
    }

    private fun lattice(index: Long, seed: Long): Double {
        val mixed = sin((index * 12.9898 + seed * 0.000001) * 78.233) * 43_758.5453
        return ((mixed - floor(mixed)) * 2.0 - 1.0).coerceIn(-1.0, 1.0)
    }
}

object ConfigValidator {
    fun error(config: SimulationConfig): String? {
        if (config.controlPoints.isEmpty()) return "Choose a location"
        if (config.controlPoints.any { !it.point.isValid }) return "Invalid coordinates"
        if (config.geometry.isEmpty() || config.geometry.any { !it.isValid }) return "Invalid route"
        if (config.mode != SimulationMode.STATIC && config.route.totalMeters < 0.5) return "Start and destination are too close"
        if (config.mode != SimulationMode.STATIC && config.durationMs !in 1_000L..604_800_000L) return "Choose a valid duration"
        if (config.updateIntervalMs !in 200L..60_000L) return "Choose an update rate from 0.02 to 5 Hz"
        if (config.accuracyMeters !in 0.5f..10_000f) return "Choose a valid accuracy"
        if (config.realismPercent !in 0..100) return "Choose realism from 0 to 100%"
        if (config.mode == SimulationMode.DRIVE && !config.driveRouteValidated) return "Calculate a driving route first"
        if (config.mode == SimulationMode.DRIVE && config.geometry.size < 2) return "No driving route found"
        return null
    }
}
