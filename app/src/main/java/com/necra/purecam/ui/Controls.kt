package com.necra.purecam.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.necra.purecam.R
import com.necra.purecam.core.OrientationMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** [clickable] with a light haptic tick on every successful tap. */
@Composable
internal fun Modifier.hapticClickable(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    val haptics = LocalHapticFeedback.current
    return clickable(enabled = enabled) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onClick()
    }
}

/** Wraps a Material component's `onClick` so it ticks like the custom camera controls. */
@Composable
internal fun hapticAction(action: () -> Unit): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return remember(action, haptics) {
        {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            action()
        }
    }
}

@Composable
internal fun ShutterButton(
    capturing: Boolean,
    recording: Boolean,
    videoMode: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && !capturing) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 900f),
        label = "shutterScale"
    )
    val accent by animateColorAsState(
        targetValue = if (videoMode) Color(0xFFE53935) else MaterialTheme.colorScheme.primary,
        animationSpec = tween(180),
        label = "shutterAccent"
    )
    Box(
        modifier = Modifier
            .size(76.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .border(4.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f), CircleShape)
            .padding(7.dp)
            .clip(CircleShape)
            .background(accent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !capturing,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = when {
                capturing -> "capturing"
                videoMode && recording -> "recording"
                else -> "idle"
            },
            transitionSpec = {
                (fadeIn(spring(dampingRatio = 0.62f)) + scaleIn(spring(dampingRatio = 0.62f))) togetherWith fadeOut()
            },
            label = "shutterState"
        ) { shutterState ->
            when (shutterState) {
                "recording" -> Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.onSurface)
                )
                "capturing" -> CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.5.dp
                )
                else -> Spacer(Modifier.size(1.dp))
            }
        }
    }
}

@Composable
internal fun FlipButton(enabled: Boolean, rotationDegrees: Int, onClick: () -> Unit) {
    // Each tap adds a half turn so the icon spins as the camera swaps front/back.
    var turns by remember { mutableIntStateOf(0) }
    val angle by animateFloatAsState(
        targetValue = OrientationMath.iconRotation(rotationDegrees) + 180f * turns,
        animationSpec = tween(durationMillis = 320),
        label = "flipRotation"
    )
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), CircleShape)
            .hapticClickable(enabled = enabled) {
                turns += 1
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_flip),
            contentDescription = stringResource(R.string.flip_camera),
            tint = if (enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer { rotationZ = angle }
        )
    }
}

@Composable
internal fun PhotoThumbnail(
    uri: android.net.Uri?,
    isVideo: Boolean,
    onClick: (android.net.Uri) -> Unit
) {
    val context = LocalContext.current
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) { loadThumbnail(context, uri, 120) }
    }
    val shape = CircleShape
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), shape)
            .hapticClickable(enabled = uri != null) { uri?.let(onClick) },
        contentAlignment = Alignment.Center
    ) {
        val bitmap = thumbnail
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.gallery),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_photo),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }
        if (isVideo && bitmap != null) {
            val playIconColor = MaterialTheme.colorScheme.onSurface
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(16.dp)
                    .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(7.dp)) {
                    val path = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width, size.height / 2f)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(path, playIconColor)
                }
            }
        }
    }
}

/**
 * Recording indicator. The elapsed time ticks locally, so the clock never pushes updates
 * through the shared UI state (which would recompose the whole camera screen).
 */
@Composable
fun RecordingPill(modifier: Modifier = Modifier) {
    var elapsedMs by remember { mutableLongStateOf(0L) }
    val recordingColor = MaterialTheme.colorScheme.error
    LaunchedEffect(Unit) {
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            elapsedMs = SystemClock.elapsedRealtime() - startedAt
            delay(250)
        }
    }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
        modifier = modifier.height(34.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val pulse = rememberInfiniteTransition(label = "recordingPulse")
            val dotAlpha by pulse.animateFloat(
                initialValue = 1f,
                targetValue = 0.35f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "recordingDot"
            )
            Canvas(modifier = Modifier.size(7.dp)) {
                drawCircle(
                    recordingColor.copy(alpha = dotAlpha),
                    radius = size.minDimension / 2f,
                    center = Offset(size.width / 2f, size.height / 2f)
                )
            }
            Text(
                text = com.necra.purecam.core.VideoMath.formatDuration(elapsedMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
