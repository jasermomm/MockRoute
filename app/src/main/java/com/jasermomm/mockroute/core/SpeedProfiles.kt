package com.jasermomm.mockroute.core

import kotlin.math.abs

object SpeedProfiles {
    fun preset(preset: SpeedPreset, mode: ProfileMode = ProfileMode.DURATION_LOCKED): SpeedProfile {
        val points = when (preset) {
            SpeedPreset.CONSTANT -> listOf(0.0 to 1.0, 1.0 to 1.0)
            SpeedPreset.WALKING -> listOf(0.0 to 0.45, 0.08 to 0.95, 0.75 to 1.0, 1.0 to 0.35)
            SpeedPreset.JOGGING -> listOf(0.0 to 0.3, 0.12 to 1.05, 0.85 to 1.0, 1.0 to 0.25)
            SpeedPreset.CYCLING -> listOf(0.0 to 0.15, 0.15 to 1.1, 0.7 to 0.9, 1.0 to 0.1)
            SpeedPreset.CITY -> listOf(0.0 to 0.1, 0.1 to 1.0, 0.35 to 0.55, 0.55 to 1.15, 0.8 to 0.65, 1.0 to 0.1)
            SpeedPreset.HIGHWAY -> listOf(0.0 to 0.25, 0.15 to 1.0, 0.85 to 1.0, 1.0 to 0.2)
            SpeedPreset.SMOOTH -> listOf(0.0 to 0.0, 0.2 to 0.85, 0.5 to 1.15, 0.8 to 0.85, 1.0 to 0.0)
            SpeedPreset.CUSTOM -> listOf(0.0 to 1.0, 0.25 to 1.0, 0.5 to 1.0, 0.75 to 1.0, 1.0 to 1.0)
        }.map { SpeedPoint(it.first, it.second) }
        return SpeedProfile(preset, mode, points)
    }

    fun normalize(profile: SpeedProfile): SpeedProfile {
        val sorted = profile.points
            .filter { it.timeFraction.isFinite() && it.value.isFinite() }
            .map { SpeedPoint(it.timeFraction.coerceIn(0.0, 1.0), it.value.coerceAtLeast(0.0)) }
            .sortedBy { it.timeFraction }
            .distinctBy { it.timeFraction }
            .toMutableList()
        if (sorted.isEmpty()) sorted += SpeedPoint(0.0, 1.0)
        if (sorted.first().timeFraction > 0.0) sorted.add(0, SpeedPoint(0.0, sorted.first().value))
        if (sorted.last().timeFraction < 1.0) sorted += SpeedPoint(1.0, sorted.last().value)
        return profile.copy(points = sorted)
    }

    fun valueAt(profile: SpeedProfile, timeFraction: Double): Double {
        val p = normalize(profile).points
        val x = timeFraction.coerceIn(0.0, 1.0)
        if (x <= p.first().timeFraction) return p.first().value
        if (x >= p.last().timeFraction) return p.last().value
        val right = p.indexOfFirst { it.timeFraction >= x }.coerceAtLeast(1)
        val a = p[right - 1]
        val b = p[right]
        val f = if (abs(b.timeFraction - a.timeFraction) < 1e-12) 0.0
        else (x - a.timeFraction) / (b.timeFraction - a.timeFraction)
        return a.value + (b.value - a.value) * f
    }

    fun integral(profile: SpeedProfile, until: Double = 1.0): Double {
        val p = normalize(profile).points
        val end = until.coerceIn(0.0, 1.0)
        var area = 0.0
        for (i in 0 until p.lastIndex) {
            val a = p[i]
            val b = p[i + 1]
            if (end <= a.timeFraction) break
            val segmentEnd = minOf(end, b.timeFraction)
            val width = segmentEnd - a.timeFraction
            if (width > 0) {
                val endValue = valueAt(profile, segmentEnd)
                area += width * (a.value + endValue) / 2.0
            }
            if (end <= b.timeFraction) break
        }
        return area
    }

    fun durationLockedProgress(profile: SpeedProfile, timeFraction: Double): Double {
        val x = timeFraction.coerceIn(0.0, 1.0)
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        val total = integral(profile)
        if (total <= 1e-12) return x
        return (integral(profile, x) / total).coerceIn(0.0, 1.0)
    }

    fun timeFractionForProgress(profile: SpeedProfile, progress: Double): Double {
        val target = progress.coerceIn(0.0, 1.0)
        if (target <= 0.0) return 0.0
        if (target >= 1.0) return 1.0
        var low = 0.0
        var high = 1.0
        repeat(60) {
            val mid = (low + high) / 2.0
            if (durationLockedProgress(profile, mid) < target) low = mid else high = mid
        }
        return (low + high) / 2.0
    }

    fun durationLockedSpeedMps(
        profile: SpeedProfile,
        timeFraction: Double,
        distanceMeters: Double,
        durationMs: Long,
    ): Double {
        if (durationMs <= 0 || distanceMeters <= 0) return 0.0
        val mean = integral(profile)
        if (mean <= 1e-12) return 0.0
        val average = distanceMeters / (durationMs / 1_000.0)
        return average * valueAt(profile, timeFraction) / mean
    }

    fun speedLockedEtaMs(profile: SpeedProfile, distanceMeters: Double): Long? {
        val averageMps = integral(profile)
        if (averageMps <= 1e-9 || distanceMeters < 0) return null
        return (distanceMeters / averageMps * 1_000.0).toLong()
    }
}
