package com.bshare.audio.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Light Color Scheme - Clean, professional VoiceMeeter-inspired design
private val BshareLightColorScheme = lightColorScheme(
    primary = BsharePrimary,
    onPrimary = BshareOnPrimary,
    primaryContainer = BsharePrimary.copy(alpha = 0.1f),
    onPrimaryContainer = BsharePrimaryVariant,
    
    secondary = BshareSecondary,
    onSecondary = BshareOnSecondary,
    secondaryContainer = BshareSecondary.copy(alpha = 0.1f),
    onSecondaryContainer = BshareSecondaryVariant,
    
    background = BshareBackgroundLight,
    onBackground = BshareOnBackgroundLight,
    
    surface = BshareSurfaceLight,
    onSurface = BshareOnSurfaceLight,
    surfaceVariant = BshareCardLight,
    onSurfaceVariant = BshareOnSurfaceLight,
    
    error = BshareError,
    onError = Color.White,
    errorContainer = BshareErrorLight,
    onErrorContainer = BshareError,
    
    outline = BshareCardStrokeLight
)

// Dark Color Scheme - OLED-optimized for Pixel 7a with proper contrast
private val BshareDarkColorScheme = darkColorScheme(
    primary = BsharePrimary.copy(alpha = 0.9f),
    onPrimary = BshareOnPrimary,
    primaryContainer = BsharePrimary.copy(alpha = 0.2f),
    onPrimaryContainer = BsharePrimary.copy(alpha = 0.9f),
    
    secondary = BshareSecondary.copy(alpha = 0.9f),
    onSecondary = BshareOnSecondary,
    secondaryContainer = BshareSecondary.copy(alpha = 0.2f),
    onSecondaryContainer = BshareSecondary.copy(alpha = 0.9f),
    
    background = BshareBackgroundDark,
    onBackground = BshareOnBackgroundDark,
    
    surface = BshareSurfaceDark,
    onSurface = BshareOnSurfaceDark,
    surfaceVariant = BshareCardDark,
    onSurfaceVariant = BshareOnSurfaceDark,
    
    error = BshareErrorLight,
    onError = Color.White,
    errorContainer = BshareError.copy(alpha = 0.3f),
    onErrorContainer = BshareErrorLight,
    
    outline = BshareCardStrokeDark
)

@Composable
fun BshareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Disable dynamic color for consistent branding
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> BshareDarkColorScheme
        else -> BshareLightColorScheme
    }
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Set status bar color to match theme
            window.statusBarColor = colorScheme.background.toArgb()
            // Enable edge-to-edge display
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
            }
        }
    }
    
    MaterialTheme(
        colorScheme = colorScheme,
        typography = BshareTypography,
        content = content
    )
}

// Helper to get current color scheme values
object BshareColors {
    val success: Color
        @Composable
        get() = if (isSystemInDarkTheme()) BshareSuccessLight else BshareSuccess
    
    val warning: Color
        @Composable
        get() = if (isSystemInDarkTheme()) BshareWarningLight else BshareWarning
    
    val error: Color
        @Composable
        get() = if (isSystemInDarkTheme()) BshareErrorLight else BshareError
    
    val audioLow: Color
        @Composable
        get() = BshareAudioLow
    
    val audioMedium: Color
        @Composable
        get() = BshareAudioMedium
    
    val audioHigh: Color
        @Composable
        get() = BshareAudioHigh
    
    val pathNone: Color
        @Composable
        get() = BsharePathANone
    
    val pathDual: Color
        @Composable
        get() = BsharePathADual
    
    val pathAuracast: Color
        @Composable
        get() = BsharePathBAuracast
    
    val sliderActive: Color
        @Composable
        get() = if (isSystemInDarkTheme()) BshareSliderActiveDark else BshareSliderActiveLight
    
    val sliderInactive: Color
        @Composable
        get() = if (isSystemInDarkTheme()) BshareSliderInactiveDark else BshareSliderInactiveLight
    
    val visualizerBg: Color
        @Composable
        get() = if (isSystemInDarkTheme()) BshareVisualizerBgDark else BshareVisualizerBgLight
}
