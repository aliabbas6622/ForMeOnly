# ForMeOnly

Native Kotlin Android wallpaper rotator for Android 10+.

## Features

- Select an image folder with Android's Storage Access Framework.
- Rotate wallpapers every 1 minute, 5 minutes, 15 minutes, 30 minutes, 1 hour, or 24 hours.
- Sequential and shuffle modes.
- Foreground service with a persistent notification.
- SharedPreferences-backed settings.
- Larger next-wallpaper preview plus a full preview dialog.
- Pixel 7a sizing modes: Auto fit, Fill screen, Fit full image, and Stretch.

## Battery Notes

The service sleeps until the next scheduled wallpaper change instead of polling. Folder images are cached after the initial SAF scan and reused by the service, so normal operation performs no repeated folder traversal. Failed images are skipped after two failed attempts. Wallpaper images are sampled and drawn into a 1080 x 2400 bitmap before being applied, matching the Pixel 7a display.

For the 1-minute interval, the app must wake once per minute because the user-selected behavior requires it. Longer intervals wake far less often.

## Build

Open this folder in Android Studio and run the `app` configuration, or build from PowerShell with a configured Android SDK:

```powershell
.\gradlew assembleDebug
```

In this workspace, the verified debug APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```
