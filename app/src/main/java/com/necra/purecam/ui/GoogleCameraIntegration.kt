package com.necra.purecam.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.necra.purecam.core.ExposureMode
import com.necra.purecam.presentation.CameraUiState
import com.necra.purecam.presentation.CameraViewModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** How long the exposure slider stays next to the focus ring. */
private const val EXPOSURE_SLIDER_TIMEOUT_MS = 2600L

/** Minimum EV step exposed by the slider. */
private const val EV_STEP = 0.5f
private const val EV_LIMIT = 2f

/**
 * Screen-level wrapper for the Pixel-style layout: owns the bottom sheets, the exposure
 * slider and the live preview, and routes every control to [CameraViewModel].
 */
@Composable
fun GoogleCameraScreen(
    state: CameraUiState,
    viewModel: CameraViewModel,
    onOpenPhoto: (Uri) -> Unit
) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    // Tap point in preview pixels; the exposure slider is anchored next to it.
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    // Held in a MutableState so dragging only recomposes the slider, not the camera UI.
    val exposureEv = rememberSaveable { mutableStateOf(0f) }

    GoogleCameraUI(
        state = state,
        onFlash = viewModel::toggleFlash,
        onTimer = viewModel::cycleTimer,
        onGrid = viewModel::toggleGrid,
        onShutter = viewModel::onShutter,
        onFlip = viewModel::flipCamera,
        onOpenPhoto = onOpenPhoto,
        onSelectMode = viewModel::setCaptureMode,
        onSelectLens = viewModel::selectLens,
        onWhiteBalance = viewModel::cycleWhiteBalance,
        onSettings = { settingsOpen = true }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { previewSize = it }
        ) {
            CameraPreview(
                state = state,
                viewModel = viewModel,
                onTap = { point -> focusPoint = point }
            )

            val point = focusPoint
            if (point != null && state.cameraState?.exposure is ExposureMode.Auto) {
                ExposureSlider(
                    point = point,
                    viewSize = previewSize,
                    ev = exposureEv,
                    onEvChange = viewModel::setExposureCompensation,
                    onDismiss = { focusPoint = null }
                )
            }
        }
    }

    if (settingsOpen) {
        CaptureSettingsSheet(
            state = state,
            viewModel = viewModel,
            onDismiss = { settingsOpen = false }
        )
    }
}

/**
 * Minimal vertical EV slider that appears beside the focus ring, like the stock camera apps.
 * It owns its own interaction timer so dragging does not recompose the rest of the screen.
 */
@Composable
private fun ExposureSlider(
    point: Offset,
    viewSize: IntSize,
    ev: MutableState<Float>,
    onEvChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val trackHeight = 132.dp
    val trackWidth = 32.dp
    val trackHeightPx = with(density) { trackHeight.toPx() }
    val trackWidthPx = with(density) { trackWidth.toPx() }
    val ringRadiusPx = with(density) { 38.dp.toPx() }
    val gapPx = with(density) { 12.dp.toPx() }
    val edgePx = with(density) { 10.dp.toPx() }

    // Prefer the right of the ring; flip to the left when there is no room.
    var x = point.x + ringRadiusPx + gapPx
    if (x + trackWidthPx > viewSize.width - edgePx) {
        x = point.x - ringRadiusPx - gapPx - trackWidthPx
    }
    x = x.coerceIn(edgePx, (viewSize.width - trackWidthPx - edgePx).coerceAtLeast(edgePx))
    val y = (point.y - trackHeightPx / 2f)
        .coerceIn(edgePx, (viewSize.height - trackHeightPx - edgePx).coerceAtLeast(edgePx))

    var interaction by remember { mutableIntStateOf(0) }

    // Reset the dismiss timer on every tap or drag.
    LaunchedEffect(point, interaction) {
        delay(EXPOSURE_SLIDER_TIMEOUT_MS)
        onDismiss()
    }

    val updateFromY: (Float) -> Unit = { yPx ->
        val fraction = (1f - yPx / trackHeightPx).coerceIn(0f, 1f)
        val value = ((-EV_LIMIT + fraction * EV_LIMIT * 2f) / EV_STEP)
            .roundToInt() * EV_STEP
        if (value != ev.value) {
            ev.value = value
            onEvChange(value)
        }
        interaction++
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .width(trackWidth)
            .height(trackHeight)
            .pointerInput(trackHeightPx) {
                detectTapGestures { offset -> updateFromY(offset.y) }
            }
            .pointerInput(trackHeightPx) {
                detectDragGestures(
                    onDragStart = { offset -> updateFromY(offset.y) },
                    onDrag = { change, _ ->
                        change.consume()
                        updateFromY(change.position.y)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Track.
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(trackHeight)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.55f))
        )
        // Thumb. Value runs bottom (-2 EV) to top (+2 EV).
        val fraction = (ev.value + EV_LIMIT) / (EV_LIMIT * 2f)
        Box(
            modifier = Modifier
                .offset { IntOffset(0, ((0.5f - fraction) * trackHeightPx).roundToInt()) }
                .width(12.dp)
                .height(12.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}
