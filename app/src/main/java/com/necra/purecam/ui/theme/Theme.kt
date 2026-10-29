package com.necra.purecam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Material You theme. A camera surface should stay dark, so the wallpaper-derived
 * [dynamicDarkColorScheme] (Android 12+) supplies the full tonal palette: accents, containers
 * and surfaces all follow the device's system colours.
 */
@Composable
fun PureCamTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // Resolving the palette is a resource lookup; keep the instance stable so it never
    // invalidates every MaterialTheme consumer on recomposition.
    val colorScheme = remember(context) { dynamicDarkColorScheme(context) }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
