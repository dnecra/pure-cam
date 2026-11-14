@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.necra.purecam.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.necra.purecam.R
import com.necra.purecam.core.CaptureAspectMode
import com.necra.purecam.core.OverlayStyle
import com.necra.purecam.core.WbPreset
import com.necra.purecam.core.WhiteBalanceMode
import com.necra.purecam.presentation.CameraUiState
import com.necra.purecam.presentation.CameraViewModel
import com.necra.purecam.presentation.FlashBehavior
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.graphics.Typeface

private val RoundedAndroidFont = FontFamily(Typeface.create("sans-serif-rounded", Typeface.NORMAL))

@Composable
fun CaptureSettingsSheet(
    state: CameraUiState,
    viewModel: CameraViewModel,
    onDismiss: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val currentWb = state.cameraState?.wb
    var previousWb by remember { mutableStateOf<WhiteBalanceMode?>(null) }
    var transientWbIndicator by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var wbIndicatorJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val showWbIndicator: (String) -> Unit = { label ->
        wbIndicatorJob?.cancel()
        transientWbIndicator = label
        wbIndicatorJob = scope.launch {
            delay(2_500)
            transientWbIndicator = null
        }
    }
    LaunchedEffect(Unit) { visible = true }
    LaunchedEffect(currentWb) {
        if (currentWb != previousWb) {
            previousWb = currentWb
            val presetName = (currentWb as? WhiteBalanceMode.Preset)?.preset?.label
            if (presetName != null) {
                showWbIndicator(presetName)
            } else {
                wbIndicatorJob?.cancel()
                transientWbIndicator = null
            }
        }
    }
    val dismiss: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                visible = false
                delay(260)
                onDismiss()
            }
        }
    }

    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(spring(dampingRatio = 0.88f, stiffness = 500f)) { it } + fadeIn(tween(150)),
                exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(160))
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .pointerInput(Unit) {
                                    var drag = 0f
                                    detectVerticalDragGestures(
                                        onVerticalDrag = { _, amount ->
                                            drag += amount
                                            if (drag > 52.dp.toPx()) {
                                                drag = 0f
                                                dismiss()
                                            }
                                        },
                                        onDragEnd = { drag = 0f },
                                        onDragCancel = { drag = 0f }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier
                                    .width(44.dp)
                                    .height(5.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
                            )
                        }

                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Settings",
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontFamily = RoundedAndroidFont,
                                    fontSize = 24.sp
                                ),
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.weight(1f))
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.close),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .hapticClickable { dismiss() }
                                    .padding(10.dp)
                            )
                        }
                        Spacer(Modifier.height(8.dp))

                        SettingRow("Aspect ratio", ratioLabel(state.aspectMode)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CaptureAspectMode.entries.forEach { mode ->
                                    TextPill(
                                        label = ratioLabel(mode),
                                        selected = state.aspectMode == mode,
                                        enabled = !state.recording && !state.recordingStarting,
                                        onClick = { viewModel.setCaptureAspectMode(mode) }
                                    )
                                }
                            }
                        }

                        state.lens?.let { lens ->
                            val presets = WbPreset.CYCLE_ORDER.filter(lens.awbPresets::contains)
                            val activePresetCount = presets.count(state.enabledWbPresets::contains)
                            SettingRow(
                                "White balance presets",
                                transientWbIndicator ?: if (activePresetCount == 0) {
                                    stringResource(R.string.wb_cycle_only_auto)
                                } else {
                                    stringResource(R.string.wb_cycle_count, activePresetCount)
                                },
                            ) {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    maxItemsInEachRow = 4,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircleOption(
                                        selected = true,
                                        enabled = false,
                                        onClick = {}
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_white_balance),
                                            contentDescription = stringResource(R.string.auto),
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    presets.forEach { preset ->
                                        val selected = preset in state.enabledWbPresets
                                        CircleOption(
                                            selected = selected,
                                            onClick = {
                                                showWbIndicator(preset.label)
                                                viewModel.setWbPresetEnabled(preset, !selected)
                                            }
                                        ) {
                                            MaterialSymbolIcon(
                                                symbol = preset.symbol,
                                                description = preset.label,
                                                tint = if (selected) MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.onSurface,
                                                size = 16.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        SettingRow("Film overlay", overlayValue(state)) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                maxItemsInEachRow = 4,
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OverlayStyle.entries.forEach { style ->
                                    val selected = style in state.enabledOverlays
                                    CircleOption(
                                        selected = selected,
                                        onClick = { viewModel.setOverlayEnabled(style, !selected) }
                                    ) {
                                        MaterialSymbolIcon(
                                            symbol = style.symbol,
                                            description = style.label,
                                            tint = if (selected) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurface,
                                            size = 16.sp
                                        )
                                    }
                                }
                            }
                        }

                        SettingRow(
                            stringResource(R.string.front_camera),
                            if (state.frontCameraFlipSave) stringResource(R.string.front_mirror_save_on)
                            else stringResource(R.string.front_mirror_save_off)
                        ) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextPill(
                                    label = stringResource(R.string.front_mirror_save),
                                    selected = state.frontCameraFlipSave,
                                    enabled = true,
                                    onClick = { viewModel.setFrontCameraFlipSave(!state.frontCameraFlipSave) }
                                )
                            }
                        }

                        SettingRow(
                            stringResource(R.string.flash_behavior),
                            if (state.flashBehavior == FlashBehavior.NORMAL) stringResource(R.string.flash_behavior_normal)
                            else stringResource(R.string.flash_behavior_late)
                        ) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FlashBehavior.entries.forEach { behavior ->
                                    val selected = state.flashBehavior == behavior
                                    TextPill(
                                        label = if (behavior == FlashBehavior.NORMAL) stringResource(R.string.flash_behavior_normal)
                                        else stringResource(R.string.flash_behavior_late),
                                        selected = selected,
                                        enabled = true,
                                        onClick = { viewModel.setFlashBehavior(behavior) }
                                    )
                                }
                            }
                        }

                        if (state.supportsVideo) {
                            val activeQuality = state.videoConfig?.quality
                            SettingRow("Video resolution", activeQuality?.label.orEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    state.availableVideoQualities.forEach { quality ->
                                        TextPill(
                                            quality.label,
                                            selected = quality == activeQuality,
                                            enabled = !state.recording && !state.recordingStarting
                                        ) { viewModel.setVideoQuality(quality) }
                                    }
                                }
                            }

                            val activeFps = state.videoConfig?.fps ?: state.videoFps
                            SettingRow("Frame rate", stringResource(R.string.frame_rate_value, activeFps)) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    state.availableVideoFps.forEach { fps ->
                                        TextPill(
                                            fps.toString(),
                                            selected = fps == activeFps,
                                            enabled = !state.recording && !state.recordingStarting
                                        ) { viewModel.setVideoFps(fps) }
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text(
                                "made by Necra",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 10.sp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    label: String,
    value: String,
    options: @Composable () -> Unit
) {
    @Composable
    fun LabelValue(modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 11.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Light
                ),
                maxLines = 2
            )
            Text(
                value,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 20.sp),
                maxLines = 2
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LabelValue(Modifier.weight(1f))
        Box(Modifier.widthIn(max = 200.dp), contentAlignment = Alignment.TopEnd) {
            options()
        }
    }
}

@Composable
private fun CircleOption(
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                shape = CircleShape
            )
            .hapticClickable(enabled, onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun TextPill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .widthIn(min = 40.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .hapticClickable(enabled, onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 12.sp),
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun ratioLabel(mode: CaptureAspectMode): String = when (mode) {
    CaptureAspectMode.PORTRAIT_9_16 -> stringResource(R.string.ratio_9_16)
    CaptureAspectMode.ORIGINAL -> stringResource(R.string.ratio_original)
}

@Composable
private fun overlayValue(state: CameraUiState): String = when (state.enabledOverlays.size) {
    0 -> stringResource(R.string.off)
    1 -> state.enabledOverlays.first().label
    else -> stringResource(R.string.overlay_active_count, state.enabledOverlays.size)
}
