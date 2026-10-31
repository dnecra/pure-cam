@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.necra.purecam.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.necra.purecam.R
import com.necra.purecam.core.SizeI
import com.necra.purecam.presentation.CameraUiState
import com.necra.purecam.presentation.CameraViewModel
import java.util.Locale
import kotlin.math.roundToInt

/** Capture format and video quality controls, presented as one expressive settings surface. */
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
        modifier = Modifier.preferredFrameRate(FrameRateCategory.High),
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            SheetHeader()
            Spacer(Modifier.height(18.dp))
            PhotoSection(state)

            AnimatedVisibility(
                visible = state.supportsVideo,
                enter = fadeIn(spring(stiffness = 380f)) + slideInVertically(spring(stiffness = 380f)) { it / 5 },
                exit = fadeOut() + slideOutVertically { it / 6 }
            ) {
                Column {
                    Spacer(Modifier.height(14.dp))
                    VideoSection(state, viewModel)
                }
            }
            AnimatedVisibility(visible = !state.supportsVideo, enter = fadeIn(), exit = fadeOut()) {
                Column {
                    Spacer(Modifier.height(14.dp))
                    Hint(stringResource(R.string.video_unavailable))
                }
            }
        }
    }
}

@Composable
private fun SheetHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = null,
                modifier = Modifier.padding(16.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Column {
            Text(stringResource(R.string.capture_settings), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = stringResource(R.string.capture_settings_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PhotoSection(state: CameraUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 17.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.52f)) {
                Icon(
                    painter = painterResource(R.drawable.ic_photo),
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp).size(22.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Column {
                Text(stringResource(R.string.photo_section), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(3.dp))
                state.photoSize?.let { size ->
                    Text(
                        text = stringResource(R.string.photo_full_resolution, sizeLabel(size)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Text(
                    text = stringResource(R.string.photo_format_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                )
            }
        }
    }
}

@Composable
private fun VideoSection(state: CameraUiState, viewModel: CameraViewModel) {
    val config = state.videoConfig
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        SectionTitle(stringResource(R.string.video_section), stringResource(R.string.video_section_hint))
        Spacer(Modifier.height(16.dp))
        OptionGroup(stringResource(R.string.resolution)) {
            state.availableVideoQualities.forEach { quality ->
                FilterChip(
                    selected = config?.quality == quality,
                    onClick = hapticAction { viewModel.setVideoQuality(quality) },
                    label = { Text(quality.label) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        OptionGroup(stringResource(R.string.frame_rate)) {
            state.availableVideoFps.forEach { fps ->
                FilterChip(
                    selected = config?.fps == fps,
                    onClick = hapticAction { viewModel.setVideoFps(fps) },
                    label = { Text(stringResource(R.string.frame_rate_value, fps)) }
                )
            }
        }
        AnimatedContent(
            targetState = config,
            transitionSpec = { fadeIn(spring(stiffness = 420f)) togetherWith fadeOut() },
            label = "videoSummary"
        ) { selected ->
            if (selected != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(
                                R.string.video_summary,
                                selected.size.width,
                                selected.size.height,
                                selected.fps,
                                com.necra.purecam.core.VideoMath.formatBitRate(selected.bitRate)
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.video_no_processing_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.78f)
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(0.dp))
            }
        }
    }
}

@Composable
private fun OptionGroup(title: String, content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content
        )
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun sizeLabel(size: SizeI): String {
    val megapixels = size.area / 1_000_000f
    val mp = if (megapixels >= 10f) "${megapixels.roundToInt()} MP" else String.format(Locale.US, "%.1f MP", megapixels)
    return "$mp · ${size.width}×${size.height}"
}
