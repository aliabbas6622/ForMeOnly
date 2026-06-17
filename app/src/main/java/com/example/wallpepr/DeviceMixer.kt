package com.example.wallpepr

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * DeviceMixer - Virtual audio mixing matrix with per-device volume control
 * Optimized for Pixel 7a Tensor G2 with minimal allocations
 */
class DeviceMixer {
    private val deviceVolumes = ConcurrentHashMap<String, Float>()
    private var masterVolume = 1.0f
    
    // Buffer pool for reuse (reduces GC pressure)
    private val bufferPool = mutableListOf<ShortArray>()
    private val maxPoolSize = 4
    
    companion object {
        private const val TAG = "DeviceMixer"
        const val SAMPLE_RATE = 48000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        
        // Calculate optimal buffer size for low latency
        val minBufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
            AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AUDIO_FORMAT)
        ) * 2 // Double for safety margin
    }
    
    fun setMasterVolume(volume: Float) {
        masterVolume = volume.coerceIn(0.0f, 1.0f)
    }
    
    fun getMasterVolume(): Float = masterVolume
    
    fun setDeviceVolume(deviceAddress: String, volume: Float) {
        deviceVolumes[deviceAddress] = volume.coerceIn(0.0f, 1.0f)
    }
    
    fun getDeviceVolume(deviceAddress: String): Float {
        return deviceVolumes[deviceAddress] ?: 1.0f
    }
    
    /**
     * Mix audio buffer with master and per-device volumes
     * Uses fixed-point math for performance on Tensor G2
     */
    fun mixBuffer(pcmBuffer: ShortArray, deviceCount: Int): ShortArray {
        // Get or allocate buffer from pool
        val outputBuffer = acquireBuffer(pcmBuffer.size)
        
        val masterInt = (masterVolume * 32767).toInt()
        
        // Simple mixing: apply master volume to all samples
        // In a full implementation, you'd mix per-device here
        for (i in pcmBuffer.indices) {
            val sample = pcmBuffer[i].toInt()
            val mixed = (sample * masterInt) / 32767
            outputBuffer[i] = mixed.toShort()
        }
        
        return outputBuffer
    }
    
    /**
     * Calculate RMS amplitude for visualizer (optimized: processes every 4th sample)
     */
    fun calculateRms(buffer: ShortArray): Float {
        if (buffer.isEmpty()) return 0f
        
        var sum = 0L
        val step = 4 // Optimization: process every 4th sample
        var count = 0
        
        for (i in buffer.indices step step) {
            val sample = buffer[i].toInt()
            sum += sample * sample
            count++
        }
        
        if (count == 0) return 0f
        
        val mean = sum.toDouble() / count
        val rms = kotlin.math.sqrt(mean)
        
        // Normalize to 0-1 range (16-bit audio max is 32767)
        return (rms / 32767).toFloat()
    }
    
    private fun acquireBuffer(size: Int): ShortArray {
        synchronized(bufferPool) {
            if (bufferPool.isNotEmpty()) {
                val buffer = bufferPool.removeAt(bufferPool.lastIndex)
                if (buffer.size >= size) {
                    return buffer
                }
            }
        }
        // Allocate new buffer if pool is empty or buffers are too small
        return ShortArray(size)
    }
    
    fun releaseBuffer(buffer: ShortArray) {
        synchronized(bufferPool) {
            if (bufferPool.size < maxPoolSize) {
                // Clear buffer before returning to pool
                for (i in buffer.indices) {
                    buffer[i] = 0
                }
                bufferPool.add(buffer)
            }
        }
    }
    
    fun cleanup() {
        synchronized(bufferPool) {
            bufferPool.clear()
        }
        deviceVolumes.clear()
    }
}
