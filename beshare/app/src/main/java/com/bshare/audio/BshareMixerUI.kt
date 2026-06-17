package com.bshare.audio

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow

/**
 * VoiceMeeter-inspired visual mixer UI built with Jetpack Compose.
 * Shows input strip with master volume and output strips for each connected device.
 */
@Composable
fun BshareMixerUI(
    audioCaptureManager: AudioCaptureManager,
    bluetoothRoutingManager: BluetoothRoutingManager,
    deviceMixer: DeviceMixer,
    onRequestPermissions: () -> Unit,
    onMediaProjectionRequest: () -> Unit
) {
    val isCapturing by audioCaptureManager.isCapturing.collectAsState()
    val audioLevel by audioCaptureManager.audioLevel.collectAsState()
    val connectedDevices by bluetoothRoutingManager.connectedDevices.collectAsState()
    val routingPath by bluetoothRoutingManager.currentRoutingPath.collectAsState()
    val deviceVolumes by deviceMixer.deviceVolumes.collectAsState()
    var masterVolume by remember { mutableStateOf(deviceMixer.masterVolume) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with routing path indicator
        RoutingPathBadge(routingPath = routingPath)
        
        // Input Strip - System Audio
        InputStrip(
            audioLevel = audioLevel,
            masterVolume = masterVolume,
            onVolumeChange = { 
                masterVolume = it
                deviceMixer.masterVolume = it
                audioCaptureManager.setMasterVolume(it)
            },
            isCapturing = isCapturing,
            onStartCapture = {
                onRequestPermissions()
                onMediaProjectionRequest()
            }
        )
        
        // Output Strips - Connected Bluetooth Devices
        Text(
            text = "Output Devices",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        if (connectedDevices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No Bluetooth devices connected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(connectedDevices, key = { it.address }) { device ->
                    OutputStrip(
                        device = device,
                        volume = deviceVolumes[device.address] ?: 1.0f,
                        onVolumeChange = { 
                            deviceMixer.setDeviceVolume(device.address, it)
                            audioCaptureManager.setDeviceVolume(device.address, it)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Badge showing current routing path (Path A or Path B)
 */
@Composable
fun RoutingPathBadge(routingPath: RoutingPath) {
    val (backgroundColor, label) = when (routingPath) {
        is RoutingPath.None -> Color.Gray to "No Route"
        is RoutingPath.PathA_DualAudio -> Color.Blue to "Path A: Dual Audio"
        is RoutingPath.PathB_Auracast -> Color.Green to "Path B: Auracast"
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor.copy(alpha = 0.2f)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Bluetooth,
                contentDescription = null,
                tint = backgroundColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = backgroundColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Input strip showing system audio with master volume control and visualizer
 */
@Composable
fun InputStrip(
    audioLevel: Float,
    masterVolume: Float,
    onVolumeChange: (Float) -> Unit,
    isCapturing: Boolean,
    onStartCapture: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "System Audio",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                StatusChip(isActive = isCapturing)
            }
            
            // Audio Level Visualizer
            AudioVisualizer(audioLevel = audioLevel)
            
            // Master Volume Slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Master Volume", style = MaterialTheme.typography.bodySmall)
                    Text("${(masterVolume * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
                Slider(
                    value = masterVolume,
                    onValueChange = onVolumeChange,
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            // Start/Stop Button
            Button(
                onClick = onStartCapture,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isCapturing
            ) {
                Text(if (isCapturing) "Capturing..." else "Start Audio Capture")
            }
        }
    }
}

/**
 * Output strip for individual Bluetooth device with volume control
 */
@Composable
fun OutputStrip(
    device: BluetoothDeviceInfo,
    volume: Float,
    onVolumeChange: (Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Device info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = if (device.isConnected) Color.Blue else Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = device.type.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            
            // Volume control
            Column(
                modifier = Modifier.width(120.dp),
                horizontalAlignment = Alignment.End
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${(volume * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Slider(
                    value = volume,
                    onValueChange = onVolumeChange,
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Real-time audio level visualizer using Canvas
 */
@Composable
fun AudioVisualizer(audioLevel: Float) {
    val animatedLevel by animateFloatAsState(
        targetValue = audioLevel,
        animationSpec = tween(durationMillis = 100, easing = LinearEasing)
    )
    
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(
                Color.Black.copy(alpha = 0.1f),
                RoundedCornerShape(8.dp)
            )
    ) {
        val width = size.width
        val height = size.height
        val barCount = 20
        val barWidth = width / barCount
        val spacing = 2.dp.toPx()
        
        for (i in 0 until barCount) {
            val normalizedPosition = i.toFloat() / barCount
            val barHeight = height * animatedLevel * (1 - kotlin.math.abs(normalizedPosition - 0.5f) * 0.5f)
            
            val color = when {
                animatedLevel > 0.8 -> Color.Red
                animatedLevel > 0.5 -> Color.Yellow
                else -> Color.Green
            }
            
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(
                    i * barWidth + spacing / 2,
                    height - barHeight
                ),
                size = androidx.compose.ui.geometry.Size(barWidth - spacing, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
            )
        }
    }
}

/**
 * Status chip showing capture state
 */
@Composable
fun StatusChip(isActive: Boolean) {
    Surface(
        color = if (isActive) Color.Green.copy(alpha = 0.2f) else Color.Gray.copy(alpha = 0.2f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text = if (isActive) "ACTIVE" : "INACTIVE",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (isActive) Color.Green else Color.Gray,
            fontWeight = FontWeight.Bold
        )
    }
}
