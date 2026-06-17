package com.bshare.audio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.*
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages system-wide audio capture using MediaProjection API.
 * Captures audio from other apps (Spotify, YouTube, etc.) and routes it to Bluetooth devices.
 */
class AudioCaptureManager(
    private val context: Context,
    private val deviceMixer: DeviceMixer,
    private val bluetoothRoutingManager: BluetoothRoutingManager
) {
    
    companion object {
        private const val TAG = "AudioCaptureManager"
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.REMOTE_SUBMIX
    }
    
    private var mediaProjection: android.media.projection.MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    
    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()
    
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()
    
    private var captureJob: Job? = null
    private var isPaused = false
    
    /**
     * Request media projection permission for audio capture
     */
    fun requestMediaProjection(activity: Activity, requestCode: Int = 1001) {
        val mediaProjectionManager = context.getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as android.media.projection.MediaProjectionManager
        
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        activity.startActivityForResult(intent, requestCode)
    }
    
    /**
     * Handle media projection result
     */
    fun handleMediaProjectionResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            val mediaProjectionManager = context.getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as android.media.projection.MediaProjectionManager
            
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
            Log.d(TAG, "MediaProjection initialized successfully")
            
            startAudioCapture()
        } else {
            Log.e(TAG, "MediaProjection permission denied")
        }
    }
    
    /**
     * Start audio capture with low-latency configuration optimized for Pixel 7a
     */
    @OptIn(ExperimentalStdlibApi::class)
    private fun startAudioCapture() {
        if (_isCapturing.value) {
            Log.w(TAG, "Audio capture already running")
            return
        }
        
        try {
            // Calculate optimal buffer size for low latency
            val bufferSize = deviceMixer.calculateMinBufferSize()
            
            // Create AudioFormat for recording
            val recordFormat = AudioFormat.Builder()
                .setSampleRate(DeviceMixer.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .setEncoding(DeviceMixer.AUDIO_FORMAT)
                .build()
            
            // Create AudioRecord with low-latency flags
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            
            audioRecord = AudioRecord(
                audioAttributes,
                recordFormat,
                bufferSize
            )
            
            // Create AudioTrack for playback with low-latency flag
            val trackFormat = AudioFormat.Builder()
                .setSampleRate(DeviceMixer.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(DeviceMixer.AUDIO_FORMAT)
                .build()
            
            val trackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                .build()
            
            audioTrack = AudioTrack(
                trackAttributes,
                trackFormat,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            
            // Add LoudnessEnhancerEffect for volume normalization
            try {
                val loudnessEnhancer = LoudnessEnhancer(audioTrack.audioSessionId)
                loudnessEnhancer.enabled = true
                Log.d(TAG, "LoudnessEnhancer enabled")
            } catch (e: Exception) {
                Log.w(TAG, "Could not enable LoudnessEnhancer: ${e.message}")
            }
            
            audioRecord?.startRecording()
            audioTrack?.play()
            
            _isCapturing.value = true
            isPaused = false
            
            // Start capture coroutine
            captureJob = CoroutineScope(Dispatchers.IO).launch {
                captureAndRouteAudio(bufferSize)
            }
            
            Log.d(TAG, "Audio capture started with buffer size: $bufferSize")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
            stopAudioCapture()
        }
    }
    
    /**
     * Main audio capture and routing loop
     */
    private suspend fun captureAndRouteAudio(bufferSize: Int) {
        val pcmBuffer = ShortArray(bufferSize / 2) // 16-bit = 2 bytes per sample
        
        while (_isCapturing.value && !isPaused) {
            try {
                val bytesRead = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: 0
                
                if (bytesRead > 0) {
                    // Calculate audio level for visualizer
                    calculateAudioLevel(pcmBuffer, bytesRead)
                    
                    // Get current routing path
                    val routingPath = bluetoothRoutingManager.currentRoutingPath.value
                    
                    when (routingPath) {
                        is RoutingPath.PathA_DualAudio -> {
                            // Path A: Route to connected A2DP devices
                            routeToA2dpDevices(pcmBuffer, bytesRead)
                        }
                        is RoutingPath.PathB_Auracast -> {
                            // Path B: Route to LE Audio broadcast
                            routeToLeAudio(pcmBuffer, bytesRead)
                        }
                        else -> {
                            // No devices connected, just play locally
                            audioTrack?.write(pcmBuffer, 0, bytesRead, AudioFormat.WRITE_BLOCKING)
                        }
                    }
                }
                
                // Small delay to prevent CPU spinning
                delay(1)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio capture loop", e)
                delay(100)
            }
        }
    }
    
    /**
     * Route audio to A2DP devices (Path A)
     */
    private fun routeToA2dpDevices(pcmBuffer: ShortArray, bytesRead: Int) {
        val connectedDevices = bluetoothRoutingManager.connectedDevices.value
        
        connectedDevices.forEach { device ->
            deviceMixer.addDevice(device.address)
            val mixedBuffer = deviceMixer.applyMixing(pcmBuffer.copyOf(bytesRead), device.address)
            
            // Write to AudioTrack (system will route to active Bluetooth profile)
            audioTrack?.write(mixedBuffer, 0, mixedBuffer.size, AudioFormat.WRITE_BLOCKING)
        }
    }
    
    /**
     * Route audio to LE Audio broadcast (Path B - Auracast)
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun routeToLeAudio(pcmBuffer: ShortArray, bytesRead: Int) {
        // For LE Audio, the system handles LC3 encoding automatically
        // when AudioTrack is configured with USAGE_MEDIA and devices are connected via LE Audio
        
        val connectedDevices = bluetoothRoutingManager.connectedDevices.value
        
        connectedDevices.forEach { device ->
            deviceMixer.addDevice(device.address)
            val mixedBuffer = deviceMixer.applyMixing(pcmBuffer.copyOf(bytesRead), device.address)
            
            audioTrack?.write(mixedBuffer, 0, mixedBuffer.size, AudioFormat.WRITE_BLOCKING)
        }
    }
    
    /**
     * Calculate RMS audio level for visualizer
     */
    private fun calculateAudioLevel(pcmBuffer: ShortArray, bytesRead: Int) {
        var sum = 0L
        val samples = bytesRead / 2
        
        for (i in 0 until samples) {
            val sample = pcmBuffer[i].toDouble()
            sum += sample * sample
        }
        
        val rms = kotlin.math.sqrt(sum.toDouble() / samples)
        val normalizedLevel = (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
        
        _audioLevel.value = normalizedLevel
    }
    
    /**
     * Pause audio capture
     */
    fun pauseCapture() {
        isPaused = true
        audioRecord?.stop()
        audioTrack?.pause()
        Log.d(TAG, "Audio capture paused")
    }
    
    /**
     * Resume audio capture
     */
    fun resumeCapture() {
        if (_isCapturing.value) {
            isPaused = false
            audioRecord?.startRecording()
            audioTrack?.play()
            Log.d(TAG, "Audio capture resumed")
        }
    }
    
    /**
     * Stop audio capture
     */
    fun stopAudioCapture() {
        _isCapturing.value = false
        isPaused = false
        
        captureJob?.cancel()
        captureJob = null
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio resources", e)
        }
        
        audioRecord = null
        audioTrack = null
        
        Log.d(TAG, "Audio capture stopped")
    }
    
    /**
     * Set master volume
     */
    fun setMasterVolume(volume: Float) {
        deviceMixer.masterVolume = volume
    }
    
    /**
     * Set volume for a specific device
     */
    fun setDeviceVolume(deviceAddress: String, volume: Float) {
        deviceMixer.setDeviceVolume(deviceAddress, volume)
    }
}
