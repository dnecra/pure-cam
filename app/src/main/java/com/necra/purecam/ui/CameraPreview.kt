package com.necra.purecam.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.view.View
import android.view.TextureView
import android.view.ViewOutlineProvider
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.necra.purecam.presentation.CameraSignal
import com.necra.purecam.presentation.PreviewState
import com.necra.purecam.presentation.CameraViewModel
import kotlin.math.roundToInt

/** TextureView supplies the sensor-oriented image; the matching stream ratio prevents stretching. */
@Composable
internal fun CameraPreview(
    viewModel: CameraViewModel,
    onTap: ((Offset) -> Unit)? = null
) {
    val previewState by viewModel.previewState.collectAsState(initial = PreviewState())
    val density = LocalDensity.current
    val focusRingSize = with(density) { 76.dp.toPx() }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusPulse by remember { mutableIntStateOf(0) }

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
                    viewModel.attachPreview(this)
                    configurePreview(previewState.facing == com.necra.purecam.core.Facing.FRONT)
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                            configurePreview(previewState.facing == com.necra.purecam.core.Facing.FRONT)
                            viewModel.onPreviewSurfaceAvailable(surface)
                        }

                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                            configurePreview(previewState.facing == com.necra.purecam.core.Facing.FRONT)
                        }

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            viewModel.onPreviewSurfaceDestroyed(surface)
                            return false
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                    }
                }
            },
            update = { view ->
                view.configurePreview(previewState.facing == com.necra.purecam.core.Facing.FRONT)
            }
        )

        CaptureFlash(viewModel)
        FocusIndicator(focusPoint, focusPulse, focusRingSize)
    }
}

/** Keeps the camera-provided sensor rotation without applying a selfie mirror. */
private class PreviewTextureView(context: Context) : TextureView(context) {
    init {
        // 18 dp circle radius + the controls' 10 dp inset from the viewfinder edge.
        val cornerRadius = 28f * resources.displayMetrics.density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        clipToOutline = true
    }

    fun configurePreview(mirror: Boolean = false) {
        // TextureView already applies SENSOR_ORIENTATION through its SurfaceTexture matrix.
        setTransform(null)
        scaleX = if (mirror) -1f else 1f
        scaleY = 1f
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
            .border(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.95f), CircleShape)
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
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val stroke = 1.dp.toPx()
        val w = size.width
        val h = size.height
        drawLine(gridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), stroke)
        drawLine(gridColor, Offset(w * 2f / 3f, 0f), Offset(w * 2f / 3f, h), stroke)
        drawLine(gridColor, Offset(0f, h / 3f), Offset(w, h / 3f), stroke)
        drawLine(gridColor, Offset(0f, h * 2f / 3f), Offset(w, h * 2f / 3f), stroke)
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
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
