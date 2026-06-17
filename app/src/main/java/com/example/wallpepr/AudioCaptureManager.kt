package com.example.wallpepr

import android.annotation.SuppressLint
import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaProjection
import android.media.audiofx.LoudnessEnhancerEffect
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AudioCaptureManager - System-wide audio capture using MediaProjection API
 * Optimized for Pixel 7a Tensor G2 with low-latency buffer configuration
 */
class AudioCaptureManager(
    private val deviceMixer: DeviceMixer
) {
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var loudnessEnhancer: LoudnessEnhancerEffect? = null
    
    private val isCapturing = AtomicBoolean(false)
    private var captureThread: Thread? = null
    
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Reusable PCM buffer (allocated once to reduce GC pressure)
    private var pcmBuffer: ShortArray? = null
    
    companion object {
        private const val TAG = "AudioCaptureManager"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
    
    @SuppressLint("MissingPermission")
    fun startCapture(mediaProjection: MediaProjection, context: Activity) {
        if (isCapturing.getAndSet(true)) return
        
        try {
            // Configure audio capture from other apps
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            
            // Calculate optimal buffer size for low latency on Tensor G2
            val minBufferSize = maxOf(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
                AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AUDIO_FORMAT)
            )
            val bufferSize = minBufferSize * 2 // Double for safety margin
            
            // Allocate buffer once (reused throughout capture session)
            pcmBuffer = ShortArray(bufferSize / 2) // Convert bytes to shorts
            
            // Create AudioRecord with playback capture config
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            
            val audioFormat = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .setEncoding(AUDIO_FORMAT)
                .build()
            
            audioRecord = AudioRecord(
                captureConfig,
                audioFormat,
                bufferSize
            )
            
            // Create AudioTrack for output (routes to active Bluetooth profile)
            val trackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setFlags(AudioAttributes.FLAG_LOW_LATENCY) // Critical for Pixel 7a
                .build()
            
            audioTrack = AudioTrack(
                trackAttributes,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            
            // Add loudness enhancer for volume normalization across apps
            val sessionId = audioTrack!!.audioSessionId
            loudnessEnhancer = LoudnessEnhancerEffect(sessionId).apply {
                enabled = true
                setTargetGain(500) // Mild boost
            }
            
            // Start recording and playback
            audioRecord?.startRecording()
            audioTrack?.play()
            
            // Start capture loop in background thread
            captureThread = Thread(this::captureLoop, "Bshare-Capture").apply {
                priority = Thread.MAX_PRIORITY // Real-time priority for low latency
                start()
            }
            
            Log.i(TAG, "Audio capture started with ${bufferSize} byte buffer")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
            isCapturing.set(false)
            stopCapture()
        }
    }
    
    fun stopCapture() {
        if (!isCapturing.getAndSet(false)) return
        
        captureThread?.let {
            it.interrupt()
            try {
                it.join(1000)
            } catch (e: InterruptedException) {
                // Ignore
            }
        }
        captureThread = null
        
        audioRecord?.let {
            try {
                it.stop()
                it.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing AudioRecord", e)
            }
        }
        audioRecord = null
        
        audioTrack?.let {
            try {
                it.stop()
                it.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing AudioTrack", e)
            }
        }
        audioTrack = null
        
        loudnessEnhancer?.let {
            try {
                it.enabled = false
                it.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing LoudnessEnhancer", e)
            }
        }
        loudnessEnhancer = null
        
        // Return buffer to pool
        pcmBuffer?.let { buffer ->
            deviceMixer.releaseBuffer(buffer)
        }
        pcmBuffer = null
        
        Log.i(TAG, "Audio capture stopped")
    }
    
    private fun captureLoop() {
        val record = audioRecord ?: return
        val track = audioTrack ?: return
        val buffer = pcmBuffer ?: return
        
        try {
            while (isCapturing.get() && !Thread.currentThread().isInterrupted) {
                // Read PCM data from captured audio
                val bytesRead = record.read(buffer, 0, buffer.size)
                
                if (bytesRead > 0) {
                    // Mix audio with master/device volumes
                    val mixedBuffer = deviceMixer.mixBuffer(buffer, 1)
                    
                    // Write to AudioTrack (automatically routes to Bluetooth)
                    track.write(mixedBuffer, 0, mixedBuffer.size, AudioTrack.WRITE_BLOCKING)
                    
                    // Return mixed buffer to pool for reuse
                    deviceMixer.releaseBuffer(mixedBuffer)
                } else if (bytesRead < 0) {
                    Log.e(TAG, "AudioRecord error: $bytesRead")
                    break
                }
                
                // Yield to ensure low latency (better than sleep for real-time audio)
                Thread.yield()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture loop error", e)
        }
    }
    
    /**
     * Get current audio level for visualizer (thread-safe)
     */
    fun getCurrentAudioLevel(): Float {
        val buffer = pcmBuffer ?: return 0f
        return deviceMixer.calculateRms(buffer)
    }
}
