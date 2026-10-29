package com.necra.purecam.ui

import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.necra.purecam.R
import com.necra.purecam.core.Aspect
import com.necra.purecam.core.AspectMath
import com.necra.purecam.core.Facing
import com.necra.purecam.core.LensProfile
import com.necra.purecam.core.WhiteBalanceMode
import com.necra.purecam.presentation.CameraUiState
import com.necra.purecam.presentation.CaptureMode
import com.necra.purecam.presentation.TimerMode

// Google Camera (Pixel) UI colours, taken from new-activity_camera.xml.
private val CamBg = Color(0xFF000000)
private val CamScrim = Color(0x99202124)
private val CamBarBtn = Color(0xFF2B2B2D)
private val CamAccent = Color(0xFFA8C7FA)
private val CamOnAccent = Color(0xFF041E49)
private val CamWhite = Color(0xFFFFFFFF)
private val CamMuted = Color(0xFF8E8E93)

/** Ratios offered by the aspect button, in cycle order. */
private val AspectChoices = AspectMath.CROP_ASPECTS

/**
 * Pixel-style camera layout: rounded viewfinder card, floating scrim controls and a bottom bar.
 * Everything is driven by [state] so the screen stays stateless.
 *
 * The preview frame is sized to the selected aspect ratio and animates between ratios.
 *
 * @param content the live preview, drawn inside the aspect-correct frame.
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
    onSelectAspect: (Aspect) -> Unit = {},
    onWhiteBalance: () -> Unit = {},
    onFullscreenAspect: (Float) -> Unit = {},
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
    val aspect = state.cameraState?.aspect ?: Aspect.R4_3
    val whiteBalanceActive = state.cameraState?.wb !is WhiteBalanceMode.Auto

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
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(28.dp),
                color = CamBg
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Aspect-correct preview frame, centred inside the card.
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val targetAspect = state.plan?.displayAspect
                            ?.takeIf { it.isFinite() && it > 0.1f && it < 10f }
                            ?: (4f / 3f)
                        val frameAspect by animateFloatAsState(
                            targetValue = targetAspect,
                            animationSpec = tween(
                                durationMillis = 260,
                                easing = FastOutSlowInEasing
                            ),
                            label = "viewfinderAspect"
                        )
                        val maxW = maxWidth
                        val maxH = maxHeight
                        LaunchedEffect(maxW, maxH) {
                            if (maxH.value > 0f) {
                                onFullscreenAspect(maxW.value / maxH.value)
                            }
                        }
                        val frameWidth = if (maxW / maxH > frameAspect) maxH * frameAspect else maxW
                        val frameHeight = frameWidth / frameAspect
                        val frameShape = RoundedCornerShape(22.dp)

                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(width = frameWidth, height = frameHeight)
                                .clip(frameShape)
                                .border(1.dp, CamWhite.copy(alpha = 0.16f), frameShape)
                        ) {
                            content()
                            GridOverlay(visible = state.grid)
                            CountdownOverlay(countdown = state.countdown)
                        }
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

                    // Top controls: flash + timer left, live spec in the middle, aspect + grid right.
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
                        topSpec(state, aspect)?.let { spec ->
                            SpecPill(text = spec, onClick = onSettings)
                        }
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                        ) {
                            AspectCycleButton(
                                aspect = aspect,
                                enabled = !state.videoMode && !busy,
                                onSelect = onSelectAspect
                            )
                            CircleIconButton(
                                icon = R.drawable.ic_grid,
                                description = stringResource(R.string.grid),
                                active = state.grid,
                                onClick = onGrid
                            )
                        }
                    }

                    // Recording indicator.
                    if (state.recording) {
                        RecordingPill(
                            elapsedMs = state.recordingElapsedMs,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 60.dp)
                        )
                    }

                    // Bottom stack inside the card: lens menu + shutter row.
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (lenses.size > 1) {
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
    val tint = when {
        !enabled -> CamWhite.copy(alpha = 0.35f)
        active -> CamOnAccent
        else -> CamWhite
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (active) CamAccent else CamScrim)
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

/** One-line status: active WB preset, or the current photo/video spec when WB is automatic. */
private fun topSpec(state: CameraUiState, aspect: Aspect): String? = when (val wb = state.cameraState?.wb) {
    is WhiteBalanceMode.Preset -> wb.preset.label
    is WhiteBalanceMode.Kelvin -> "${wb.kelvin} K"
    else -> if (state.videoMode) {
        state.videoConfig?.let { "${it.quality.label} · ${it.fps} fps" }
    } else {
        state.plan?.let { plan ->
            val saved = AspectMath.savedSize(plan, state.lens?.sensorOrientation ?: 0)
            "${aspect.label} · ${saved.width}×${saved.height}"
        }
    }
}

@Composable
private fun SpecPill(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .widthIn(max = 120.dp)
            .clip(CircleShape)
            .background(CamScrim)
            .hapticClickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = CamWhite.copy(alpha = 0.92f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Tapping cycles through the ratios the UI offers; every one is produced by cropping. */
@Composable
private fun AspectCycleButton(
    aspect: Aspect,
    enabled: Boolean,
    onSelect: (Aspect) -> Unit
) {
    val index = AspectChoices.indexOf(aspect)
    val next = AspectChoices[(index + 1).mod(AspectChoices.size)]

    Box(
        modifier = Modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(CamScrim)
            .hapticClickable(enabled = enabled) { onSelect(next) }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = aspect.label,
            color = if (enabled) CamWhite else CamMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
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
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .widthIn(min = 52.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(if (selected) CamAccent else Color.Transparent)
                        .hapticClickable(enabled = enabled) { onSelect(lens) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = lens.label,
                        color = when {
                            selected -> CamOnAccent
                            !enabled -> CamMuted
                            else -> CamWhite
                        },
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
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (active) CamAccent else CamBarBtn)
            .hapticClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = if (active) CamOnAccent else CamAccent,
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
    val backgroundColor = if (isSelected) CamAccent else Color.Transparent
    val iconTint = when {
        isSelected -> CamOnAccent
        !enabled -> CamMuted
        else -> CamWhite
    }
    val shape = RoundedCornerShape(20.dp)

    Surface(
        shape = shape,
        color = backgroundColor,
        modifier = Modifier
            .size(width = 58.dp, height = 40.dp)
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
