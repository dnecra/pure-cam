package com.necra.purecam.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.necra.purecam.R
import com.necra.purecam.core.Facing
import com.necra.purecam.core.LensProfile
import com.necra.purecam.core.WhiteBalanceMode
import com.necra.purecam.presentation.CameraUiState
import com.necra.purecam.presentation.CaptureMode
import com.necra.purecam.presentation.TimerMode
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// Google Camera (Pixel) UI colours, taken from new-activity_camera.xml.
private val CamBg = Color(0xFF000000)
private val CamScrim = Color(0x99202124)
private val CamBarBtn = Color(0xFF2B2B2D)
private val CamAccent = Color(0xFFA8C7FA)
private val CamOnAccent = Color(0xFF041E49)
private val CamWhite = Color(0xFFFFFFFF)
private val CamMuted = Color(0xFF8E8E93)

/**
 * Pixel-style camera layout: rounded viewfinder card, floating scrim controls and a bottom bar.
 * Everything is driven by [state] so the screen stays stateless.
 *
 * The preview frame fits the camera stream's native aspect ratio.
 *
 * @param content the live preview, drawn inside a frame matching the camera stream.
 */
@Composable
fun GoogleCameraUI(
    state: CameraUiState,
    modifier: Modifier = Modifier,
    onFlash: () -> Unit = {},
    onTimer: () -> Unit = {},
    onGrid: () -> Unit = {},
    onShutter: () -> Unit = {},
    onFlip: () -> Unit = {},
    onOpenPhoto: (Uri) -> Unit = {},
    onSelectMode: (CaptureMode) -> Unit = {},
    onSelectLens: (LensProfile) -> Unit = {},
    onWhiteBalance: () -> Unit = {},
    onSettings: () -> Unit = {},
    content: @Composable () -> Unit
) {
    val lens = state.lens
    val lenses = state.lenses
    val flashOn = state.cameraState?.flash == true
    val busy = state.capturing || state.recording || state.recordingStarting
    val canFlip = lens != null && state.catalog.lenses(
        if (lens.facing == Facing.BACK) Facing.FRONT else Facing.BACK
    ).isNotEmpty()
    val whiteBalanceActive = state.cameraState?.wb !is WhiteBalanceMode.Auto
    val frameAspect = state.plan?.displayAspect ?: (4f / 3f)

    // A WB change is announced for two seconds, then the pill falls back to the live spec.
    val wbSpec = whiteBalanceLabel(state.cameraState?.wb)
    var transientSpec by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(wbSpec) {
        transientSpec = wbSpec
        if (wbSpec != null) {
            delay(2_000)
            transientSpec = null
        }
    }
    val spec = transientSpec ?: defaultSpec(state)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CamBg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // Viewfinder card.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 88.dp)
        ) {
            // Plain black card. The preview is clipped by its native-ratio window, so no extra
            // rounded Surface layer wraps the whole viewfinder (one less layer per frame).
            Box(modifier = Modifier.fillMaxSize().background(CamBg)) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Native-ratio preview, fitted inside the viewfinder space.
                    ViewfinderFrame(
                        aspect = frameAspect,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        content()
                        GridOverlay(visible = state.grid)
                        CountdownOverlay(countdown = state.countdown)
                    }

                    // Scrims keep the floating controls readable over bright scenes.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(120.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent)
                                )
                            )
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(220.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                                )
                            )
                    )

                    // Top controls: flash + timer left, live spec in the middle, grid right.
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircleIconButton(
                                icon = if (flashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off,
                                description = stringResource(R.string.flash),
                                active = flashOn,
                                enabled = lens?.flashAvailable == true,
                                onClick = onFlash
                            )
                            TimerButton(timer = state.timer, onClick = onTimer)
                        }
                        SpecPill(text = spec, onClick = onSettings)
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                        ) {
                            CircleIconButton(
                                icon = R.drawable.ic_grid,
                                description = stringResource(R.string.grid),
                                active = state.grid,
                                onClick = onGrid
                            )
                        }
                    }

                    // Recording indicator.
                    AnimatedVisibility(
                        visible = state.recording,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp),
                        enter = fadeIn(spring(stiffness = 420f)) +
                            scaleIn(spring(dampingRatio = 0.72f)) +
                            slideInVertically(spring(stiffness = 420f)) { -it / 3 },
                        exit = fadeOut() + scaleOut(spring(dampingRatio = 0.8f)) + slideOutVertically { -it / 4 }
                    ) {
                        RecordingPill()
                    }

                    // Bottom stack inside the card: lens menu + shutter row.
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AnimatedVisibility(
                            visible = lenses.size > 1,
                            enter = fadeIn(spring(stiffness = 420f)) +
                                slideInVertically(spring(stiffness = 420f)) { it / 2 },
                            exit = fadeOut() + slideOutVertically { it / 2 }
                        ) {
                            LensSegmented(
                                lenses = lenses,
                                selectedId = lens?.id,
                                enabled = !busy,
                                onSelect = onSelectLens
                            )
                            Spacer(Modifier.height(10.dp))
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
            }
        }

        // Bottom bar, outside the viewfinder card.
        BottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp),
            mode = state.captureMode,
            modeEnabled = state.supportsVideo && !busy,
            whiteBalanceActive = whiteBalanceActive,
            onSelectMode = onSelectMode,
            onSettings = onSettings,
            onWhiteBalance = onWhiteBalance
        )
    }
}

@Composable
private fun CircleIconButton(
    icon: Int,
    description: String,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true
) {
    val tint by animateColorAsState(
        targetValue = when {
            !enabled -> CamWhite.copy(alpha = 0.35f)
            active -> CamOnAccent
            else -> CamWhite
        },
        animationSpec = tween(160),
        label = "circleIconTint"
    )
    val background by animateColorAsState(
        targetValue = if (active) CamAccent else CamScrim,
        animationSpec = tween(160),
        label = "circleIconBg"
    )
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(background)
            .hapticClickable(enabled = enabled, onClick = onClick),
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
private fun TimerButton(timer: TimerMode, onClick: () -> Unit) {
    val label = when (timer) {
        TimerMode.OFF -> null
        TimerMode.THREE -> stringResource(R.string.timer_3s)
        TimerMode.TEN -> stringResource(R.string.timer_10s)
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (label == null) CamScrim else CamAccent)
            .hapticClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (label == null) {
            Icon(
                painter = painterResource(R.drawable.ic_timer),
                contentDescription = stringResource(R.string.timer),
                tint = CamWhite,
                modifier = Modifier.size(22.dp)
            )
        } else {
            Text(
                text = label,
                color = CamOnAccent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** White-balance label shown briefly after the preset changes, or null when WB is automatic. */
private fun whiteBalanceLabel(wb: WhiteBalanceMode?): String? = when (wb) {
    is WhiteBalanceMode.Preset -> wb.preset.label
    is WhiteBalanceMode.Kelvin -> "${wb.kelvin} K"
    else -> null
}

/** The steady-state status: the photo crop size or the video quality/frame rate. */
private fun defaultSpec(state: CameraUiState): String? = if (state.videoMode) {
    state.videoConfig?.let { "${it.quality.label} · ${it.fps} fps" }
} else {
    state.plan?.let { plan ->
        val rotated = state.lens?.sensorOrientation?.rem(180) == 90
        val width = if (rotated) plan.jpegSize.height else plan.jpegSize.width
        val height = if (rotated) plan.jpegSize.width else plan.jpegSize.height
        "JPG · ${width}×${height}"
    }
}
/**
 * Fixed-width status pill. The width never changes, so the top row stays put while the label
 * cross-fades between the WB preset and the live spec.
 */
@Composable
private fun SpecPill(text: String?, onClick: () -> Unit) {
    AnimatedContent(
        targetState = text,
        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(90)) },
        label = "specPill"
    ) { value ->
        Box(
            modifier = Modifier
                .width(124.dp)
                .height(34.dp)
                .clip(CircleShape)
                .background(if (value != null) CamScrim else Color.Transparent)
                .hapticClickable(enabled = value != null, onClick = onClick)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            if (value != null) {
                Text(
                    text = value,
                    color = CamWhite.copy(alpha = 0.92f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Centred preview window that preserves the camera stream's native ratio.
 */
@Composable
private fun ViewfinderFrame(
    aspect: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .layout { measurable, constraints ->
                val ratio = aspect.coerceIn(0.2f, 5f)
                val maxW = constraints.maxWidth
                val maxH = constraints.maxHeight
                val byHeight = (maxH * ratio).roundToInt()
                val width = if (byHeight in 1..maxW) byHeight else maxW
                val height = (width / ratio).roundToInt().coerceIn(1, maxH)
                val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(width, height))
                layout(width, height) { placeable.place(0, 0) }
            }
            .clip(shape)
            .border(1.dp, CamWhite.copy(alpha = 0.16f), shape)
    ) {
        content()
    }
}

@Composable
private fun LensSegmented(
    lenses: List<LensProfile>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (LensProfile) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = CamBarBtn
    ) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            lenses.forEach { lens ->
                val selected = lens.id == selectedId
                val background by animateColorAsState(
                    targetValue = if (selected) CamAccent else Color.Transparent,
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 620f),
                    label = "lensBg"
                )
                val textColor by animateColorAsState(
                    targetValue = when {
                        selected -> CamOnAccent
                        !enabled -> CamMuted
                        else -> CamWhite
                    },
                    animationSpec = spring(dampingRatio = 0.82f, stiffness = 700f),
                    label = "lensText"
                )
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .widthIn(min = 52.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(background)
                        .hapticClickable(enabled = enabled) { onSelect(lens) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = lens.label,
                        color = textColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomBar(
    modifier: Modifier = Modifier,
    mode: CaptureMode,
    modeEnabled: Boolean,
    whiteBalanceActive: Boolean,
    onSelectMode: (CaptureMode) -> Unit,
    onSettings: () -> Unit,
    onWhiteBalance: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Equal side weights keep the mode switch exactly centred under the shutter.
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BarButton(
                icon = R.drawable.ic_settings,
                description = stringResource(R.string.settings),
                onClick = onSettings
            )
        }

        SegmentedControl(
            mode = mode,
            enabled = modeEnabled,
            onSelect = onSelectMode
        )

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            BarButton(
                icon = R.drawable.ic_white_balance,
                description = stringResource(R.string.wb),
                active = whiteBalanceActive,
                onClick = onWhiteBalance
            )
        }
    }
}

@Composable
private fun BarButton(
    icon: Int,
    description: String,
    onClick: () -> Unit,
    active: Boolean = false
) {
    // No border: the old accent outline left a coloured halo over bright scenes.
    val background by animateColorAsState(
        targetValue = if (active) CamAccent else CamBarBtn,
        animationSpec = tween(160),
        label = "barButtonBg"
    )
    val tint by animateColorAsState(
        targetValue = if (active) CamOnAccent else CamAccent,
        animationSpec = tween(160),
        label = "barButtonTint"
    )
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(background)
            .hapticClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun SegmentedControl(
    mode: CaptureMode,
    enabled: Boolean,
    onSelect: (CaptureMode) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = CamBarBtn
    ) {
        Row(
            modifier = Modifier
                .height(48.dp)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SegmentButton(
                icon = R.drawable.ic_camera,
                description = stringResource(R.string.mode_photo),
                isSelected = mode == CaptureMode.PHOTO,
                enabled = enabled,
                onClick = { onSelect(CaptureMode.PHOTO) }
            )
            SegmentButton(
                icon = R.drawable.ic_video,
                description = stringResource(R.string.mode_video),
                isSelected = mode == CaptureMode.VIDEO,
                enabled = enabled,
                onClick = { onSelect(CaptureMode.VIDEO) }
            )
        }
    }
}

@Composable
private fun SegmentButton(
    icon: Int,
    description: String,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) CamAccent else Color.Transparent,
        animationSpec = tween(160),
        label = "segmentBg"
    )
    val iconTint by animateColorAsState(
        targetValue = when {
            isSelected -> CamOnAccent
            !enabled -> CamMuted
            else -> CamWhite
        },
        animationSpec = tween(160),
        label = "segmentTint"
    )
    val shape = RoundedCornerShape(20.dp)
    val selectedScale by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.94f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 520f),
        label = "modeSegmentScale"
    )

    Surface(
        shape = shape,
        color = backgroundColor,
        modifier = Modifier
            .size(width = 58.dp, height = 40.dp)
            .graphicsLayer { scaleX = selectedScale; scaleY = selectedScale }
            // Clip before the ripple so the pressed indication follows the pill, not a box.
            .clip(shape)
            .hapticClickable(enabled = enabled, onClick = onClick)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = description,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
