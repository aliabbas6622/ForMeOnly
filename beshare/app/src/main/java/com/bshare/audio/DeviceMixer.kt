package com.bshare.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages audio mixing for multiple output devices.
 * Implements a virtual audio mixing matrix with per-device volume control.
 */
class DeviceMixer {
    
    companion object {
        private const val TAG = "DeviceMixer"
        const val SAMPLE_RATE = 48000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
    
    // Map of device address to volume level (0.0f to 1.0f)
    private val _deviceVolumes = MutableStateFlow<Map<String, Float>>(emptyMap())
    val deviceVolumes: StateFlow<Map<String, Float>> = _deviceVolumes.asStateFlow()
    
    private var _masterVolume = 1.0f
    var masterVolume: Float
        get() = _masterVolume
        set(value) {
            _masterVolume = value.coerceIn(0.0f, 1.0f)
            Log.d(TAG, "Master volume set to: $_masterVolume")
        }
    
    /**
     * Set volume for a specific device
     */
    fun setDeviceVolume(deviceAddress: String, volume: Float) {
        val clampedVolume = volume.coerceIn(0.0f, 1.0f)
        val currentMap = _deviceVolumes.value.toMutableMap()
        currentMap[deviceAddress] = clampedVolume
        _deviceVolumes.value = currentMap
        Log.d(TAG, "Device $deviceAddress volume set to: $clampedVolume")
    }
    
    /**
     * Get volume for a specific device
     */
    fun getDeviceVolume(deviceAddress: String): Float {
        return _deviceVolumes.value[deviceAddress] ?: 1.0f
    }
    
    /**
     * Remove a device from the mixer
     */
    fun removeDevice(deviceAddress: String) {
        val currentMap = _deviceVolumes.value.toMutableMap()
        currentMap.remove(deviceAddress)
        _deviceVolumes.value = currentMap
        Log.d(TAG, "Device $deviceAddress removed from mixer")
    }
    
    /**
     * Add or update a device in the mixer with default volume
     */
    fun addDevice(deviceAddress: String, initialVolume: Float = 1.0f) {
        if (!_deviceVolumes.value.containsKey(deviceAddress)) {
            val currentMap = _deviceVolumes.value.toMutableMap()
            currentMap[deviceAddress] = initialVolume
            _deviceVolumes.value = currentMap
            Log.d(TAG, "Device $deviceAddress added to mixer")
        }
    }
    
    /**
     * Apply mixing to PCM buffer
     * This applies master volume and per-device volume multipliers
     */
    fun applyMixing(pcmBuffer: ShortArray, deviceAddress: String): ShortArray {
        val deviceVolume = getDeviceVolume(deviceAddress)
        val combinedVolume = (_masterVolume * deviceVolume).coerceIn(0.0f, 1.0f)
        
        // Apply volume scaling to PCM buffer
        val mixedBuffer = pcmBuffer.copyOf()
        for (i in mixedBuffer.indices) {
            mixedBuffer[i] = (mixedBuffer[i] * combinedVolume).toInt().coerceIn(
                Short.MIN_VALUE.toInt(),
                Short.MAX_VALUE.toInt()
            ).toShort()
        }
        
        return mixedBuffer
    }
    
    /**
     * Calculate minimum buffer size for low-latency audio
     */
    fun calculateMinBufferSize(): Int {
        val recordMinSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AUDIO_FORMAT
        )
        
        val trackMinSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        
        // Use the larger of the two, multiplied by 2 for safety margin
        val minSize = maxOf(recordMinSize, trackMinSize)
        val bufferSize = minSize * 2
        
        Log.d(TAG, "Calculated buffer size: $bufferSize (record: $recordMinSize, track: $trackMinSize)")
        return bufferSize
    }
    
    /**
     * Create AudioFormat for low-latency recording
     */
    fun createAudioRecordFormat(): AudioFormat {
        return AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .setEncoding(AUDIO_FORMAT)
            .build()
    }
    
    /**
     * Create AudioFormat for low-latency playback
     */
    fun createAudioTrackFormat(): AudioFormat {
        return AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .setEncoding(AUDIO_FORMAT)
            .build()
    }
}
