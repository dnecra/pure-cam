package com.necra.purecam.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

/**
 * Design tokens for the camera screen.
 *
 * Camera chrome sits on a black canvas, but every control and dialog colour still comes from the
 * Material theme, so dynamic colour changes stay coherent. Spacing and sizes are named instead of
 * ad-hoc values, which keeps every row on the same 4 dp grid and every control at the 48 dp
 * minimum touch target.
 */
internal object CameraSpace {
    const val XS = 4
    const val S = 8
    const val M = 12
    const val L = 16
    const val XL = 24
}

internal object CameraSize {
    /** Material's minimum interactive target. */
    const val TOUCH = 48
    const val TOP_BAR = 56
    const val LENS_ROW = 44
    const val SHUTTER_ROW = 88
    const val BOTTOM_BAR = 64
    const val CARD_RADIUS = 32
    const val ICON = 24
    const val RECORDING_PILL = 32
}

/** Large, consistent corner radii keep the chrome in the Expressive shape language. */
internal object CameraShape {
    const val BAR = 28
    const val CHIP = 18
}

internal object CameraMotion {
    const val PRESS_IN = 110L
    const val PRESS_OUT = 220L
    const val MORPH = 280L
}

internal class CameraPalette(context: Context) {
    @ColorInt val primary = context.color(androidx.appcompat.R.attr.colorPrimary, 0xFFA8C7FA.toInt())
    @ColorInt val onPrimary = context.color(com.google.android.material.R.attr.colorOnPrimary, 0xFF041E49.toInt())
    @ColorInt val primaryContainer = context.color(com.google.android.material.R.attr.colorPrimaryContainer, 0xFF0842A0.toInt())
    @ColorInt val onPrimaryContainer = context.color(com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFFD3E3FD.toInt())
    @ColorInt val secondaryContainer = context.color(com.google.android.material.R.attr.colorSecondaryContainer, 0xFF332D41.toInt())
    @ColorInt val surface = context.color(com.google.android.material.R.attr.colorSurface, 0xFF141318.toInt())
    @ColorInt val surfaceContainerLow = context.color(com.google.android.material.R.attr.colorSurfaceContainerLow, 0xFF1D1B20.toInt())
    @ColorInt val surfaceContainer = context.color(com.google.android.material.R.attr.colorSurfaceContainer, 0xFF211F26.toInt())
    @ColorInt val surfaceContainerHigh = context.color(com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF2B2930.toInt())
    @ColorInt val surfaceContainerHighest = context.color(com.google.android.material.R.attr.colorSurfaceContainerHighest, 0xFF36343B.toInt())
    @ColorInt val onSurface = context.color(com.google.android.material.R.attr.colorOnSurface, 0xFFE6E1E5.toInt())
    @ColorInt val onSurfaceVariant = context.color(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFCAC4D0.toInt())
    @ColorInt val outline = context.color(com.google.android.material.R.attr.colorOutline, 0xFF938F99.toInt())
    @ColorInt val outlineVariant = context.color(com.google.android.material.R.attr.colorOutlineVariant, 0xFF49454F.toInt())
    @ColorInt val error = context.color(androidx.appcompat.R.attr.colorError, 0xFFFFB4AB.toInt())
    @ColorInt val onError = context.color(com.google.android.material.R.attr.colorOnError, 0xFF690005.toInt())

    /** Recording red is semantic, not part of the theme's tonal palette. */
    @ColorInt val record = 0xFFE5484D.toInt()

    @ColorInt fun withAlpha(@ColorInt color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }
}

@ColorInt
internal fun Context.color(@AttrRes attr: Int, @ColorInt fallback: Int): Int =
    MaterialColors.getColor(this, attr, fallback)

internal fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).roundToInt()

internal fun Context.dp(value: Float): Float =
    value * resources.displayMetrics.density

internal fun Context.pill(
    @ColorInt color: Int,
    radiusDp: Int = CameraSize.TOUCH / 2,
    strokeWidthDp: Int = 0,
    @ColorInt strokeColor: Int = Color.TRANSPARENT
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(radiusDp).toFloat()
    setColor(color)
    if (strokeWidthDp > 0) setStroke(dp(strokeWidthDp), strokeColor)
}

/** Springy press feedback for every interactive control. Returning false keeps clicks working. */
@SuppressLint("ClickableViewAccessibility")
internal fun View.installPressAnimation(scale: Float = 0.92f) {
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.animate().cancel()
                view.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(CameraMotion.PRESS_IN)
                    .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
                    .start()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.animate().cancel()
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(CameraMotion.PRESS_OUT)
                    .setInterpolator(OvershootInterpolator(1.15f))
                    .start()
            }
        }
        false
    }
}

/** Small selection pulse used when a chip becomes active. */
internal fun View.bump() {
    animate().cancel()
    scaleX = 0.96f
    scaleY = 0.96f
    animate()
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(CameraMotion.MORPH)
        .setInterpolator(OvershootInterpolator(1.35f))
        .start()
}

/** Blends two ARGB colours; used to morph the shutter between photo and video. */
@ColorInt
internal fun blendColor(@ColorInt from: Int, @ColorInt to: Int, fraction: Float): Int {
    val t = fraction.coerceIn(0f, 1f)
    return Color.argb(
        (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t).roundToInt(),
        (Color.red(from) + (Color.red(to) - Color.red(from)) * t).roundToInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * t).roundToInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).roundToInt()
    )
}
