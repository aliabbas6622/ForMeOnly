package com.example.wallpepr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.io.File

class AlarmService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val autoStopHandler = Handler(Looper.getMainLooper())
    private val autoStopRunnable = Runnable { stopSelf() }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarmId = intent?.getStringExtra(AlarmReceiver.EXTRA_ALARM_ID)
        val alarm = alarmId?.let { id -> Prefs.alarms(this).find { it.id == id } }

        if (alarm == null) {
            // Even if we have nothing to do, we MUST call startForeground if started 
            // via startForegroundService to prevent a crash.
            val dummyNotification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .build()
            startForeground(NOTIFICATION_ID, dummyNotification)
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(alarm), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(alarm))
        }
        playAlarmAudio(alarm)
        startVibration()

        // Safety net: auto-dismiss after 60 seconds so battery is not drained
        autoStopHandler.removeCallbacks(autoStopRunnable)
        autoStopHandler.postDelayed(autoStopRunnable, AUTO_STOP_MS)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        autoStopHandler.removeCallbacks(autoStopRunnable)
        stopAlarmAudio()
        stopVibration()
        super.onDestroy()
    }

    private fun playAlarmAudio(alarm: AlarmItem) {
        stopAlarmAudio()

        try {
            val attrContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                createAttributionContext("alarm_service")
            } else {
                this
            }

            val player = MediaPlayer()
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            player.setAudioAttributes(audioAttributes)
            player.isLooping = true

            val path = alarm.audioPath
            if (path != null && File(path).exists()) {
                player.setDataSource(attrContext, Uri.fromFile(File(path)))
            } else {
                val defaultAlarmUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                    ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
                player.setDataSource(attrContext, defaultAlarmUri)
            }

            player.prepare()
            player.start()
            mediaPlayer = player
            Log.i(TAG, "Playing alarm audio: $path")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing alarm audio", e)
        }
    }

    private fun startVibration() {
        try {
            val attrContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                createAttributionContext("alarm_service")
            } else {
                this
            }

            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                attrContext.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                attrContext.getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = longArrayOf(0, 500, 300, 500, 300, 1000)
            v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            vibrator = v
        } catch (e: Exception) {
            Log.w(TAG, "Vibration start failed", e)
        }
    }

    private fun stopVibration() {
        try { vibrator?.cancel() } catch (_: Exception) {}
        vibrator = null
    }

    private fun stopAlarmAudio() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
        }
        mediaPlayer = null
    }

    private fun buildNotification(alarm: AlarmItem): Notification {
        val dismissIntent = Intent(this, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_ALARM_DISMISS)
        val dismissPendingIntent = PendingIntent.getBroadcast(
            this,
            alarm.id.hashCode() + 2,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ringIntent = Intent(this, AlarmActivity::class.java).apply {
            putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarm.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val ringPendingIntent = PendingIntent.getActivity(
            this,
            alarm.id.hashCode() + 3,
            ringIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Alarm")
            .setContentText(alarm.cleanText.ifBlank { "Your alarm is ringing!" })
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setFullScreenIntent(ringPendingIntent, true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    getString(R.string.dismiss),
                    dismissPendingIntent
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Alarm Events",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableLights(true)
            enableVibration(true)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "AlarmService"
        private const val CHANNEL_ID = "alarm_channel"
        private const val NOTIFICATION_ID = 8
        private const val AUTO_STOP_MS = 60_000L  // 60-second safety auto-dismiss
    }
}
