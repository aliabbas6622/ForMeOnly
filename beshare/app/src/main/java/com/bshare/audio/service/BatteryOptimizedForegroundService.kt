package com.bshare.audio.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bshare.audio.AudioCaptureManager
import com.bshare.audio.BluetoothRoutingManager
import com.bshare.audio.DeviceMixer
import com.bshare.audio.R

/**
 * Foreground service that manages audio capture and Bluetooth routing.
 * Optimized for Pixel 7a with proper battery management.
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
        
        // Start foreground with notification
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Start audio capture
        audioCaptureManager.startAudioCapture()
        
        // START_STICKY ensures the service restarts if killed
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        
        // Clean up resources
        audioCaptureManager.stopAudioCapture()
        bluetoothRoutingManager.unregisterAudioDeviceCallback()
    }
    
    /**
     * Create notification channel for Android O and above
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Bshare Audio Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Audio streaming to Bluetooth devices"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Create foreground service notification
     */
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.bshare.audio.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Bshare Audio")
            .setContentText("Streaming audio to Bluetooth devices")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
