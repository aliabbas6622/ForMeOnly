package com.bshare.audio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.bshare.audio.ui.theme.BshareTheme

/**
 * Main activity that handles permissions and hosts the Compose UI.
 */
class MainActivity : ComponentActivity() {
    
    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1001
    }
    
    private lateinit var deviceMixer: DeviceMixer
    private lateinit var bluetoothRoutingManager: BluetoothRoutingManager
    private lateinit var audioCaptureManager: AudioCaptureManager
    
    // Permission request launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            // All permissions granted, start audio capture
            startAudioCapture()
        } else {
            // Handle missing permissions
            handleMissingPermissions()
        }
    }
    
    // Media projection result launcher
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        audioCaptureManager.handleMediaProjectionResult(result.resultCode, result.data)
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize components
        deviceMixer = DeviceMixer()
        bluetoothRoutingManager = BluetoothRoutingManager(this)
        audioCaptureManager = AudioCaptureManager(this, deviceMixer, bluetoothRoutingManager)
        
        setContent {
            BshareTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BshareMixerUI(
                        audioCaptureManager = audioCaptureManager,
                        bluetoothRoutingManager = bluetoothRoutingManager,
                        deviceMixer = deviceMixer,
                        onRequestPermissions = { checkAndRequestPermissions() },
                        onMediaProjectionRequest = { requestMediaProjection() }
                    )
                }
            }
        }
        
        // Check and request permissions
        checkAndRequestPermissions()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        bluetoothRoutingManager.unregisterAudioDeviceCallback()
        audioCaptureManager.stopAudioCapture()
    }
    
    /**
     * Check and request required permissions
     */
    private fun checkAndRequestPermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
        
        // Add notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isEmpty()) {
            // All permissions already granted
            startAudioCapture()
        } else {
            // Request missing permissions
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
    
    /**
     * Handle missing permissions
     */
    private fun handleMissingPermissions() {
        // Show dialog or navigate to settings
        // For now, we'll just log it
        android.util.Log.w("MainActivity", "Some permissions were denied")
    }
    
    /**
     * Start audio capture after permissions are granted
     */
    private fun startAudioCapture() {
        // Request media projection for system audio capture
        requestMediaProjection()
    }
    
    /**
     * Request media projection permission
     */
    private fun requestMediaProjection() {
        audioCaptureManager.requestMediaProjection(this, REQUEST_MEDIA_PROJECTION)
    }
}
