@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.necra.purecam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.necra.purecam.R
import com.necra.purecam.core.SizeI
import com.necra.purecam.presentation.CameraUiState
import com.necra.purecam.presentation.CameraViewModel
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Capture settings: the still buffer size plus the video resolution and frame rate.
 * Replaces the old manual exposure/focus/WB sheet; white balance is cycled from the
 * main screen's WB button instead.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CaptureSettingsSheet(
    state: CameraUiState,
    viewModel: CameraViewModel,
    onDismiss: () -> Unit
) {
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
                text = stringResource(R.string.capture_settings),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(20.dp))

            PhotoSection(state, viewModel)

            if (state.supportsVideo) {
                Spacer(Modifier.height(24.dp))
                VideoSection(state, viewModel)
            } else {
                Spacer(Modifier.height(16.dp))
                Hint(stringResource(R.string.video_unavailable))
            }
        }
    }
}

@Composable
private fun PhotoSection(state: CameraUiState, viewModel: CameraViewModel) {
    Text(
        text = stringResource(R.string.photo_resolution),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    val sizes = state.availablePhotoSizes
    if (sizes.isEmpty()) {
        Hint(stringResource(R.string.photo_resolution_unavailable))
        return
    }
    val selected = state.photoResolution
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        sizes.forEach { size ->
            FilterChip(
                selected = size == selected,
                onClick = hapticAction { viewModel.setPhotoResolution(size) },
                label = { Text(sizeLabel(size)) }
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Hint(stringResource(R.string.photo_resolution_hint))
}

@Composable
private fun VideoSection(state: CameraUiState, viewModel: CameraViewModel) {
    val config = state.videoConfig

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
                selected = config?.quality == quality,
                onClick = hapticAction { viewModel.setVideoQuality(quality) },
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
                selected = config?.fps == fps,
                onClick = hapticAction { viewModel.setVideoFps(fps) },
                label = { Text(stringResource(R.string.frame_rate_value, fps)) }
            )
        }
    }

    if (config != null) {
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(
                R.string.video_summary,
                config.size.width,
                config.size.height,
                config.fps,
                com.necra.purecam.core.VideoMath.formatBitRate(config.bitRate)
            ),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(6.dp))
        Hint(stringResource(R.string.video_no_processing_hint))
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun sizeLabel(size: SizeI): String {
    val megapixels = size.area / 1_000_000f
    val mp = if (megapixels >= 10f) {
        "${megapixels.roundToInt()} MP"
    } else {
        String.format(Locale.US, "%.1f MP", megapixels)
    }
    return "$mp  ·  ${size.width}×${size.height}"
}
