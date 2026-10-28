package com.metalsensor.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Brand fallback palette (used when dynamic colors are unavailable, below Android 12)
private val Blue700 = Color(0xFF1565C0)
private val Blue200 = Color(0xFF90CAF9)
private val Teal700 = Color(0xFF00897B)
private val Teal200 = Color(0xFF80CBC4)

// ---- Fixed semantic colors for sensor states (theme independent) ----
val StateCalColor = Color(0xFF9E9E9E)       // gray   -> CAL
val StateClearColor = Color(0xFF43A047)     // green  -> CLEAR
val StateDetectingColor = Color(0xFFF9A825) // amber  -> DETECTING
val StateOccupiedColor = Color(0xFFD32F2F)  // red    -> OCCUPIED
val WarnOrange = Color(0xFFF57C00)          // validation warning

private val LightColors = lightColorScheme(
    primary = Blue700,
    onPrimary = Color.White,
    secondary = Teal700,
    onSecondary = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Blue200,
    onPrimary = Color(0xFF0D2C6B),
    secondary = Teal200,
    onSecondary = Color(0xFF00332B),
)

@Composable
fun MetalSensorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
