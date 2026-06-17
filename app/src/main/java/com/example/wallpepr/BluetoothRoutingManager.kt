package com.example.wallpepr

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * BluetoothRoutingManager - Hybrid Path A/B routing state machine
 * Optimized for Pixel 7a with event-driven updates and low-power scanning
 */
class BluetoothRoutingManager {
    private var audioManager: android.media.AudioManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var leScanner: BluetoothLeScanner? = null
    private var isScanning = false
    
    private val connectedDevices = CopyOnWriteArrayList<BluetoothDeviceState>()
    private var currentPath: RoutingPath = RoutingPath.PATH_A
    
    private var deviceCallback: ((List<BluetoothDeviceState>) -> Unit)? = null
    private var routingCallback: ((RoutingPath) -> Unit)? = null
    
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {}
        override fun onBatchScanResults(results: List<ScanResult>) {}
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            stopScanning()
        }
    }
    
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            updateConnectedDevices()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            updateConnectedDevices()
        }
    }
    
    companion object {
        private const val TAG = "BluetoothRoutingManager"
        private const val DEVICE_COUNT_THRESHOLD = 2
    }
    
    fun setDeviceCallback(callback: (List<BluetoothDeviceState>) -> Unit) {
        deviceCallback = callback
    }
    
    fun setRoutingCallback(callback: (RoutingPath) -> Unit) {
        routingCallback = callback
    }
    
    @SuppressLint("MissingPermission")
    fun onResume(context: Context) {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter
        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        updateConnectedDevices()
    }
    
    fun onPause() {
        stopScanning()
        audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
    }
    
    fun onDestroy() {
        onPause()
        connectedDevices.clear()
        deviceCallback = null
        routingCallback = null
    }
    
    @SuppressLint("MissingPermission")
    fun startRouting(context: Context) {
        updateConnectedDevices()
        if (connectedDevices.isEmpty() && !isScanning) {
            startLowPowerScan()
        }
    }
    
    fun stopRouting() {
        stopScanning()
        connectedDevices.clear()
        notifyDeviceUpdate()
    }
    
    fun getConnectedDevices(): List<BluetoothDeviceState> = connectedDevices.toList()
    
    @SuppressLint("MissingPermission")
    private fun updateConnectedDevices() {
        val audioMgr = audioManager ?: return
        val btAdapter = bluetoothAdapter ?: return
        
        val newDevices = mutableListOf<BluetoothDeviceState>()
        val audioDevices = audioMgr.getDevices(android.media.AudioManager.GET_DEVICES_ALL)
        
        audioDevices.forEach { device ->
            if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || 
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                
                val isLeAudio = device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                               device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
               
                newDevices.add(
                    BluetoothDeviceState(
                        address = device.address ?: "unknown",
                        name = device.productName?.toString() ?: "Unknown Device",
                        isConnected = true,
                        isLeAudio = isLeAudio
                    )
                )
            }
        }
        
        connectedDevices.clear()
        connectedDevices.addAll(newDevices)
        
        val totalDevices = connectedDevices.size
        val newPath = if (totalDevices <= DEVICE_COUNT_THRESHOLD) {
            RoutingPath.PATH_A
        } else {
            RoutingPath.PATH_B
        }
        
        if (newPath != currentPath) {
            currentPath = newPath
            Log.i(TAG, "Routing switched to ${if (newPath == RoutingPath.PATH_A) "Path A (Dual Audio)" else "Path B (Auracast)"}")
            routingCallback?.invoke(newPath)
            if (isScanning) stopScanning()
        }
        
        notifyDeviceUpdate()
    }
    
    @SuppressLint("MissingPermission")
    private fun startLowPowerScan() {
        val btAdapter = bluetoothAdapter ?: return
        if (!btAdapter.isEnabled) return
        
        leScanner = btAdapter.bluetoothLeScanner
        isScanning = true
        
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setReportDelay(5000)
            .build()
        
        val filters = listOf(ScanFilter.Builder().build())
        
        try {
            leScanner?.startScan(filters, scanSettings, scanCallback)
            Log.d(TAG, "Started low-power Bluetooth scan")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scan", e)
            isScanning = false
        }
    }
    
    private fun stopScanning() {
        if (!isScanning) return
        try {
            leScanner?.stopScan(scanCallback)
            Log.d(TAG, "Stopped Bluetooth scan")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop scan", e)
        } finally {
            isScanning = false
        }
    }
    
    private fun notifyDeviceUpdate() {
        deviceCallback?.invoke(connectedDevices.toList())
    }
}

data class BluetoothDeviceState(
    val address: String,
    val name: String,
    val isConnected: Boolean,
    val isLeAudio: Boolean
)

enum class RoutingPath { PATH_A, PATH_B }
