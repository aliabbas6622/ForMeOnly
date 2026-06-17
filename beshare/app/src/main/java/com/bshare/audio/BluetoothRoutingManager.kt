package com.bshare.audio

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages Bluetooth routing logic with hybrid Path A/B approach.
 * Path A: Native Dual Audio (≤ 2 devices) using A2DP
 * Path B: Auracast/LE Audio Broadcast (> 2 devices) using LC3 codec
 */
class BluetoothRoutingManager(private val context: Context) {
    
    companion object {
        private const val TAG = "BluetoothRoutingManager"
        private const val MAX_A2DP_DEVICES = 2
    }
    
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    
    private val _connectedDevices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    val connectedDevices: StateFlow<List<BluetoothDeviceInfo>> = _connectedDevices.asStateFlow()
    
    private val _currentRoutingPath = MutableStateFlow<RoutingPath>(RoutingPath.None)
    val currentRoutingPath: StateFlow<RoutingPath> = _currentRoutingPath.asStateFlow()
    
    private val leScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    private var isScanning = false
    
    // Callback for audio device connection changes
    private val audioDeviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) {
            Log.d(TAG, "Audio devices added: ${addedDevices.size}")
            updateConnectedDevices()
        }
        
        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>) {
            Log.d(TAG, "Audio devices removed: ${removedDevices.size}")
            updateConnectedDevices()
        }
    }
    
    init {
        registerAudioDeviceCallback()
    }
    
    private fun registerAudioDeviceCallback() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
    }
    
    fun unregisterAudioDeviceCallback() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
    }
    
    /**
     * Update the list of connected Bluetooth audio devices and determine routing path
     */
    private fun updateConnectedDevices() {
        val connectedA2dpDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.A2DP)
        val connectedHeadsetDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.HEADSET)
        
        val allDevices = mutableListOf<BluetoothDeviceInfo>()
        
        connectedA2dpDevices.forEach { device ->
            allDevices.add(
                BluetoothDeviceInfo(
                    name = device.name ?: "Unknown",
                    address = device.address,
                    type = DeviceType.A2DP,
                    isConnected = true
                )
            )
        }
        
        connectedHeadsetDevices.forEach { device ->
            if (!allDevices.any { it.address == device.address }) {
                allDevices.add(
                    BluetoothDeviceInfo(
                        name = device.name ?: "Unknown",
                        address = device.address,
                        type = DeviceType.HEADSET,
                        isConnected = true
                    )
                )
            }
        }
        
        _connectedDevices.value = allDevices
        
        // Determine routing path based on device count
        updateRoutingPath(allDevices.size)
    }
    
    /**
     * Determine whether to use Path A (Dual Audio) or Path B (Auracast)
     */
    private fun updateRoutingPath(deviceCount: Int) {
        val newPath = when {
            deviceCount == 0 -> RoutingPath.None
            deviceCount <= MAX_A2DP_DEVICES -> RoutingPath.PathA_DualAudio
            else -> RoutingPath.PathB_Auracast
        }
        
        if (_currentRoutingPath.value != newPath) {
            Log.d(TAG, "Switching from ${_currentRoutingPath.value} to $newPath")
            _currentRoutingPath.value = newPath
        }
    }
    
    /**
     * Start scanning for LE Audio devices (for Auracast)
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun startLeAudioScan(callback: ScanCallback) {
        if (isScanning) return
        
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BluetoothUuid.LE_AUDIO))
            .build()
        
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        
        leScanner?.startScan(listOf(scanFilter), scanSettings, callback)
        isScanning = true
        Log.d(TAG, "Started LE Audio scan")
    }
    
    /**
     * Stop scanning for LE Audio devices
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun stopLeAudioScan(callback: ScanCallback) {
        if (!isScanning) return
        
        leScanner?.stopScan(callback)
        isScanning = false
        Log.d(TAG, "Stopped LE Audio scan")
    }
    
    /**
     * Connect to a Bluetooth device
     */
    fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to device: ${device.name}")
        // Connection handling will be done through system Bluetooth settings
        // This method can be extended for custom connection logic
    }
    
    /**
     * Disconnect from a Bluetooth device
     */
    fun disconnectFromDevice(device: BluetoothDevice) {
        Log.d(TAG, "Disconnecting from device: ${device.name}")
        // Disconnection handling
    }
    
    /**
     * Check if device supports LE Audio (Auracast)
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun isLeAudioSupported(): Boolean {
        return bluetoothAdapter?.isLeAudioSupported ?: false
    }
    
    /**
     * Get current routing path description
     */
    fun getRoutingPathDescription(): String {
        return when (_currentRoutingPath.value) {
            RoutingPath.None -> "No devices connected"
            RoutingPath.PathA_DualAudio -> "Path A: Dual Audio (A2DP)"
            RoutingPath.PathB_Auracast -> "Path B: Auracast (LE Audio)"
        }
    }
}

/**
 * Data class representing a Bluetooth device
 */
data class BluetoothDeviceInfo(
    val name: String,
    val address: String,
    val type: DeviceType,
    val isConnected: Boolean
)

/**
 * Enum representing device types
 */
enum class DeviceType {
    A2DP,
    HEADSET,
    LE_AUDIO
}

/**
 * Enum representing routing paths
 */
sealed class RoutingPath {
    object None : RoutingPath()
    object PathA_DualAudio : RoutingPath()
    object PathB_Auracast : RoutingPath()
}
