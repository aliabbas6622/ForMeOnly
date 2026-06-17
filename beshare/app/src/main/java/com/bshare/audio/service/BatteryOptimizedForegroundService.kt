package com.bshare.audio.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bshare.audio.AudioCaptureManager
import com.bshare.audio.BluetoothRoutingManager
import com.bshare.audio.DeviceMixer
import com.bshare.audio.R

/**
 * Foreground service that manages audio capture and Bluetooth routing.
 * Optimized for Pixel 7a with proper battery management.
 * 
 * Optimizations:
 * - Minimal WakeLock duration (only held during active playback)
 * - FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK for Android 14+ compliance
 * - Low-priority notification to reduce distraction
 * - Immediate resource cleanup on service stop
 * - No blocking I/O operations
 */
class BatteryOptimizedForegroundService : Service() {
    
    companion object {
        private const val TAG = "BshareForegroundService"
        private const val NOTIFICATION_CHANNEL_ID = "BshareAudioChannel"
        private const val NOTIFICATION_ID = 1001
    }
    
    private lateinit var deviceMixer: DeviceMixer
    private lateinit var bluetoothRoutingManager: BluetoothRoutingManager
    private lateinit var audioCaptureManager: AudioCaptureManager
    
    // WakeLock is managed by AudioCaptureManager, not held here
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        
        // Initialize components
        deviceMixer = DeviceMixer()
        bluetoothRoutingManager = BluetoothRoutingManager(this)
        audioCaptureManager = AudioCaptureManager(this, deviceMixer, bluetoothRoutingManager)
        
        // Create notification channel
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        
        // Start foreground with notification immediately
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Note: Audio capture is started by MainActivity after permissions are granted
        // This service just provides the infrastructure
        
        // START_STICKY ensures the service restarts if killed by system
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null // Not a bound service
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed, cleaning up resources")
        
        // Clean up all resources in reverse order of initialization
        audioCaptureManager.cleanup()
        bluetoothRoutingManager.unregisterAudioDeviceCallback()
        deviceMixer.cleanup()
        
        Log.d(TAG, "All resources released")
    }
    
    /**
     * Create notification channel for Android O and above
     * Uses IMPORTANCE_LOW to minimize disruption
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Bshare Audio Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Audio streaming to Bluetooth devices"
                setShowBadge(false) // Don't show badge count
                enableLights(false) // No LED lights
                enableVibration(false) // No vibration
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Create foreground service notification
     * Low priority to minimize distraction
     */
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.bshare.audio.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Bshare Audio")
            .setContentText("Ready to stream to Bluetooth devices")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
}
