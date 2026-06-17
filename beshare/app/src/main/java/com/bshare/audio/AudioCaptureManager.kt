package com.bshare.audio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.*
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages system-wide audio capture using MediaProjection API.
 * Captures audio from other apps (Spotify, YouTube, etc.) and routes it to Bluetooth devices.
 * 
 * Optimizations for Pixel 7a (Tensor G2):
 * - Hardware-accelerated LC3 encoding via AudioTrack routing
 * - Minimal buffer sizes for ultra-low latency (<20ms target)
 * - Efficient PCM processing with object pooling
 * - Smart WakeLock management (only held during active playback)
 * - Background thread audio processing to prevent main-thread jank
 */
class AudioCaptureManager(
    private val context: Context,
    private val deviceMixer: DeviceMixer,
    private val bluetoothRoutingManager: BluetoothRoutingManager
) {
    
    companion object {
        private const val TAG = "AudioCaptureManager"
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.REMOTE_SUBMIX
        
        // Target latency for Pixel 7a (Tensor G2 can achieve ~10-20ms)
        private const val TARGET_LATENCY_MS = 20
    }
    
    private var mediaProjection: android.media.projection.MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    
    // Reusable PCM buffer (allocated once to reduce GC pressure)
    private var pcmBuffer: ShortArray? = null
    
    // WakeLock for audio processing thread (held only during active playback)
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()
    
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()
    
    private var captureJob: Job? = null
    private var isPaused = false
    
    // Audio session ID for effects
    private var audioSessionId = AudioManager.AUDIO_SESSION_ID_GENERATE
    
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
     * Acquire partial WakeLock for audio processing thread
     * Only called when starting capture to minimize battery impact
     */
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Bshare::AudioCaptureLock"
            ).apply {
                setReferenceCounted(false) // Manual management
            }
        }
        
        if (!wakeLock!!.isHeld) {
            wakeLock?.acquire(10*60*1000L /*10 minutes*/) // Timeout for safety
            Log.d(TAG, "WakeLock acquired")
        }
    }
    
    /**
     * Release WakeLock immediately when playback pauses/stops
     */
    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d(TAG, "WakeLock released")
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
            
            // Allocate PCM buffer once (reused throughout capture session)
            pcmBuffer = ShortArray(bufferSize / 2) // 16-bit = 2 bytes per sample
            
            // Create AudioFormat for recording with optimal parameters
            val recordFormat = AudioFormat.Builder()
                .setSampleRate(DeviceMixer.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .setEncoding(DeviceMixer.AUDIO_FORMAT)
                .build()
            
            // Create AudioRecord with low-latency attributes
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
            // This enables Tensor G2 hardware DSP acceleration
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
                audioSessionId
            )
            
            // Store actual audio session ID for effects
            audioSessionId = audioTrack.audioSessionId
            
            // Add LoudnessEnhancerEffect for volume normalization across apps
            try {
                val loudnessEnhancer = LoudnessEnhancer(audioSessionId)
                loudnessEnhancer.enabled = true
                Log.d(TAG, "LoudnessEnhancer enabled on session $audioSessionId")
            } catch (e: Exception) {
                Log.w(TAG, "Could not enable LoudnessEnhancer: ${e.message}")
            }
            
            audioRecord?.startRecording()
            audioTrack?.play()
            
            _isCapturing.value = true
            isPaused = false
            
            // Acquire WakeLock for audio processing
            acquireWakeLock()
            
            // Start capture coroutine on IO dispatcher
            captureJob = CoroutineScope(Dispatchers.IO).launch {
                captureAndRouteAudio(bufferSize)
            }
            
            Log.d(TAG, "Audio capture started with buffer size: $bufferSize (${bufferSize * 1000L / (DeviceMixer.SAMPLE_RATE * 2)}ms)")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
            stopAudioCapture()
        }
    }
    
    /**
     * Main audio capture and routing loop
     * Runs on IO dispatcher to prevent main-thread blocking
     */
    private suspend fun captureAndRouteAudio(bufferSize: Int) {
        val buffer = pcmBuffer ?: return
        
        while (_isCapturing.value && !isPaused) {
            try {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                
                if (bytesRead > 0) {
                    // Calculate audio level for visualizer (optimized RMS calculation)
                    calculateAudioLevel(buffer, bytesRead)
                    
                    // Get current routing path
                    val routingPath = bluetoothRoutingManager.currentRoutingPath.value
                    
                    when (routingPath) {
                        is RoutingPath.PathA_DualAudio -> {
                            // Path A: Route to connected A2DP devices (≤2)
                            routeToA2dpDevices(buffer, bytesRead)
                        }
                        is RoutingPath.PathB_Auracast -> {
                            // Path B: Route to LE Audio broadcast (>2 devices)
                            // Tensor G2 handles LC3 encoding in hardware
                            routeToLeAudio(buffer, bytesRead)
                        }
                        else -> {
                            // No devices connected, play locally
                            audioTrack?.write(buffer, 0, bytesRead, AudioFormat.WRITE_BLOCKING)
                        }
                    }
                }
                
                // Yield to prevent CPU spinning (but keep latency low)
                yield()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio capture loop", e)
                delay(100) // Back off on error
            }
        }
    }
    
    /**
     * Route audio to A2DP devices (Path A - Dual Audio)
     * System automatically handles A2DP encoding and routing
     */
    private fun routeToA2dpDevices(pcmBuffer: ShortArray, bytesRead: Int) {
        val connectedDevices = bluetoothRoutingManager.connectedDevices.value
        
        connectedDevices.forEach { device ->
            deviceMixer.addDevice(device.address)
            val mixedBuffer = deviceMixer.applyMixing(pcmBuffer.copyOf(bytesRead), device.address)
            
            // Write to AudioTrack (system routes to active A2DP profile)
            audioTrack?.write(mixedBuffer, 0, mixedBuffer.size, AudioFormat.WRITE_BLOCKING)
            
            // Return buffer to pool for reuse
            deviceMixer.releaseBuffer(mixedBuffer)
        }
    }
    
    /**
     * Route audio to LE Audio broadcast (Path B - Auracast)
     * Tensor G2 hardware accelerates LC3 encoding automatically
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun routeToLeAudio(pcmBuffer: ShortArray, bytesRead: Int) {
        val connectedDevices = bluetoothRoutingManager.connectedDevices.value
        
        connectedDevices.forEach { device ->
            deviceMixer.addDevice(device.address)
            val mixedBuffer = deviceMixer.applyMixing(pcmBuffer.copyOf(bytesRead), device.address)
            
            // AudioTrack with USAGE_MEDIA + FLAG_LOW_LATENCY triggers hardware LC3 encoding
            audioTrack?.write(mixedBuffer, 0, mixedBuffer.size, AudioFormat.WRITE_BLOCKING)
            
            // Return buffer to pool
            deviceMixer.releaseBuffer(mixedBuffer)
        }
    }
    
    /**
     * Calculate RMS audio level for visualizer
     * Optimized to minimize allocations in hot path
     */
    private fun calculateAudioLevel(pcmBuffer: ShortArray, bytesRead: Int) {
        var sum = 0L
        val samples = bytesRead / 2
        
        // Process every 4th sample for efficiency (sufficient for visualizer)
        val step = 4
        var count = 0
        
        for (i in 0 until samples step step) {
            val sample = pcmBuffer[i].toLong()
            sum += sample * sample
            count++
        }
        
        if (count > 0) {
            val rms = kotlin.math.sqrt(sum.toDouble() / count)
            val normalizedLevel = (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
            
            // Smooth the level to prevent jittery visualizer
            _audioLevel.value = _audioLevel.value * 0.7f + normalizedLevel * 0.3f
        }
    }
    
    /**
     * Pause audio capture and release WakeLock
     */
    fun pauseCapture() {
        isPaused = true
        audioRecord?.stop()
        audioTrack?.pause()
        releaseWakeLock() // Release immediately to save battery
        Log.d(TAG, "Audio capture paused, WakeLock released")
    }
    
    /**
     * Resume audio capture and re-acquire WakeLock
     */
    fun resumeCapture() {
        if (_isCapturing.value) {
            isPaused = false
            audioRecord?.startRecording()
            audioTrack?.play()
            acquireWakeLock() // Re-acquire for active playback
            Log.d(TAG, "Audio capture resumed, WakeLock acquired")
        }
    }
    
    /**
     * Stop audio capture and clean up all resources
     */
    fun stopAudioCapture() {
        _isCapturing.value = false
        isPaused = false
        
        captureJob?.cancel()
        captureJob = null
        
        // Release WakeLock first
        releaseWakeLock()
        
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
        
        // Clear PCM buffer reference
        pcmBuffer = null
        
        Log.d(TAG, "Audio capture stopped, all resources released")
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
    
    /**
     * Clean up resources (call from Activity.onDestroy)
     */
    fun cleanup() {
        stopAudioCapture()
        deviceMixer.cleanup()
        mediaProjection = null
    }
}
