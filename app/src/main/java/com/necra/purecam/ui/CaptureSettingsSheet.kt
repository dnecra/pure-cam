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
