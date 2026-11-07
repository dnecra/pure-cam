@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.necra.purecam.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.necra.purecam.R
import com.necra.purecam.core.CaptureAspectMode
import com.necra.purecam.core.OverlayStyle
import com.necra.purecam.core.WbPreset
import com.necra.purecam.presentation.CameraUiState
import com.necra.purecam.presentation.CameraViewModel

/** Compact camera settings: framing first, with only the available video choices below it. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CaptureSettingsSheet(
    state: CameraUiState,
    viewModel: CameraViewModel,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.preferredFrameRate(FrameRateCategory.High),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(stringResource(R.string.capture_settings), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(20.dp))

            Text(stringResource(R.string.aspect_ratio), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.aspectMode == CaptureAspectMode.PORTRAIT_9_16,
                    onClick = hapticAction { viewModel.setCaptureAspectMode(CaptureAspectMode.PORTRAIT_9_16) },
                    label = { Text(stringResource(R.string.aspect_9_16)) }
                )
                FilterChip(
                    selected = state.aspectMode == CaptureAspectMode.ORIGINAL,
                    onClick = hapticAction { viewModel.setCaptureAspectMode(CaptureAspectMode.ORIGINAL) },
                    label = { Text(stringResource(R.string.aspect_original)) }
                )
            }

            Spacer(Modifier.height(16.dp))
            WhiteBalanceSettings(state = state, viewModel = viewModel)

            Spacer(Modifier.height(16.dp))
            OverlaySettings(state = state, viewModel = viewModel)

            if (state.supportsVideo) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.video_section), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                OptionGroup(stringResource(R.string.resolution)) {
                    state.availableVideoQualities.forEach { quality ->
                        FilterChip(
                            selected = state.videoConfig?.quality == quality,
                            onClick = hapticAction { viewModel.setVideoQuality(quality) },
                            label = { Text(quality.label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OptionGroup(stringResource(R.string.frame_rate)) {
                    state.availableVideoFps.forEach { fps ->
                        FilterChip(
                            selected = state.videoConfig?.fps == fps,
                            onClick = hapticAction { viewModel.setVideoFps(fps) },
                            label = { Text(stringResource(R.string.frame_rate_value, fps)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WhiteBalanceSettings(
    state: CameraUiState,
    viewModel: CameraViewModel
) {
    val lens = state.lens
    Text(stringResource(R.string.white_balance), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.white_balance_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))

    OptionGroup(stringResource(R.string.wb_presets)) {
        WbPreset.CYCLE_ORDER.forEach { preset ->
            if (lens?.awbPresets?.contains(preset) == true) {
                val enabled = preset in state.enabledWbPresets
                FilterChip(
                    selected = enabled,
                    onClick = hapticAction { viewModel.setWbPresetEnabled(preset, !enabled) },
                    label = { Text(preset.label) }
                )
            }
        }
    }

    val anyPreset = state.enabledWbPresets.any { lens?.awbPresets?.contains(it) == true }
    if (!anyPreset) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.wb_none_enabled),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OverlaySettings(
    state: CameraUiState,
    viewModel: CameraViewModel
) {
    Text(stringResource(R.string.overlay), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.overlay_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))

    OptionGroup(stringResource(R.string.overlay_effects)) {
        OverlayStyle.entries.forEach { style ->
            val enabled = style in state.enabledOverlays
            FilterChip(
                selected = enabled,
                onClick = hapticAction { viewModel.setOverlayEnabled(style, !enabled) },
                label = { Text(style.label) }
            )
        }
    }
}

@Composable
private fun OptionGroup(
    title: String,
    content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit
) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            content = content
        )
    }
}
