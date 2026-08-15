package com.jasermomm.mockroute.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.jasermomm.mockroute.core.EngineFrame
import com.jasermomm.mockroute.core.SimulationConfig

class MockLocationController(context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var started = false

    fun probeAuthorization(): Result<Unit> = runCatching {
        removeQuietly(PROBE_PROVIDER)
        addProvider(PROBE_PROVIDER)
        manager.setTestProviderEnabled(PROBE_PROVIDER, true)
        manager.removeTestProvider(PROBE_PROVIDER)
    }.onFailure { removeQuietly(PROBE_PROVIDER) }

    @Synchronized
    fun start(): Result<Unit> = runCatching {
        if (started) return@runCatching
        removeQuietly(PROVIDER)
        addProvider(PROVIDER)
        manager.setTestProviderEnabled(PROVIDER, true)
        started = true
    }

    @Synchronized
    @SuppressLint("MissingPermission")
    fun inject(frame: EngineFrame, config: SimulationConfig): Result<Unit> = runCatching {
        check(started) { "Mock provider is not active" }
        val location = Location(PROVIDER).apply {
            latitude = frame.point.latitude
            longitude = frame.point.longitude
            accuracy = config.accuracyMeters
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            speed = frame.speedMps.coerceAtLeast(0.0).toFloat()
            bearing = ((frame.bearingDegrees % 360f) + 360f) % 360f
            if (config.includeAltitude && frame.point.altitude != null) altitude = frame.point.altitude
            speedAccuracyMetersPerSecond = (0.4f + config.realismPercent / 100f).coerceAtMost(3f)
            bearingAccuracyDegrees = (2f + config.realismPercent / 20f).coerceAtMost(12f)
            if (config.includeAltitude && frame.point.altitude != null) verticalAccuracyMeters = 3f
        }
        manager.setTestProviderLocation(PROVIDER, location)
    }

    @Synchronized
    fun stop() {
        started = false
        removeQuietly(PROVIDER)
    }

    private fun addProvider(name: String) {
        if (Build.VERSION.SDK_INT >= 31) Api31.add(manager, name)
        else addLegacy(manager, name)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("InlinedApi")
    private fun addLegacy(manager: LocationManager, name: String) {
        manager.addTestProvider(
            name,
            false,
            name == PROVIDER,
            false,
            false,
            true,
            true,
            true,
            ProviderProperties.POWER_USAGE_LOW,
            ProviderProperties.ACCURACY_FINE,
        )
    }

    private fun removeQuietly(name: String) {
        runCatching { manager.removeTestProvider(name) }
    }

    @RequiresApi(31)
    private object Api31 {
        fun add(manager: LocationManager, name: String) {
            val properties = ProviderProperties.Builder()
                .setHasAltitudeSupport(true)
                .setHasSpeedSupport(true)
                .setHasBearingSupport(true)
                .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                .setAccuracy(ProviderProperties.ACCURACY_FINE)
                .build()
            manager.addTestProvider(name, properties)
        }
    }

    companion object {
        const val PROVIDER = LocationManager.GPS_PROVIDER
        private const val PROBE_PROVIDER = "mockroute_authorization_probe"
    }
}
