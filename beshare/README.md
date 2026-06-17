# Bshare - Bluetooth Audio Broadcasting App

## Project Overview

**Bshare** is a complete Android application that captures system-wide audio (Spotify, YouTube, etc.) and broadcasts it to multiple Bluetooth earbuds simultaneously, without requiring listeners to install any app. The app functions like a mobile "VoiceMeeter," featuring a visual mixer, per-device volume control, and a hybrid Bluetooth routing engine.

## Core Architecture

### Hybrid Routing Logic (Path A/B)

The app dynamically routes audio based on the number of connected Bluetooth audio devices:

- **Path A (≤ 2 Devices)**: Uses native Android A2DP profile with dual-audio support
- **Path B (> 2 Devices)**: Activates Auracast/LE Audio Broadcast using LC3 codec (Android 13+)

### Key Components

1. **BluetoothRoutingManager.kt**: State machine managing Path A/B transitions
2. **DeviceMixer.kt**: Virtual audio mixing matrix with per-device volume control
3. **AudioCaptureManager.kt**: MediaProjection-based system audio capture
4. **BatteryOptimizedForegroundService.kt**: Battery-optimized foreground service
5. **BshareMixerUI.kt**: Jetpack Compose VoiceMeeter-style visual mixer

## Pixel 7a Optimizations

- **Low-Latency Audio**: 48000 Hz sample rate, 16-bit PCM encoding
- **Dynamic Buffer Sizing**: Calculates minimum buffer size using `AudioTrack.getMinBufferSize()`
- **Hardware Offloading**: Routes to AudioTrack with USAGE_MEDIA for Tensor G2 DSP acceleration
- **Power Management**: 
  - PARTIAL_WAKE_LOCK only on audio processing thread
  - SCAN_MODE_LOW_POWER during device discovery
  - Foreground service with media playback type

## Project Structure

```
beshare/
├── app/
│   ├── src/main/
│   │   ├── java/com/bshare/audio/
│   │   │   ├── MainActivity.kt
│   │   │   ├── BluetoothRoutingManager.kt
│   │   │   ├── DeviceMixer.kt
│   │   │   ├── AudioCaptureManager.kt
│   │   │   ├── BshareMixerUI.kt
│   │   │   └── service/
│   │   │       └── BatteryOptimizedForegroundService.kt
│   │   ├── res/
│   │   │   ├── drawable/
│   │   │   ├── mipmap-anydpi-v26/
│   │   │   └── values/
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## Required Permissions

- BLUETOOTH, BLUETOOTH_ADMIN, BLUETOOTH_CONNECT, BLUETOOTH_SCAN
- RECORD_AUDIO (for AudioPlaybackCapture)
- FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK
- WAKE_LOCK
- POST_NOTIFICATIONS (Android 13+)

## Build Requirements

- **Minimum SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)
- **Compile SDK**: 34
- **Kotlin**: 1.9.20
- **Jetpack Compose**: BOM 2023.10.01

## Features

### Audio Engine
- System-wide audio capture via MediaProjection API
- Real-time PCM processing with low-latency configuration
- LoudnessEnhancerEffect for volume normalization
- Per-device independent volume control (0-100%)
- Master volume control

### UI/UX
- VoiceMeeter-inspired visual mixer
- Real-time audio level visualizer
- Color-coded routing path indicator (Path A/B)
- Dynamic device list with connection status
- Material Design 3 components

### Bluetooth Management
- Automatic device count monitoring
- Seamless Path A ↔ Path B transitions
- LE Audio scanning with low-power mode
- A2DP and HEADSET profile support

## Usage

1. Launch the app and grant required permissions
2. Connect Bluetooth earbuds through system settings
3. Tap "Start Audio Capture" and accept MediaProjection permission
4. Adjust master volume and per-device volumes using sliders
5. Monitor routing path indicator for current mode

## Notes

- For Path B (Auracast), Android 13+ and LE Audio-compatible earbuds are required
- Listeners can tune in via their native Bluetooth settings (no app installation needed)
- The app runs as a foreground service with persistent notification

## License

This project is provided as-is for educational and development purposes.
