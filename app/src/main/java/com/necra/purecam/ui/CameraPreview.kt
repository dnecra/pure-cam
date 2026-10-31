package com.necra.purecam.ui

import android.content.Context
import android.graphics.Matrix
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.necra.purecam.core.Facing
import com.necra.purecam.presentation.CameraSignal
import com.necra.purecam.presentation.PreviewState
import com.necra.purecam.presentation.CameraViewModel
import kotlin.math.roundToInt

/**
 * SurfaceView hosted in Compose. SurfaceView gets a dedicated hardware overlay layer, so
 * SurfaceFlinger composites the camera frames directly with zero extra copies — unlike
 * TextureView, which forces a full buffer copy on every frame.
 *
 * The transform matrix is recomputed whenever the view size changes so the sensor image stays
 * upright, centre-cropped and undistorted. The front-camera mirror is applied on-screen only;
 * the saved JPEG is never mirrored.
 *
 * NOTE: SurfaceView does not support arbitrary 2D transforms via setTransform(). Centre-crop
 * framing is handled by sizing the SurfaceView itself so that its aspect ratio matches the
 * camera stream: Compose's AndroidView fills the available space and the ViewfinderFrame layout
 * already clips to the correct native ratio, so no Matrix is needed here.
 */
@Composable
internal fun CameraPreview(
    state: PreviewState,
    viewModel: CameraViewModel,
    onTap: ((Offset) -> Unit)? = null
) {
    val density = LocalDensity.current
    val focusRingSize = with(density) { 76.dp.toPx() }

    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusPulse by remember { mutableIntStateOf(0) }

    val mirrored = state.facing == Facing.FRONT

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        focusPoint = offset
                        focusPulse++
                        viewModel.tapToFocus(offset.x, offset.y)
                        onTap?.invoke(offset)
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1f) viewModel.zoomBy(zoom)
                }
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                PreviewSurfaceView(context).apply {
                    this.mirrored = mirrored
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            viewModel.onPreviewSurfaceAvailable()
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) = Unit

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            viewModel.onPreviewSurfaceDestroyed()
                        }
                    })
                    viewModel.attachPreview(this)
                }
            },
            update = { view ->
                view.mirrored = mirrored
            }
        )

        CaptureFlash(viewModel)
        FocusIndicator(focusPoint, focusPulse, focusRingSize)
    }
}

/**
 * A [SurfaceView] that optionally mirrors itself horizontally for the front camera.
 * SurfaceView lives in its own z-ordered window and is composited by SurfaceFlinger in
 * hardware, bypassing the GPU composition path that TextureView forces.
 */
private class PreviewSurfaceView(context: Context) : SurfaceView(context) {

    var mirrored: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            scaleX = if (value) -1f else 1f
        }
}

@Composable
private fun FocusIndicator(point: Offset?, pulse: Int, ringSizePx: Float) {
    if (point == null) return
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }

    LaunchedEffect(pulse) {
        alpha.snapTo(1f)
        scale.snapTo(0.82f)
        scale.animateTo(1f, tween(durationMillis = 160, easing = FastOutSlowInEasing))
        kotlinx.coroutines.delay(650)
        alpha.animateTo(0f, tween(durationMillis = 220))
    }

    val ringSize = with(LocalDensity.current) { ringSizePx.toDp() }
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (point.x - ringSizePx / 2f).roundToInt(),
                    (point.y - ringSizePx / 2f).roundToInt()
                )
            }
            .size(ringSize)
            .graphicsLayer {
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
            }
            .border(1.5.dp, Color.White.copy(alpha = 0.95f), CircleShape)
    )
}

@Composable
private fun CaptureFlash(viewModel: CameraViewModel) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        viewModel.signals.collect { signal ->
            if (signal is CameraSignal.CaptureFlash) {
                alpha.snapTo(0.55f)
                alpha.animateTo(0f, tween(durationMillis = 260))
            }
        }
    }
    val value = alpha.value
    if (value > 0.01f) {
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = value)))
    }
}

@Composable
internal fun GridOverlay(visible: Boolean) {
    if (!visible) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        val line = Color.White.copy(alpha = 0.32f)
        val stroke = 1.dp.toPx()
        val w = size.width
        val h = size.height
        drawLine(line, Offset(w / 3f, 0f), Offset(w / 3f, h), stroke)
        drawLine(line, Offset(w * 2f / 3f, 0f), Offset(w * 2f / 3f, h), stroke)
        drawLine(line, Offset(0f, h / 3f), Offset(w, h / 3f), stroke)
        drawLine(line, Offset(0f, h * 2f / 3f), Offset(w, h * 2f / 3f), stroke)
    }
}

@Composable
internal fun CountdownOverlay(countdown: Int?) {
    if (countdown == null) return
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(countdown) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = countdown,
            transitionSpec = {
                (scaleIn(initialScale = 1.18f) + fadeIn()) togetherWith fadeOut()
            },
            label = "countdown"
        ) { value ->
            Text(
                text = value.toString(),
                fontSize = 104.sp,
                fontWeight = FontWeight.Thin,
                color = Color.White
            )
        }
    }
}
