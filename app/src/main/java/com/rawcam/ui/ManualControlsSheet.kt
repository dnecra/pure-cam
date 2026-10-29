@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.rawcam.ui

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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rawcam.R
import com.rawcam.core.ExposureMath
import com.rawcam.core.ExposureMode
import com.rawcam.core.ExposureValues
import com.rawcam.core.FocusMode
import com.rawcam.core.LensProfile
import com.rawcam.core.ManualFocus
import com.rawcam.core.WhiteBalanceMode
import com.rawcam.presentation.CameraUiState
import com.rawcam.presentation.CameraViewModel
import kotlin.math.roundToInt

private enum class SheetSection { Exposure, Focus, WhiteBalance }

/**
 * Material 3 modal bottom sheet with the lens' manual controls. Only sections the active lens
 * actually supports are offered; everything else stays automatic.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ManualControlsSheet(
    state: CameraUiState,
    viewModel: CameraViewModel,
    onDismiss: () -> Unit
) {
    val lens = state.lens ?: return
    val cameraState = state.cameraState ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var sectionIndex by rememberSaveable { mutableIntStateOf(0) }

    val sections = remember(lens.supportsManualFocus) {
        buildList {
            add(SheetSection.Exposure)
            if (lens.supportsManualFocus) add(SheetSection.Focus)
            add(SheetSection.WhiteBalance)
        }
    }
    val section = sections.getOrElse(sectionIndex) { sections.first() }

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
                text = stringResource(R.string.manual_controls),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(16.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                sections.forEachIndexed { index, item ->
                    SegmentedButton(
                        selected = item == section,
                        onClick = { sectionIndex = index },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = sections.size)
                    ) {
                        Text(
                            when (item) {
                                SheetSection.Exposure -> stringResource(R.string.exposure)
                                SheetSection.Focus -> stringResource(R.string.focus)
                                SheetSection.WhiteBalance -> stringResource(R.string.white_balance)
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            when (section) {
                SheetSection.Exposure -> ExposureSection(lens, cameraState.exposure, state.videoMode, viewModel)
                SheetSection.Focus -> FocusSection(lens, cameraState.focus, viewModel)
                SheetSection.WhiteBalance -> WhiteBalanceSection(lens, cameraState.wb, viewModel)
            }
        }
    }
}

@Composable
private fun ExposureSection(
    lens: LensProfile,
    exposure: ExposureMode,
    videoMode: Boolean,
    viewModel: CameraViewModel
) {
    val supports = lens.supportsManualExposure
    ModeSelector(
        options = listOf(stringResource(R.string.auto), stringResource(R.string.manual)),
        selectedIndex = if (exposure is ExposureMode.Manual) 1 else 0,
        enabled = listOf(true, supports)
    ) { index ->
        if (index == 0) viewModel.setExposureAuto() else viewModel.setExposureManual()
    }
    Spacer(Modifier.height(18.dp))

    if (!supports) {
        Hint(stringResource(R.string.manual_exposure_unavailable))
        return
    }
    when (exposure) {
        is ExposureMode.Auto -> Hint(stringResource(R.string.auto_exposure_hint))
        is ExposureMode.Manual -> {
            val isoRange = lens.isoRange ?: return
            val shutterRange = lens.exposureRange ?: return
            val isoStops = remember(lens.id) { ExposureMath.isoStops(isoRange) }
            val shutterStops = remember(lens.id) { ExposureMath.shutterStops(shutterRange) }
            val isoIndex = ExposureMath.nearestIsoIndex(isoStops, exposure.iso)
            val shutterIndex = ExposureMath.nearestIndex(shutterStops, exposure.shutterNs)

            LabeledSlider(
                title = "ISO",
                valueText = ExposureMath.formatIso(isoStops[isoIndex]),
                value = isoIndex.toFloat(),
                range = 0f..(isoStops.size - 1).coerceAtLeast(1).toFloat(),
                steps = (isoStops.size - 2).coerceAtLeast(0)
            ) { value ->
                val index = value.roundToInt().coerceIn(0, isoStops.lastIndex)
                viewModel.setExposureValues(isoStops[index], shutterStops[shutterIndex])
            }
            LabeledSlider(
                title = "Shutter",
                valueText = ExposureMath.formatShutter(shutterStops[shutterIndex]),
                value = shutterIndex.toFloat(),
                range = 0f..(shutterStops.size - 1).coerceAtLeast(1).toFloat(),
                steps = (shutterStops.size - 2).coerceAtLeast(0)
            ) { value ->
                val index = value.roundToInt().coerceIn(0, shutterStops.lastIndex)
                viewModel.setExposureValues(isoStops[isoIndex], shutterStops[index])
            }
            if (videoMode) {
                Hint(stringResource(R.string.video_exposure_hint))
            } else if (ExposureMath.isPreviewApproximate(ExposureValues(exposure.iso, exposure.shutterNs))) {
                Hint(stringResource(R.string.preview_approximate))
            }
        }
    }
}

@Composable
private fun FocusSection(lens: LensProfile, focus: FocusMode, viewModel: CameraViewModel) {
    ModeSelector(
        options = listOf(stringResource(R.string.auto), stringResource(R.string.manual)),
        selectedIndex = if (focus is FocusMode.Manual) 1 else 0
    ) { index ->
        if (index == 0) viewModel.setFocusAuto() else viewModel.setFocusManual()
    }
    Spacer(Modifier.height(18.dp))

    when (focus) {
        is FocusMode.Auto -> Hint(stringResource(R.string.auto_focus_hint))
        is FocusMode.Manual -> {
            val minFocus = lens.minFocusDiopters
            val fraction = if (minFocus > 0f) {
                (focus.diopters / minFocus).coerceIn(0f, 1f)
            } else {
                0f
            }
            LabeledSlider(
                title = stringResource(R.string.focus_distance),
                valueText = ManualFocus.label(focus.diopters, lens.focusCalibrated),
                value = fraction,
                range = 0f..1f
            ) { value ->
                viewModel.setFocusDiopters(value * minFocus)
            }
        }
    }
}

@Composable
private fun WhiteBalanceSection(lens: LensProfile, wb: WhiteBalanceMode, viewModel: CameraViewModel) {
    val presets = lens.awbPresets.toList()
    val selectedIndex = when (wb) {
        is WhiteBalanceMode.Auto -> 0
        is WhiteBalanceMode.Preset -> 1
        is WhiteBalanceMode.Kelvin -> 2
    }
    ModeSelector(
        options = listOf(
            stringResource(R.string.auto),
            stringResource(R.string.preset),
            stringResource(R.string.kelvin)
        ),
        selectedIndex = selectedIndex,
        enabled = listOf(true, presets.isNotEmpty(), lens.supportsKelvin)
    ) { index ->
        when (index) {
            0 -> viewModel.setWhiteBalanceAuto()
            1 -> presets.firstOrNull()?.let(viewModel::setWhiteBalancePreset)
            2 -> if (lens.supportsKelvin) viewModel.setWhiteBalanceKelvin(viewModel.suggestedKelvin())
        }
    }
    Spacer(Modifier.height(18.dp))

    when (wb) {
        is WhiteBalanceMode.Auto -> Hint(stringResource(R.string.auto_wb_hint))
        is WhiteBalanceMode.Preset -> FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            presets.forEach { preset ->
                FilterChip(
                    selected = wb.preset == preset,
                    onClick = { viewModel.setWhiteBalancePreset(preset) },
                    label = { Text(preset.label) }
                )
            }
        }
        is WhiteBalanceMode.Kelvin -> LabeledSlider(
            title = stringResource(R.string.kelvin),
            valueText = "${wb.kelvin} K",
            value = wb.kelvin.toFloat(),
            range = 2000f..10000f,
            steps = 159
        ) { value ->
            viewModel.setWhiteBalanceKelvin(value.roundToInt())
        }
    }
}

@Composable
private fun ModeSelector(
    options: List<String>,
    selectedIndex: Int,
    enabled: List<Boolean> = List(options.size) { true },
    onSelect: (Int) -> Unit
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                enabled = enabled.getOrElse(index) { true }
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(text = valueText, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
