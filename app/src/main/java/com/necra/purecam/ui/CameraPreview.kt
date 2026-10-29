package com.necra.purecam.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import com.necra.purecam.presentation.CameraUiState
import com.necra.purecam.presentation.CameraViewModel
import kotlin.math.roundToInt

/**
 * TextureView hosted in Compose. The preview fills the whole window; a centre-crop matrix keeps
 * the sensor image upright and unstretched, and mirrors the front camera on screen only
 * (the saved JPEG stays unmirrored).
 *
 * The transform is applied synchronously from [onSizeChanged] so an animating viewfinder
 * never shows a stretched frame while the ratio changes.
 */
@Composable
internal fun CameraPreview(
    state: CameraUiState,
    viewModel: CameraViewModel,
    onTap: ((Offset) -> Unit)? = null
) {
    val density = LocalDensity.current
    val focusRingSize = with(density) { 76.dp.toPx() }

    var surfaceKey by remember { mutableIntStateOf(0) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusPulse by remember { mutableIntStateOf(0) }
    val textureRef = remember { mutableStateOf<PreviewTextureView?>(null) }

    val mirrored = state.facing == Facing.FRONT
    val contentAspect = state.plan?.contentAspect

    LaunchedEffect(contentAspect, mirrored, surfaceKey) {
        textureRef.value?.apply {
            displayAspect = contentAspect
            this.mirrored = mirrored
            applyTransform()
        }
    }

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
                PreviewTextureView(context).apply {
                    displayAspect = contentAspect
                    this.mirrored = mirrored
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            surfaceKey++
                            applyTransform()
                            viewModel.onPreviewSurfaceAvailable()
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) = Unit

                        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                            viewModel.onPreviewSurfaceDestroyed()
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
                    }
                    textureRef.value = this
                    viewModel.attachPreview(this)
                }
            }
        )

        CaptureFlash(viewModel)
        FocusIndicator(focusPoint, focusPulse, focusRingSize)
    }
}

/** A [TextureView] that keeps the centre-crop matrix in sync with its own size. */
private class PreviewTextureView(context: Context) : TextureView(context) {

    var displayAspect: Float? = null
    var mirrored: Boolean = false

    fun applyTransform() {
        setTransform(previewTransform(displayAspect, IntSize(width, height), mirrored))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyTransform()
    }
}

private fun previewTransform(displayAspect: Float?, viewSize: IntSize, mirrored: Boolean): Matrix {
    val matrix = Matrix()
    if (displayAspect == null || viewSize.width <= 0 || viewSize.height <= 0 ||
        !displayAspect.isFinite() || displayAspect <= 0f
    ) {
        return matrix
    }
    val viewAspect = viewSize.width.toFloat() / viewSize.height.toFloat()
    val cx = viewSize.width / 2f
    val cy = viewSize.height / 2f
    if (displayAspect >= viewAspect) {
        matrix.setScale(displayAspect / viewAspect, 1f, cx, cy)
    } else {
        matrix.setScale(1f, viewAspect / displayAspect, cx, cy)
    }
    if (mirrored) matrix.postScale(-1f, 1f, cx, cy)
    return matrix
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
