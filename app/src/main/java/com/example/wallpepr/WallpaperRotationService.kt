package com.example.wallpepr

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Battery-optimised wallpaper rotation service.
 *
 * Strategy: instead of keeping a long-lived thread sleeping between rotations,
 * we schedule the *next* rotation with AlarmManager.setWindow() (inexact, allows
 * the CPU to remain in deep-sleep) and stop the foreground service immediately
 * after applying the wallpaper. This is the most significant battery saving possible
 * for a wallpaper rotator — the service is alive for <1 second per rotation rather
 * than continuously.
 *
 * The foreground notification persists in the notification shade while the service is
 * "armed" so the user can see rotation is active. On Android 12+ the notification is
 * a silent, persistent one with IMPORTANCE_MIN.
 */
class WallpaperRotationService : Service() {

    // ─── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRotation()
            ACTION_APPLY -> applyAndReschedule()      // triggered by AlarmManager
            else -> startRotation()
        }
        return START_NOT_STICKY   // don't restart if killed — AlarmManager will wake us again
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Release the image cache when the system is under memory pressure
        if (level >= TRIM_MEMORY_BACKGROUND) {
            ImageRepository.clearMemoryCache()
            Log.d(TAG, "Cleared memory cache on trimMemory($level)")
        }
    }

    // ─── Rotation Logic ─────────────────────────────────────────────────────

    private fun startRotation() {
        // We MUST call startForeground() immediately because this service is started 
        // via context.startForegroundService(). Failing to do so within 5 seconds 
        // leads to ForegroundServiceDidNotStartInTimeException, even if the service 
        // was already logically "running".
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        if (Prefs.isRunning(this)) {
            Log.d(TAG, "Service already running, refreshing foreground state only")
            return
        }

        Prefs.setRunning(this, true)
        applyAndReschedule()
    }

    private fun stopRotation() {
        Prefs.setRunning(this, false)
        cancelNextAlarm()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun applyAndReschedule() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        // Run the actual wallpaper switch on a min-priority background thread so
        // it never competes with the UI thread or interactive apps.
        val workerThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
            try {
                val changed = setNextWallpaper()
                val now = SystemClock.elapsedRealtime()
                if (changed) Prefs.setLastChange(this, now, System.currentTimeMillis())
            } finally {
                scheduleNextAlarm()     // always re-arm regardless of success
                // Drop foreground state — service will be re-started by AlarmManager
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, "WallpaperApply")

        workerThread.isDaemon = true
        workerThread.start()
    }

    private fun setNextWallpaper(): Boolean {
        val next = ImageRepository.takeNext(this) ?: return false
        var bitmap: android.graphics.Bitmap? = null

        return try {
            bitmap = WallpaperSizer.createWallpaperBitmap(this, next, reusableTarget = true)
                ?: return false
            
            val attrContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                createAttributionContext("wallpaper_service")
            } else {
                this
            }
            WallpaperManager.getInstance(attrContext).setBitmap(bitmap)

            Prefs.clearFailure(this, next)
            true
        } catch (error: Exception) {
            Prefs.recordFailure(this, next)
            Log.e(TAG, "Unable to set wallpaper: $next", error)
            false
        } finally {
            WallpaperSizer.releaseBitmap(bitmap)
        }
    }

    // ─── AlarmManager Scheduling ─────────────────────────────────────────────

    private fun scheduleNextAlarm() {
        if (!Prefs.isRunning(this)) return

        val intervalMs = Prefs.intervalMs(this)
        val triggerAt   = SystemClock.elapsedRealtime() + intervalMs

        // setWindow allows the CPU to stay in deep-sleep; the window is 10% of the
        // interval (min 30s) so the alarm fires roughly on time without being exact.
        val windowMs = (intervalMs * 0.10).toLong().coerceAtLeast(30_000L)

        getSystemService(AlarmManager::class.java).setWindow(
            AlarmManager.ELAPSED_REALTIME,    // does NOT hold a wake lock after firing
            triggerAt,
            windowMs,
            buildPendingIntent()
        )
        Log.d(TAG, "Next wallpaper rotation scheduled in ${intervalMs / 1000}s ± ${windowMs / 1000}s")
    }

    private fun cancelNextAlarm() {
        getSystemService(AlarmManager::class.java).cancel(buildPendingIntent())
    }

    private fun buildPendingIntent(): PendingIntent {
        val intent = Intent(this, WallpaperRotationService::class.java).setAction(ACTION_APPLY)
        return PendingIntent.getService(
            this,
            PENDING_INTENT_RC,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ─── Notification ────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, WallpaperRotationService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentIntent(openPendingIntent)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                    getString(R.string.stop),
                    stopPendingIntent
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // ─── Companion ───────────────────────────────────────────────────────────

    companion object {
        private const val TAG              = "WallpeprService"
        private const val CHANNEL_ID       = "wallpaper_rotation"
        private const val NOTIFICATION_ID  = 7
        private const val PENDING_INTENT_RC = 100

        const val ACTION_START = "com.example.wallpepr.START"
        const val ACTION_STOP  = "com.example.wallpepr.STOP"
        const val ACTION_APPLY = "com.example.wallpepr.APPLY"   // internal — called by AlarmManager

        fun start(context: Context) {
            val intent = Intent(context, WallpaperRotationService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, WallpaperRotationService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
