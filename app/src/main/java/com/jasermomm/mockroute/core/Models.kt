package com.jasermomm.mockroute.core

import java.util.Locale
import kotlin.math.roundToLong

enum class SimulationMode { STATIC, TRAVEL, DRIVE }

enum class CompletionBehavior { STOP, HOLD, RESTART }

enum class ProfileMode { DURATION_LOCKED, SPEED_LOCKED }

enum class SpeedPreset {
    CONSTANT, WALKING, JOGGING, CYCLING, CITY, HIGHWAY, SMOOTH, CUSTOM
}

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
) {
    val isValid: Boolean
        get() = latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            (altitude == null || altitude.isFinite())

    fun requireValid(): GeoPoint = apply { require(isValid) { "Invalid coordinate" } }

    fun display(decimals: Int = 6): String =
        "%.${decimals}f, %.${decimals}f".format(Locale.US, latitude, longitude)
}

data class ControlPoint(
    val id: String,
    val point: GeoPoint,
    val label: String = "",
)

data class SpeedPoint(val timeFraction: Double, val value: Double)

data class SpeedProfile(
    val preset: SpeedPreset = SpeedPreset.CONSTANT,
    val mode: ProfileMode = ProfileMode.DURATION_LOCKED,
    val points: List<SpeedPoint> = listOf(SpeedPoint(0.0, 1.0), SpeedPoint(1.0, 1.0)),
)

data class SimulationConfig(
    val mode: SimulationMode,
    val controlPoints: List<ControlPoint>,
    val geometry: List<GeoPoint>,
    val durationMs: Long = 60_000L,
    val updateIntervalMs: Long = 1_000L,
    val accuracyMeters: Float = 5f,
    val includeAltitude: Boolean = false,
    val realismPercent: Int = 0,
    val completion: CompletionBehavior = CompletionBehavior.STOP,
    val startDelayMs: Long = 0L,
    val speedProfile: SpeedProfile = SpeedProfile(),
    val name: String = "",
    val driveRouteValidated: Boolean = false,
) {
    val route: RouteGeometry by lazy { RouteGeometry(geometry) }
}

data class SimulationSnapshot(
    val active: Boolean = false,
    val paused: Boolean = false,
    val countdownMs: Long = 0L,
    val mode: SimulationMode? = null,
    val point: GeoPoint? = null,
    val destination: GeoPoint? = null,
    val progress: Double = 0.0,
    val elapsedMs: Long = 0L,
    val remainingMs: Long = 0L,
    val traveledMeters: Double = 0.0,
    val remainingMeters: Double = 0.0,
    val speedMps: Double = 0.0,
    val averageSpeedMps: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val bearingDegrees: Float = 0f,
    val error: String? = null,
)

data class SavedPlace(
    val id: String,
    val name: String,
    val point: GeoPoint,
    val note: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

data class SavedRoute(
    val id: String,
    val name: String,
    val config: SimulationConfig,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class SearchResult(
    val displayName: String,
    val point: GeoPoint,
    val type: String = "",
)

data class RoadRoute(
    val geometry: List<GeoPoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val snappedPoints: List<GeoPoint>,
)

sealed interface RouteFailure {
    data object NoRoute : RouteFailure
    data object CannotSnap : RouteFailure
    data object Offline : RouteFailure
    data object ServerUnavailable : RouteFailure
    data object MalformedResponse : RouteFailure
    data class Other(val message: String) : RouteFailure
}

sealed interface RouteResult {
    data class Success(val route: RoadRoute) : RouteResult
    data class Failure(val reason: RouteFailure) : RouteResult
}

fun Long.formatDuration(): String {
    val totalSeconds = (coerceAtLeast(0L) / 1_000.0).roundToLong()
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
