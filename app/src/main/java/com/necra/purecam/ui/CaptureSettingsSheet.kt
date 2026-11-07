@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.necra.purecam.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.necra.purecam.R
import com.necra.purecam.core.CaptureAspectMode
import com.necra.purecam.core.OverlayStyle
import com.necra.purecam.core.WbPreset
import com.necra.purecam.core.WhiteBalanceMode
import com.necra.purecam.presentation.CameraUiState
import com.necra.purecam.presentation.CameraViewModel
import java.util.Locale
import kotlinx.coroutines.launch

/** One compact chip inside a settings group. */
private data class ChipOption<T>(
    val value: T,
    val label: String
)

private val ChipShape = RoundedCornerShape(11.dp)

/**
 * Compact single-page camera settings: one label per group, small option chips and a thin
 * separator between groups.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CaptureSettingsSheet(
    state: CameraUiState,
    viewModel: CameraViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val closeSheet: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = closeSheet,
        modifier = Modifier.preferredFrameRate(FrameRateCategory.High),
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            GroupHeader(stringResource(R.string.mode_photo))

            val lens = state.lens

            CompactSection(
                title = stringResource(R.string.ratio),
                value = ratioSummary(state)
            ) {
                ChipRow(
                    options = CaptureAspectMode.entries.map { mode ->
                        ChipOption(mode, ratioLabel(mode))
                    },
                    isSelected = { it == state.aspectMode },
                    enabled = !state.recording && !state.recordingStarting,
                    onSelect = viewModel::setCaptureAspectMode
                )
            }

            if (lens != null) {
                CompactSection(
                    title = stringResource(R.string.white_balance),
                    value = whiteBalanceLabel(state.cameraState?.wb)
                ) {
                    ChipRow(
                        options = WbPreset.CYCLE_ORDER
                            .filter { lens.awbPresets.contains(it) }
                            .map { ChipOption(it, it.label) },
                        isSelected = { it in state.enabledWbPresets },
                        onSelect = { preset ->
                            viewModel.setWbPresetEnabled(preset, preset !in state.enabledWbPresets)
                        }
                    )
                    if (state.enabledWbPresets.none { lens.awbPresets.contains(it) }) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.wb_none_enabled),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            CompactSection(
                title = stringResource(R.string.overlay),
                value = overlaySummary(state)
            ) {
                ChipRow(
                    options = OverlayStyle.entries.map { ChipOption(it, it.label) },
                    isSelected = { it in state.enabledOverlays },
                    onSelect = { style ->
                        viewModel.setOverlayEnabled(style, style !in state.enabledOverlays)
                    }
                )
            }

            if (state.supportsVideo) {
                Spacer(Modifier.height(12.dp))
                GroupHeader(stringResource(R.string.video_section))
                CompactSection(
                    title = stringResource(R.string.resolution),
                    value = state.videoConfig?.quality?.label.orEmpty()
                ) {
                    ChipRow(
                        options = state.availableVideoQualities.map {
                            ChipOption(it, it.label)
                        },
                        isSelected = { it == state.videoConfig?.quality },
                        enabled = !state.recording && !state.recordingStarting,
                        onSelect = viewModel::setVideoQuality
                    )
                }

                val activeFps = state.videoConfig?.fps ?: state.videoFps
                CompactSection(
                    title = stringResource(R.string.frame_rate),
                    value = stringResource(R.string.frame_rate_value, activeFps)
                ) {
                    ChipRow(
                        options = state.availableVideoFps.map {
                            ChipOption(it, stringResource(R.string.frame_rate_value, it))
                        },
                        isSelected = { it == activeFps },
                        enabled = !state.recording && !state.recordingStarting,
                        onSelect = viewModel::setVideoFps
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.settings_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CompactSection(
    title: String,
    value: String?,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun <T> ChipRow(
    options: List<ChipOption<T>>,
    isSelected: (T) -> Boolean,
    enabled: Boolean = true,
    onSelect: (T) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val selected = isSelected(option.value)
            val background by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                animationSpec = tween(150),
                label = "chipBackground"
            )
            val contentColor by animateColorAsState(
                targetValue = when {
                    selected -> MaterialTheme.colorScheme.onPrimary
                    enabled -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(150),
                label = "chipContent"
            )
            Row(
                modifier = Modifier
                    .height(34.dp)
                    .clip(ChipShape)
                    .background(background)
                    .hapticClickable(enabled = enabled) { onSelect(option.value) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                    color = contentColor,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(title: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
        )
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun ratioLabel(mode: CaptureAspectMode): String = when (mode) {
    CaptureAspectMode.PORTRAIT_9_16 -> stringResource(R.string.ratio_9_16)
    CaptureAspectMode.ORIGINAL -> stringResource(R.string.ratio_original)
}

@Composable
private fun ratioSummary(state: CameraUiState): String {
    val ratio = ratioLabel(state.aspectMode)
    val megapixels = state.photoSize?.let { formatMegapixels(it.width, it.height) }
        ?: return ratio
    return stringResource(R.string.ratio_summary, ratio, megapixels)
}

@Composable
private fun overlaySummary(state: CameraUiState): String = when (state.enabledOverlays.size) {
    0 -> stringResource(R.string.off)
    1 -> state.enabledOverlays.first().label
    else -> stringResource(R.string.overlay_active_count, state.enabledOverlays.size)
}

@Composable
private fun whiteBalanceLabel(wb: WhiteBalanceMode?): String = when (wb) {
    is WhiteBalanceMode.Preset -> wb.preset.label
    is WhiteBalanceMode.Kelvin -> stringResource(R.string.kelvin_value, wb.kelvin)
    else -> stringResource(R.string.auto)
}

private fun formatMegapixels(width: Int, height: Int): String =
    String.format(Locale.US, "%.1f", width.toLong() * height / 1_000_000.0)
