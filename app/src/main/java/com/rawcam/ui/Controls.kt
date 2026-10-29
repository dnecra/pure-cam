package com.rawcam.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rawcam.R
import com.rawcam.core.Facing
import com.rawcam.core.LensProfile
import com.rawcam.core.OrientationMath
import com.rawcam.core.VideoConfig
import com.rawcam.presentation.CameraUiState
import com.rawcam.presentation.CaptureMode
import com.rawcam.presentation.TimerMode
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TopControls(
    state: CameraUiState,
    onFlash: () -> Unit,
    onTimer: () -> Unit,
    onGrid: () -> Unit,
    onManual: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lens = state.lens
    val flashOn = state.cameraState?.flash == true
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            )
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ControlButton(
            icon = if (flashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off,
            description = "Flash",
            active = flashOn,
            enabled = lens?.flashAvailable == true,
            onClick = onFlash
        )
        TimerControl(timer = state.timer, onClick = onTimer)
        ControlButton(
            icon = R.drawable.ic_grid,
            description = "Grid",
            active = state.grid,
            onClick = onGrid
        )
        ControlButton(
            icon = R.drawable.ic_tune,
            description = "Manual controls",
            active = state.manualControlsActive,
            onClick = onManual
        )
    }
}

@Composable
private fun ControlButton(
    @DrawableRes icon: Int,
    description: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val tint = when {
        !enabled -> Color.White.copy(alpha = 0.3f)
        active -> accent
        else -> Color.White.copy(alpha = 0.92f)
    }
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.28f))
            .border(
                width = 1.dp,
                color = if (active) accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.12f),
                shape = CircleShape
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun TimerControl(timer: TimerMode, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val label = when (timer) {
        TimerMode.OFF -> null
        TimerMode.THREE -> "3s"
        TimerMode.TEN -> "10s"
    }
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.28f))
            .border(
                width = 1.dp,
                color = if (label == null) Color.White.copy(alpha = 0.12f) else accent.copy(alpha = 0.6f),
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (label == null) {
            Icon(
                painter = painterResource(R.drawable.ic_timer),
                contentDescription = "Timer",
                tint = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.size(22.dp)
            )
        } else {
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = accent)
        }
    }
}

@Composable
fun BottomControls(
    state: CameraUiState,
    onShutter: () -> Unit,
    onFlip: () -> Unit,
    onSelectLens: (LensProfile) -> Unit,
    onOpenPhoto: (android.net.Uri) -> Unit,
    onSelectMode: (CaptureMode) -> Unit,
    onVideoSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canFlip = state.catalog.lenses(Facing.BACK).isNotEmpty() &&
        state.catalog.lenses(Facing.FRONT).isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
            )
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (state.zoom > 1.01f) {
            ZoomBadge(state.zoom)
            Spacer(Modifier.height(10.dp))
        }
        if (state.lenses.size > 1) {
            LensPills(
                lenses = state.lenses,
                selectedId = state.lens?.id,
                enabled = !state.recording && !state.recordingStarting,
                onSelect = onSelectLens
            )
            Spacer(Modifier.height(16.dp))
        }
        if (state.videoMode) {
            state.videoConfig?.let { config ->
                VideoQualityPill(
                    config = config,
                    enabled = !state.recording && !state.recordingStarting,
                    onClick = onVideoSettings
                )
                Spacer(Modifier.height(12.dp))
            }
        }
        if (state.supportsVideo) {
            ModeSwitch(
                mode = state.captureMode,
                enabled = !state.recording && !state.recordingStarting && !state.capturing,
                onSelect = onSelectMode
            )
            Spacer(Modifier.height(14.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                PhotoThumbnail(
                    uri = state.lastPhoto,
                    isVideo = state.lastMediaIsVideo,
                    onClick = onOpenPhoto
                )
            }
            ShutterButton(
                capturing = state.capturing,
                recording = state.recording || state.recordingStarting,
                videoMode = state.videoMode,
                onClick = onShutter
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                FlipButton(
                    enabled = canFlip && !state.recording,
                    rotationDegrees = state.deviceRotation,
                    onClick = onFlip
                )
            }
        }
    }
}

@Composable
private fun ModeSwitch(
    mode: CaptureMode,
    enabled: Boolean,
    onSelect: (CaptureMode) -> Unit
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.32f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ModeTab(
            label = stringResource(R.string.mode_photo),
            selected = mode == CaptureMode.PHOTO,
            enabled = enabled
        ) { onSelect(CaptureMode.PHOTO) }
        ModeTab(
            label = stringResource(R.string.mode_video),
            selected = mode == CaptureMode.VIDEO,
            enabled = enabled
        ) { onSelect(CaptureMode.VIDEO) }
    }
}

@Composable
private fun ModeTab(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) accent else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = when {
                selected -> MaterialTheme.colorScheme.onPrimary
                !enabled -> Color.White.copy(alpha = 0.3f)
                else -> Color.White.copy(alpha = 0.85f)
            }
        )
    }
}

@Composable
private fun VideoQualityPill(config: VideoConfig, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.34f),
        modifier = Modifier
            .clip(CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            text = "${config.quality.label}  ·  ${stringResource(R.string.frame_rate_value, config.fps)}",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun ZoomBadge(zoom: Float) {
    Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.34f)) {
        Text(
            text = formatZoom(zoom),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.92f)
        )
    }
}

private fun formatZoom(zoom: Float): String {
    val rounded = Math.round(zoom * 10f) / 10f
    return if (rounded % 1f == 0f) {
        "${rounded.toInt()}\u00d7"
    } else {
        String.format(Locale.US, "%.1f\u00d7", rounded)
    }
}

@Composable
private fun LensPills(
    lenses: List<LensProfile>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (LensProfile) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        lenses.forEach { lens ->
            val selected = lens.id == selectedId
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else Color.Black.copy(alpha = 0.32f)
                    )
                    .border(
                        width = 1.dp,
                        color = if (selected) Color.Transparent else Color.White.copy(alpha = 0.18f),
                        shape = CircleShape
                    )
                    .clickable(enabled = enabled) { onSelect(lens) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Text(
                    text = lens.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        selected -> MaterialTheme.colorScheme.onPrimary
                        !enabled -> Color.White.copy(alpha = 0.5f)
                        else -> Color.White.copy(alpha = 0.9f)
                    }
                )
            }
        }
    }
}

@Composable
private fun ShutterButton(
    capturing: Boolean,
    recording: Boolean,
    videoMode: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && !capturing) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "shutterScale"
    )
    val accent = when {
        videoMode -> Color(0xFFE53935)
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier
            .size(82.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .border(4.dp, Color.White.copy(alpha = 0.92f), CircleShape)
            .padding(8.dp)
            .clip(CircleShape)
            .background(accent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !capturing,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            videoMode && recording -> Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White)
            )
            capturing -> CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.5.dp
            )
        }
    }
}

@Composable
private fun FlipButton(enabled: Boolean, rotationDegrees: Int, onClick: () -> Unit) {
    val angle by animateFloatAsState(
        targetValue = OrientationMath.iconRotation(rotationDegrees).toFloat(),
        animationSpec = tween(durationMillis = 220),
        label = "flipRotation"
    )
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.28f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_flip),
            contentDescription = "Flip camera",
            tint = if (enabled) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.3f),
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer { rotationZ = angle }
        )
    }
}

@Composable
private fun PhotoThumbnail(
    uri: android.net.Uri?,
    isVideo: Boolean,
    onClick: (android.net.Uri) -> Unit
) {
    val context = LocalContext.current
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) { loadThumbnail(context, uri, 120) }
    }
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.32f))
            .border(1.dp, Color.White.copy(alpha = 0.28f), shape)
            .clickable(enabled = uri != null) { uri?.let(onClick) },
        contentAlignment = Alignment.Center
    ) {
        val bitmap = thumbnail
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.gallery),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_photo),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(22.dp)
            )
        }
        if (isVideo && bitmap != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(7.dp)) {
                    val path = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width, size.height / 2f)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(path, Color.White)
                }
            }
        }
    }
}

/** Red dot + elapsed time, shown while a clip is being recorded. */
@Composable
fun RecordingPill(elapsedMs: Long, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.45f), modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Canvas(modifier = Modifier.size(9.dp)) {
                drawCircle(Color(0xFFFF5252), radius = size.minDimension / 2f, center = Offset(size.width / 2f, size.height / 2f))
            }
            Text(
                text = com.rawcam.core.VideoMath.formatDuration(elapsedMs),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White
            )
        }
    }
}
