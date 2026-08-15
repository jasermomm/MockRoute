package com.jasermomm.mockroute.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.jasermomm.mockroute.MainActivity
import com.jasermomm.mockroute.R
import com.jasermomm.mockroute.core.*
import com.jasermomm.mockroute.data.JsonCodec
import com.jasermomm.mockroute.location.MockLocationController
import kotlinx.coroutines.*
import kotlinx.coroutines.asCoroutineDispatcher
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.max

class SimulationService : Service() {
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MockRouteSimulation").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private lateinit var provider: MockLocationController
    private var worker: Job? = null
    private var engine: SimulationEngine? = null
    private var currentConfig: SimulationConfig? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopping = false
    private val preferences by lazy { getSharedPreferences("active_session", Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        provider = MockLocationController(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_RESTORE
        if (action == ACTION_START || action == ACTION_RESTORE) {
            startForegroundCompat(SimulationSnapshot(active = true))
        }
        scope.launch {
            when (action) {
                ACTION_START -> intent?.getStringExtra(EXTRA_CONFIG)?.let { startSession(it) }
                ACTION_PAUSE -> pauseSession()
                ACTION_RESUME -> resumeSession()
                ACTION_STOP -> stopSession(true)
                ACTION_SEEK -> engine?.seek(intent?.getDoubleExtra(EXTRA_PROGRESS, 0.0) ?: 0.0)
                ACTION_RESTORE -> restoreSession()
            }
        }
        return START_STICKY
    }

    private suspend fun startSession(json: String) {
        stopSession(false)
        val config = runCatching { JsonCodec.config(JSONObject(json)) }.getOrElse {
            failAndStop("Could not read the simulation")
            return
        }
        ConfigValidator.error(config)?.let {
            failAndStop(it)
            return
        }
        stopping = false
        currentConfig = config
        preferences.edit { putBoolean(KEY_ACTIVE, true); putString(KEY_CONFIG, json) }
        startForegroundCompat(SimulationSnapshot(active = true, mode = config.mode))
        val setup = provider.start()
        if (setup.isFailure) {
            failAndStop(setup.exceptionOrNull().toSetupMessage())
            return
        }
        acquireWakeLock()
        engine = SimulationEngine(config) { SystemClock.elapsedRealtime() }
        worker = scope.launch { runLoop(config) }
    }

    private suspend fun runLoop(config: SimulationConfig) {
        try {
            val delayStart = SystemClock.elapsedRealtime()
            while (currentCoroutineContext().isActive && !stopping) {
                val remaining = config.startDelayMs - (SystemClock.elapsedRealtime() - delayStart)
                if (remaining <= 0) break
                publish(SimulationSnapshot(active = true, countdownMs = remaining, mode = config.mode))
                delay(minOf(250L, remaining))
            }
            var maxSpeed = 0.0
            var lastNotification = 0L
            while (currentCoroutineContext().isActive && !stopping) {
                val frame = engine?.frame() ?: break
                if (stopping) break
                provider.inject(frame, config).getOrElse { throw it }
                maxSpeed = max(maxSpeed, frame.speedMps)
                val traveled = config.route.totalMeters * frame.progress
                val average = if (frame.elapsedMs > 0) traveled / (frame.elapsedMs / 1_000.0) else 0.0
                val snapshot = SimulationSnapshot(
                    active = true,
                    paused = engine?.isPaused == true,
                    mode = config.mode,
                    point = frame.point,
                    destination = config.geometry.lastOrNull(),
                    progress = frame.progress,
                    elapsedMs = frame.elapsedMs,
                    remainingMs = frame.remainingMs,
                    traveledMeters = traveled,
                    remainingMeters = (config.route.totalMeters - traveled).coerceAtLeast(0.0),
                    speedMps = frame.speedMps,
                    averageSpeedMps = average,
                    maxSpeedMps = maxSpeed,
                    bearingDegrees = frame.bearingDegrees,
                )
                publish(snapshot)
                val now = SystemClock.elapsedRealtime()
                if (now - lastNotification >= 1_000L) {
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(snapshot))
                    lastNotification = now
                }
                if (frame.completed) {
                    scope.launch { stopSession(true) }
                    return
                }
                delay(config.updateIntervalMs)
            }
        } catch (_: CancellationException) {
            // Normal stop path.
        } catch (error: Throwable) {
            failAndStop(error.toSetupMessage())
        }
    }

    private fun pauseSession() {
        engine?.pause()
        publish(SimulationBus.state.value.copy(paused = true, speedMps = 0.0))
        notificationManager.notify(NOTIFICATION_ID, buildNotification(SimulationBus.state.value))
    }

    private fun resumeSession() {
        engine?.resume()
        publish(SimulationBus.state.value.copy(paused = false))
        notificationManager.notify(NOTIFICATION_ID, buildNotification(SimulationBus.state.value))
    }

    private suspend fun stopSession(removeForeground: Boolean) {
        stopping = true
        val running = worker
        worker = null
        if (running != currentCoroutineContext()[Job]) running?.cancelAndJoin()
        engine = null
        currentConfig = null
        provider.stop()
        releaseWakeLock()
        preferences.edit { putBoolean(KEY_ACTIVE, false); remove(KEY_CONFIG) }
        publish(SimulationSnapshot())
        if (removeForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun restoreSession() {
        if (!preferences.getBoolean(KEY_ACTIVE, false)) {
            stopSession(true)
            return
        }
        val json = preferences.getString(KEY_CONFIG, null)
        if (json == null) stopSession(true) else startSession(json)
    }

    private suspend fun failAndStop(message: String) {
        stopping = true
        provider.stop()
        releaseWakeLock()
        preferences.edit { putBoolean(KEY_ACTIVE, false); remove(KEY_CONFIG) }
        publish(SimulationSnapshot(error = message))
        notificationManager.notify(NOTIFICATION_ID, buildNotification(SimulationBus.state.value))
        delay(1_500L)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publish(snapshot: SimulationSnapshot) = SimulationBus.publish(snapshot)

    private fun startForegroundCompat(snapshot: SimulationSnapshot) {
        val notification = buildNotification(snapshot)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(snapshot: SimulationSnapshot): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when {
            snapshot.error != null -> "MockRoute stopped"
            snapshot.countdownMs > 0 -> "MockRoute starts in ${(snapshot.countdownMs + 999) / 1_000}s"
            else -> "MockRoute active"
        }
        val content = when {
            snapshot.error != null -> snapshot.error
            snapshot.mode == SimulationMode.STATIC -> snapshot.point?.display() ?: "Preparing location"
            snapshot.point != null -> "${(snapshot.progress * 100).toInt()}% • ${formatDistance(snapshot.remainingMeters)} • ${snapshot.remainingMs.formatDuration()} left"
            else -> "Preparing simulation"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(openIntent)
            .setOngoing(snapshot.active && snapshot.error == null)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (snapshot.active && snapshot.error == null) {
            val pauseAction = if (snapshot.paused) ACTION_RESUME else ACTION_PAUSE
            val pauseLabel = if (snapshot.paused) "Resume" else "Pause"
            builder.addAction(0, pauseLabel, servicePendingIntent(pauseAction, 2))
            builder.addAction(0, "Stop", servicePendingIntent(ACTION_STOP, 3))
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode, Intent(this, SimulationService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.notification_channel_description)
        channel.setShowBadge(false)
        notificationManager.createNotificationChannel(channel)
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MockRoute:Simulation")
            .apply { setReferenceCounted(false); acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        stopping = true
        worker?.cancel()
        scope.cancel()
        provider.stop()
        releaseWakeLock()
        dispatcher.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.jasermomm.mockroute.action.START"
        const val ACTION_PAUSE = "com.jasermomm.mockroute.action.PAUSE"
        const val ACTION_RESUME = "com.jasermomm.mockroute.action.RESUME"
        const val ACTION_STOP = "com.jasermomm.mockroute.action.STOP"
        const val ACTION_SEEK = "com.jasermomm.mockroute.action.SEEK"
        private const val ACTION_RESTORE = "com.jasermomm.mockroute.action.RESTORE"
        private const val EXTRA_CONFIG = "config"
        private const val EXTRA_PROGRESS = "progress"
        private const val KEY_ACTIVE = "active"
        private const val KEY_CONFIG = "config"
        private const val CHANNEL_ID = "mockroute_active"
        private const val NOTIFICATION_ID = 4107
        private const val WAKE_LOCK_TIMEOUT_MS = 8L * 24L * 60L * 60L * 1_000L

        fun start(context: Context, config: SimulationConfig) {
            val intent = Intent(context, SimulationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG, JsonCodec.config(config).toString())
            ContextCompat.startForegroundService(context, intent)
        }

        fun action(context: Context, action: String) {
            context.startService(Intent(context, SimulationService::class.java).setAction(action))
        }

        fun seek(context: Context, progress: Double) {
            context.startService(
                Intent(context, SimulationService::class.java).setAction(ACTION_SEEK)
                    .putExtra(EXTRA_PROGRESS, progress.coerceIn(0.0, 1.0)),
            )
        }

        private fun Throwable?.toSetupMessage(): String = when (this) {
            is SecurityException -> "Select MockRoute as the mock location app"
            is IllegalArgumentException -> message?.take(100) ?: "The location is invalid"
            else -> "Mock location could not start"
        }

        private fun formatDistance(meters: Double): String =
            if (meters >= 1_000) "%.1f km".format(meters / 1_000) else "${meters.toInt()} m"
    }
}
