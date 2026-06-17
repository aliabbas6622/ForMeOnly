package com.example.wallpepr

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaProjection
import android.media.MediaProjectionManager
import android.media.audiofx.LoudnessEnhancerEffect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class BshareTool : Tool {
    override val id: String = "bshare"
    override val nameResId: Int = R.string.tool_bshare
    override val iconResId: Int = android.R.drawable.ic_btn_speak_now

    private var activity: Activity? = null
    private var rootLayout: View? = null

    private lateinit var statusText: TextView
    private lateinit var routingBadge: TextView
    private lateinit var masterVolumeSlider: androidx.appcompat.widget.AppCompatSeekBar
    private lateinit var devicesContainer: LinearLayout
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var permissionButton: Button

    private val deviceMixer = DeviceMixer()
    private val bluetoothManager = BluetoothRoutingManager()
    private var audioCaptureManager: AudioCaptureManager? = null
    private var mediaProjection: MediaProjection? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val isRunning = AtomicBoolean(false)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioLevelUpdateRunnable: Runnable? = null

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 200
        private const val REQUEST_PERMISSIONS = 201
        private const val TAG = "BshareTool"
    }

    override fun createView(activity: Activity, parent: ViewGroup): View {
        this.activity = activity
        val inflater = LayoutInflater.from(activity)
        val view = inflater.inflate(R.layout.tool_bshare, parent, false)
        rootLayout = view

        statusText = view.findViewById(R.id.statusText)
        routingBadge = view.findViewById(R.id.routingBadge)
        masterVolumeSlider = view.findViewById(R.id.masterVolumeSlider)
        devicesContainer = view.findViewById(R.id.devicesContainer)
        startButton = view.findViewById(R.id.startButton)
        stopButton = view.findViewById(R.id.stopButton)
        permissionButton = view.findViewById(R.id.permissionButton)

        masterVolumeSlider.progress = 100
        masterVolumeSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    deviceMixer.setMasterVolume(progress / 100f)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        startButton.setOnClickListener { checkPermissionsAndStart() }
        stopButton.setOnClickListener { stopBroadcast() }
        permissionButton.setOnClickListener { requestAllPermissions() }

        bluetoothManager.setDeviceCallback { updateDevicesUI() }
        bluetoothManager.setRoutingCallback { path -> updateRoutingBadge(path) }

        refreshUi()
        return view
    }

    override fun onResume() {
        refreshUi()
        bluetoothManager.onResume(activity!!)
    }

    override fun onPause() {
        bluetoothManager.onPause()
    }

    override fun onDestroy() {
        stopBroadcast()
        bluetoothManager.onDestroy()
        audioLevelUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
        activity = null
        rootLayout = null
    }

    private fun checkPermissionsAndStart() {
        val act = activity ?: return
        val requiredPermissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = requiredPermissions.filter {
            ActivityCompat.checkSelfPermission(act, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(act, missingPermissions.toTypedArray(), REQUEST_PERMISSIONS)
            return
        }

        startMediaProjection()
    }

    private fun startMediaProjection() {
        val act = activity ?: return
        val mediaProjectionManager = act.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        act.startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)
    }

    fun handleMediaProjectionResult(resultCode: Int, data: android.content.Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Toast.makeText(activity, "Media projection permission denied", Toast.LENGTH_SHORT).show()
            return
        }

        val act = activity ?: return
        mediaProjection = (act.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .getMediaProjection(resultCode, data)

        startBroadcastInternal()
    }

    private fun startBroadcastInternal() {
        val act = activity ?: return
        if (isRunning.getAndSet(true)) return

        try {
            // Acquire wake lock for audio processing
            val powerManager = act.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bshare:audio_processing")
            wakeLock?.acquire(10*60*1000L) // 10 minutes max

            // Start audio capture
            audioCaptureManager = AudioCaptureManager(deviceMixer).apply {
                startCapture(mediaProjection!!, activity!!)
            }

            // Start Bluetooth routing
            bluetoothManager.startRouting(act)

            // Setup UI updates
            scheduleAudioLevelUpdates()

            Log.i(TAG, "Bshare broadcast started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start broadcast", e)
            isRunning.set(false)
            wakeLock?.release()
            wakeLock = null
        }

        refreshUi()
    }

    private fun stopBroadcast() {
        isRunning.set(false)
        
        audioCaptureManager?.stopCapture()
        audioCaptureManager = null
        
        bluetoothManager.stopRouting()
        
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

        mediaProjection?.stop()
        mediaProjection = null

        audioLevelUpdateRunnable?.let { mainHandler.removeCallbacks(it) }

        Log.i(TAG, "Bshare broadcast stopped")
        refreshUi()
    }

    private fun scheduleAudioLevelUpdates() {
        audioLevelUpdateRunnable = object : Runnable {
            override fun run() {
                if (isRunning.get()) {
                    updateAudioLevelVisualizer()
                    mainHandler.postDelayed(this, 100)
                }
            }
        }
        mainHandler.post(audioLevelUpdateRunnable!!)
    }

    private fun updateAudioLevelVisualizer() {
        // This would update a visualizer in the UI
        // For now, just keep the loop running
    }

    private fun updateDevicesUI() {
        mainHandler.post {
            val act = activity ?: return@post
            devicesContainer.removeAllViews()

            val devices = bluetoothManager.getConnectedDevices()
            if (devices.isEmpty()) {
                val emptyText = TextView(act).apply {
                    text = act.getString(R.string.no_devices_connected)
                    setPadding(48, 32, 48, 32)
                    textSize = 14f
                    setTextColor(0xFF757575.toInt())
                }
                devicesContainer.addView(emptyText)
                return@post
            }

            devices.forEach { device ->
                val deviceView = createDeviceStrip(act, device)
                devicesContainer.addView(deviceView)
            }
        }
    }

    private fun createDeviceStrip(act: Activity, device: BluetoothDeviceState): View {
        val container = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Device name and status
        val headerLayout = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val nameText = TextView(act).apply {
            text = device.name
            textSize = 16f
            setTextColor(0xFF212121.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val statusBadge = TextView(act).apply {
            text = if (device.isLeAudio) "LE Audio" else "A2DP"
            textSize = 12f
            setPadding(12, 6, 12, 6)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (device.isLeAudio) 0xFF00BCD4.toInt() else 0xFF4CAF50.toInt())
            }
            setTextColor(0xFFFFFFFF.toInt())
        }

        headerLayout.addView(nameText)
        headerLayout.addView(statusBadge)

        // Volume slider
        val volumeSlider = androidx.appcompat.widget.AppCompatSeekBar(act).apply {
            max = 100
            progress = (deviceMixer.getDeviceVolume(device.address) * 100).toInt()
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        deviceMixer.setDeviceVolume(device.address, progress / 100f)
                    }
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }

        container.addView(headerLayout)
        container.addView(volumeSlider)

        return container
    }

    private fun updateRoutingBadge(path: RoutingPath) {
        mainHandler.post {
            val act = activity ?: return@post
            routingBadge.text = when (path) {
                RoutingPath.PATH_A -> act.getString(R.string.routing_path_a)
                RoutingPath.PATH_B -> act.getString(R.string.routing_path_b)
            }
            routingBadge.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 16f
                setColor(when (path) {
                    RoutingPath.PATH_A -> 0xFF4CAF50.toInt()
                    RoutingPath.PATH_B -> 0xFF00BCD4.toInt()
                })
            }
            routingBadge.setTextColor(0xFFFFFFFF.toInt())
            routingBadge.setPadding(16, 8, 16, 8)
        }
    }

    private fun refreshUi() {
        val act = activity ?: return
        val running = isRunning.get()

        startButton.visibility = if (running) View.GONE else View.VISIBLE
        stopButton.visibility = if (running) View.VISIBLE else View.GONE
        permissionButton.visibility = View.GONE

        statusText.text = if (running) {
            val deviceCount = bluetoothManager.getConnectedDevices().size
            act.getString(R.string.broadcasting, deviceCount)
        } else {
            act.getString(R.string.bshare_description)
        }

        updateDevicesUI()
    }

    private fun requestAllPermissions() {
        val act = activity ?: return
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )
        ActivityCompat.requestPermissions(act, permissions, REQUEST_PERMISSIONS)
    }
}

// Data classes for Bluetooth device state
data class BluetoothDeviceState(
    val address: String,
    val name: String,
    val isConnected: Boolean,
    val isLeAudio: Boolean
)

enum class RoutingPath { PATH_A, PATH_B }
