package com.rawcam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Material You theme. A camera surface should stay dark, so the wallpaper-derived
 * [dynamicDarkColorScheme] (Android 12+) supplies the full tonal palette: accents, containers
 * and surfaces all follow the device's system colours.
 */
@Composable
fun RawCamTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    MaterialTheme(
        colorScheme = dynamicDarkColorScheme(context),
        typography = Typography(),
        content = content
    )
}
