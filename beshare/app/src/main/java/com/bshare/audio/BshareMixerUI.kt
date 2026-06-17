package com.bshare.audio

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bshare.audio.ui.theme.BshareColors
import com.bshare.audio.ui.theme.isSystemInDarkTheme
import kotlinx.coroutines.flow.StateFlow

/**
 * VoiceMeeter-inspired visual mixer UI built with Jetpack Compose.
 * Shows input strip with master volume and output strips for each connected device.
 * Fully optimized for both light and dark modes with proper contrast ratios.
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
    
    val colorScheme = MaterialTheme.colorScheme
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
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
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = colorScheme.onBackground
        )
        
        if (connectedDevices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bluetooth,
                            contentDescription = null,
                            tint = colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No Bluetooth devices connected",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Text(
                            text = "Connect earbuds to start broadcasting",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
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
 * Uses theme-aware colors with proper contrast for dark mode
 */
@Composable
fun RoutingPathBadge(routingPath: RoutingPath) {
    val colorScheme = MaterialTheme.colorScheme
    
    val (backgroundColor, labelColor, label) = when (routingPath) {
        is RoutingPath.None -> 
            colorScheme.outline.copy(alpha = 0.3f) to 
            colorScheme.onSurfaceVariant.copy(alpha = 0.7f) to "No Route"
        is RoutingPath.PathA_DualAudio -> 
            BshareColors.pathDual.copy(alpha = 0.2f) to 
            BshareColors.pathDual to "Path A: Dual Audio"
        is RoutingPath.PathB_Auracast -> 
            BshareColors.pathAuracast.copy(alpha = 0.2f) to 
            BshareColors.pathAuracast to "Path B: Auracast"
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(12.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.verticalGradient(
                colors = listOf(
                    labelColor.copy(alpha = 0.3f),
                    Color.Transparent
                )
            )
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Bluetooth,
                contentDescription = null,
                tint = labelColor,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = labelColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Input strip showing system audio with master volume control and visualizer
 * Optimized for dark mode with proper contrast and visual hierarchy
 */
@Composable
fun InputStrip(
    audioLevel: Float,
    masterVolume: Float,
    onVolumeChange: (Float) -> Unit,
    isCapturing: Boolean,
    onStartCapture: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
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
                        tint = colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "System Audio",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface
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
                    Text(
                        text = "Master Volume",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${(masterVolume * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = masterVolume,
                    onValueChange = onVolumeChange,
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = colorScheme.primary,
                        activeTrackColor = colorScheme.primary,
                        inactiveTrackColor = colorScheme.outline.copy(alpha = 0.3f)
                    )
                )
            }
            
            // Start/Stop Button
            Button(
                onClick = onStartCapture,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = !isCapturing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isCapturing) 
                        colorScheme.primary.copy(alpha = 0.3f) 
                    else colorScheme.primary,
                    contentColor = if (isCapturing) 
                        colorScheme.onPrimary.copy(alpha = 0.7f) 
                    else colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = if (isCapturing) Icons.Default.Mic else Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isCapturing) "Capturing..." else "Start Audio Capture",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Output strip for individual Bluetooth device with volume control
 * Optimized for dark mode with proper contrast and visual hierarchy
 */
@Composable
fun OutputStrip(
    device: BluetoothDeviceInfo,
    volume: Float,
    onVolumeChange: (Float) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (device.isConnected) 
                colorScheme.surfaceVariant.copy(alpha = 0.4f)
            else colorScheme.surfaceVariant.copy(alpha = 0.2f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp),
        border = if (device.isConnected) {
            androidx.compose.foundation.BorderStroke(
                1.dp, 
                colorScheme.primary.copy(alpha = 0.2f)
            )
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Device info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = if (device.isConnected) colorScheme.primary else colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (device.isConnected) 
                            colorScheme.onSurface 
                        else colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = when (device.type) {
                            BluetoothDeviceType.A2DP -> BshareColors.pathDual.copy(alpha = 0.2f)
                            BluetoothDeviceType.LE_AUDIO -> BshareColors.pathAuracast.copy(alpha = 0.2f)
                            else -> colorScheme.outline.copy(alpha = 0.2f)
                        },
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = device.type.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = when (device.type) {
                                BluetoothDeviceType.A2DP -> BshareColors.pathDual
                                BluetoothDeviceType.LE_AUDIO -> BshareColors.pathAuracast
                                else -> colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    if (!device.isConnected) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Disconnected",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.error.copy(alpha = 0.8f)
                        )
                    }
                }
            }
            
            // Volume control
            Column(
                modifier = Modifier.width(130.dp),
                horizontalAlignment = Alignment.End
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = if (volume > 0.5f) colorScheme.primary else colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${(volume * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Slider(
                    value = volume,
                    onValueChange = onVolumeChange,
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = colorScheme.primary,
                        activeTrackColor = colorScheme.primary,
                        inactiveTrackColor = colorScheme.outline.copy(alpha = 0.3f)
                    )
                )
            }
        }
    }
}

/**
 * Real-time audio level visualizer using Canvas
 * Optimized for dark mode with gradient colors and smooth animations
 */
@Composable
fun AudioVisualizer(audioLevel: Float) {
    val colorScheme = MaterialTheme.colorScheme
    
    val animatedLevel by animateFloatAsState(
        targetValue = audioLevel,
        animationSpec = tween(durationMillis = 100, easing = LinearEasing)
    )
    
    val visualizerBgColor = BshareColors.visualizerBg
    
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp)
            .background(
                visualizerBgColor.copy(alpha = if (colorScheme.dark) 0.5f else 0.3f),
                RoundedCornerShape(12.dp)
            )
            .border(
                1.dp,
                colorScheme.outline.copy(alpha = 0.2f),
                RoundedCornerShape(12.dp)
            )
    ) {
        val width = size.width
        val height = size.height
        val barCount = 24
        val barWidth = width / barCount
        val spacing = 3.dp.toPx()
        
        // Create gradient based on audio level
        for (i in 0 until barCount) {
            val normalizedPosition = i.toFloat() / barCount
            // Create a wave-like effect with varying heights
            val waveFactor = kotlin.math.sin((i / barCount.toFloat()) * kotlin.math.PI).toFloat()
            val barHeight = height * animatedLevel * waveFactor * 0.9f
            
            // Dynamic color based on level position
            val colorProgress = i.toFloat() / barCount
            val barColor = when {
                animatedLevel > 0.8 -> {
                    // High level - red to orange gradient
                    Color.Red.copy(alpha = 0.6f + (1 - colorProgress) * 0.4f)
                }
                animatedLevel > 0.5 -> {
                    // Medium level - yellow to orange gradient
                    BshareColors.audioMedium.copy(alpha = 0.6f + (1 - colorProgress) * 0.4f)
                }
                else -> {
                    // Low level - green to cyan gradient
                    BshareColors.audioLow.copy(alpha = 0.6f + (1 - colorProgress) * 0.4f)
                }
            }
            
            // Draw rounded bar
            drawRoundRect(
                color = barColor,
                topLeft = androidx.compose.ui.geometry.Offset(
                    i * barWidth + spacing / 2,
                    height - barHeight
                ),
                size = androidx.compose.ui.geometry.Size(barWidth - spacing, maxOf(barHeight, 2.dp.toPx())),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
            )
        }
        
        // Draw center line indicator
        drawLine(
            color = colorScheme.outline.copy(alpha = 0.3f),
            start = androidx.compose.ui.geometry.Offset(0f, height / 2),
            end = androidx.compose.ui.geometry.Offset(width, height / 2),
            strokeWidth = 1.dp.toPx()
        )
    }
}

/**
 * Status chip showing capture state
 * Uses theme-aware colors with proper contrast for dark mode
 */
@Composable
fun StatusChip(isActive: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    
    Surface(
        color = if (isActive) 
            BshareColors.success.copy(alpha = 0.15f) 
        else colorScheme.outline.copy(alpha = 0.15f),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isActive) 
                BshareColors.success.copy(alpha = 0.3f) 
            else colorScheme.outline.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status indicator dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        if (isActive) BshareColors.success else colorScheme.outline,
                        RoundedCornerShape(4.dp)
                    )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isActive) "ACTIVE" else "INACTIVE",
                style = MaterialTheme.typography.labelMedium,
                color = if (isActive) BshareColors.success else colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
