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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.graphics.Typeface

private val PanelColor = Color(0xFF191918)
private val InactiveOptionColor = Color(0xFF353533)
private val SelectedOptionColor = Color(0xFFD0D0B3)
private val PrimaryTextColor = Color(0xFFEEEEEE)
private val SecondaryTextColor = Color(0xFFC5C5C0)
private val ValueTextColor = Color(0xFFD0D0B3)
private val SelectedTextColor = Color(0xFF292920)
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
                    color = PanelColor
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
                                .align(Alignment.CenterHorizontally)
                                .width(44.dp)
                                .height(5.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF666666))
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
                                }
                        )

                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Settings",
                                color = PrimaryTextColor,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontFamily = RoundedAndroidFont,
                                    fontSize = 24.sp
                                ),
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "×",
                                modifier = Modifier.hapticClickable { dismiss() }.padding(horizontal = 4.dp),
                                color = SecondaryTextColor,
                                fontSize = 28.sp
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
                                }
                            ) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircleOption(
                                        selected = true,
                                        backgroundColor = SelectedOptionColor,
                                        enabled = false,
                                        onClick = {}
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_white_balance),
                                            contentDescription = stringResource(R.string.auto),
                                            tint = SelectedTextColor,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    presets.forEach { preset ->
                                        val selected = preset in state.enabledWbPresets
                                        CircleOption(
                                            selected = selected,
                                            backgroundColor = wbButtonColor(preset),
                                            onClick = {
                                                showWbIndicator(preset.label)
                                                viewModel.setWbPresetEnabled(preset, !selected)
                                            }
                                        ) {
                                            OptionNumber((WbPreset.CYCLE_ORDER.indexOf(preset) + 2).toString())
                                        }
                                    }
                                }
                            }
                        }

                        SettingRow("Film overlay", overlayValue(state)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OverlayStyle.entries.forEachIndexed { index, style ->
                                    val selected = style in state.enabledOverlays
                                    CircleOption(
                                        selected = selected,
                                        backgroundColor = overlayButtonColor(style),
                                        onClick = { viewModel.setOverlayEnabled(style, !selected) }
                                    ) {
                                        OptionNumber((index + 1).toString())
                                    }
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
                                color = SecondaryTextColor.copy(alpha = 0.72f),
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                label,
                color = SecondaryTextColor,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 11.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Light
                ),
                maxLines = 2
            )
            Text(
                value,
                color = ValueTextColor,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 20.sp),
                maxLines = 2
            )
        }
        Box(Modifier.widthIn(max = 200.dp), contentAlignment = Alignment.TopStart) {
            options()
        }
    }
}

@Composable
private fun CircleOption(
    selected: Boolean,
    backgroundColor: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                backgroundColor?.let { if (selected) it else it.copy(alpha = 0.48f) }
                    ?: if (selected) SelectedOptionColor else InactiveOptionColor
            )
            .hapticClickable(enabled, onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

private fun wbButtonColor(preset: WbPreset): Color = when (preset) {
    WbPreset.INCANDESCENT -> Color(0xFFFF9A5C)
    WbPreset.FLUORESCENT -> Color(0xFFB9D77A)
    WbPreset.WARM_FLUORESCENT -> Color(0xFFFFC266)
    WbPreset.DAYLIGHT -> Color(0xFFFFE28A)
    WbPreset.CLOUDY_DAYLIGHT -> Color(0xFFA9D9F5)
    WbPreset.TWILIGHT -> Color(0xFFB9A0D8)
    WbPreset.SHADE -> Color(0xFF83BDE0)
}

private fun overlayButtonColor(style: OverlayStyle): Color = when (style) {
    OverlayStyle.DUST -> Color(0xFFB99A6C)
    OverlayStyle.BURN_IN -> Color(0xFFC97845)
    OverlayStyle.LIGHT_LEAK -> Color(0xFFBD7180)
}

@Composable
private fun OptionNumber(number: String) {
    Text(
        number,
        color = SelectedTextColor,
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    )
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
            .background(if (selected) SelectedOptionColor else InactiveOptionColor)
            .hapticClickable(enabled, onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) SelectedTextColor else PrimaryTextColor,
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
