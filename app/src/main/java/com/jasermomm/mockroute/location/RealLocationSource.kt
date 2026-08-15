package com.jasermomm.mockroute.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.jasermomm.mockroute.core.GeoPoint
import com.jasermomm.mockroute.core.RealLocationCandidate
import com.jasermomm.mockroute.core.RealLocationSelection
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class RealLocationSource(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun current(): Result<GeoPoint> = runCatching {
        check(hasPermission()) { "Location permission is needed" }
        val providers = manager.getProviders(true)
            .filterNot { it == LocationManager.PASSIVE_PROVIDER }
            .sortedBy { when (it.lowercase()) { "fused" -> 0; "network" -> 1; "gps" -> 2; else -> 3 } }
            .take(4)
        require(providers.isNotEmpty()) { "Turn on device location" }

        val cached = providers.mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
        val fresh = supervisorScope {
            providers.map { provider -> async { withTimeoutOrNull(9_000L) { fresh(provider) } } }.awaitAll().filterNotNull()
        }
        val candidates = (fresh + cached).map(::candidate)
        RealLocationSelection.choose(candidates, System.currentTimeMillis())?.point
            ?: throw IllegalStateException("No recent real location found")
    }

    @SuppressLint("MissingPermission")
    private suspend fun fresh(provider: String): Location? = suspendCancellableCoroutine { continuation ->
        if (Build.VERSION.SDK_INT >= 30) {
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            runCatching {
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            }.onFailure { if (continuation.isActive) continuation.resume(null) }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    manager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(location)
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            @Suppress("DEPRECATION")
            runCatching { manager.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper()) }
                .onFailure { if (continuation.isActive) continuation.resume(null) }
        }
    }

    @Suppress("DEPRECATION")
    private fun candidate(location: Location): RealLocationCandidate = RealLocationCandidate(
        provider = location.provider.orEmpty(),
        point = GeoPoint(location.latitude, location.longitude, location.altitude.takeIf { location.hasAltitude() }),
        wallTimeMs = location.time,
        elapsedRealtimeNanos = location.elapsedRealtimeNanos,
        accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() } ?: Float.MAX_VALUE,
        isMock = if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider,
    )
}
