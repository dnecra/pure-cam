@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.rawcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rawcam.R
import com.rawcam.core.VideoMath
import com.rawcam.presentation.CameraUiState
import com.rawcam.presentation.CameraViewModel

/**
 * Resolution and frame-rate choices for video. Only streams the active lens advertises are
 * listed, so every chip is guaranteed to be accepted by the encoder.
 */
@Composable
fun VideoSettingsSheet(
    state: CameraUiState,
    viewModel: CameraViewModel,
    onDismiss: () -> Unit
) {
    val config = state.videoConfig ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.video_settings),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.resolution),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                state.availableVideoQualities.forEach { quality ->
                    FilterChip(
                        selected = config.quality == quality,
                        onClick = { viewModel.setVideoQuality(quality) },
                        label = { Text(quality.label) }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.frame_rate),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                state.availableVideoFps.forEach { fps ->
                    FilterChip(
                        selected = config.fps == fps,
                        onClick = { viewModel.setVideoFps(fps) },
                        label = { Text(stringResource(R.string.frame_rate_value, fps)) }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(16.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.video_summary,
                        config.size.width,
                        config.size.height,
                        config.fps,
                        VideoMath.formatBitRate(config.bitRate)
                    ),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.video_no_processing_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.video_audio_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
