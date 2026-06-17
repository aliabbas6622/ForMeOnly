# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /sdk/tools/proguard/proguard-android.txt

# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }

# Keep data classes
-keepclassmembers class com.bshare.audio.** {
    public <fields>;
    public <methods>;
}

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Bluetooth classes
-keep class android.bluetooth.** { *; }
-keep class android.media.** { *; }
