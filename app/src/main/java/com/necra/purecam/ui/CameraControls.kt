package com.necra.purecam.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.necra.purecam.R
import kotlin.math.min

private fun activeColors(@androidx.annotation.ColorInt active: Int, @androidx.annotation.ColorInt inactive: Int): ColorStateList =
    ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_selected),
            intArrayOf()
        ),
        intArrayOf(active, active, inactive)
    )

private fun View.haptic() {
    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
}

private val mediumTypeface: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

/** Circular 48 dp icon button used by the shutter row and, when [container] is false, toolbar bars. */
internal fun cameraIconButton(
    context: Context,
    palette: CameraPalette,
    @DrawableRes icon: Int,
    description: String,
    container: Boolean = true,
    onClick: () -> Unit
): MaterialButton = MaterialButton(context).apply {
    text = ""
    minWidth = 0
    minimumWidth = 0
    minHeight = 0
    minimumHeight = 0
    insetLeft = 0
    insetTop = 0
    insetRight = 0
    insetBottom = 0
    setPadding(0, 0, 0, 0)
    cornerRadius = context.dp(CameraSize.TOUCH / 2)
    iconSize = context.dp(CameraSize.ICON - 2)
    iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
    iconPadding = 0
    setIconResource(icon)
    iconTint = activeColors(palette.onPrimary, palette.onSurfaceVariant)
    backgroundTintList = activeColors(
        palette.primaryContainer,
        if (container) palette.surfaceContainerHigh else android.graphics.Color.TRANSPARENT
    )
    contentDescription = description
    setOnClickListener {
        haptic()
        onClick()
    }
    installPressAnimation()
}

/**
 * Toolbar action: transparent by default, tonal when active. Used inside the floating top capsule
 * so the capsule itself provides the surface instead of a row of identical grey circles.
 */
internal fun cameraToolbarButton(
    context: Context,
    palette: CameraPalette,
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit
): MaterialButton = MaterialButton(context).apply {
    text = ""
    minWidth = 0
    minimumWidth = 0
    minHeight = 0
    minimumHeight = 0
    insetLeft = 0
    insetTop = 0
    insetRight = 0
    insetBottom = 0
    setPadding(0, 0, 0, 0)
    cornerRadius = context.dp(CameraSize.TOUCH / 2)
    iconSize = context.dp(22)
    iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
    iconPadding = 0
    setIconResource(icon)
    iconTint = activeColors(palette.onPrimaryContainer, palette.onSurfaceVariant)
    backgroundTintList = activeColors(palette.primaryContainer, android.graphics.Color.TRANSPARENT)
    setTextColor(activeColors(palette.onPrimaryContainer, palette.onSurfaceVariant))
    contentDescription = description
    setOnClickListener {
        haptic()
        onClick()
    }
    installPressAnimation()
}

/** Pill-shaped text button used for the capture summary and lens chips. */
internal fun cameraChip(
    context: Context,
    palette: CameraPalette,
    text: String,
    description: String,
    minWidthDp: Int = CameraSize.TOUCH,
    textSizeSp: Float = 12f,
    onClick: (() -> Unit)? = null
): MaterialButton = MaterialButton(context).apply {
    this.text = text
    textSize = textSizeSp
    typeface = mediumTypeface
    isAllCaps = false
    letterSpacing = -0.01f
    minWidth = context.dp(minWidthDp)
    minimumWidth = context.dp(minWidthDp)
    minHeight = 0
    minimumHeight = 0
    insetLeft = 0
    insetTop = 0
    insetRight = 0
    insetBottom = 0
    setPadding(context.dp(CameraSpace.M), 0, context.dp(CameraSpace.M), 0)
    cornerRadius = context.dp(CameraSize.TOUCH / 2)
    backgroundTintList = activeColors(palette.primaryContainer, palette.surfaceContainerHigh)
    setTextColor(activeColors(palette.onPrimaryContainer, palette.onSurface))
    iconTint = activeColors(palette.onPrimaryContainer, palette.onSurface)
    iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
    iconPadding = context.dp(CameraSpace.XS)
    iconSize = context.dp(18)
    strokeWidth = context.dp(1)
    strokeColor = activeColors(palette.primary, palette.withAlpha(palette.outlineVariant, 0.55f))
    contentDescription = description
    setOnClickListener {
        haptic()
        onClick?.invoke()
    }
    installPressAnimation(0.95f)
}

/** Segment of a [com.google.android.material.button.MaterialButtonToggleGroup]. */
internal fun cameraSegmentButton(
    context: Context,
    palette: CameraPalette,
    text: String?,
    description: String,
    @DrawableRes icon: Int? = null
): MaterialButton = MaterialButton(context).apply {
    this.text = text
    textSize = 14f
    typeface = mediumTypeface
    isAllCaps = false
    isCheckable = true
    minWidth = 0
    minimumWidth = 0
    minHeight = 0
    minimumHeight = 0
    insetLeft = 0
    insetTop = 0
    insetRight = 0
    insetBottom = 0
    val horizontal = if (text == null) 0 else context.dp(CameraSpace.S)
    setPadding(horizontal, 0, horizontal, 0)
    cornerRadius = context.dp(CameraSize.TOUCH / 2)
    icon?.let {
        setIconResource(it)
        iconSize = context.dp(20)
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        iconPadding = if (text == null) 0 else context.dp(CameraSpace.XS)
    }
    backgroundTintList = activeColors(palette.primaryContainer, android.graphics.Color.TRANSPARENT)
    setTextColor(activeColors(palette.onPrimaryContainer, palette.onSurfaceVariant))
    iconTint = activeColors(palette.onPrimaryContainer, palette.onSurfaceVariant)
    contentDescription = description
    installPressAnimation(0.95f)
}

internal fun MaterialButton.setActive(active: Boolean, enabled: Boolean = true, state: String? = null) {
    isSelected = active
    isEnabled = enabled
    alpha = if (enabled) 1f else 0.38f
    if (state != null) stateDescription = state
}

/**
 * Camera shutter. Photo mode is a light circle, video mode a red one; recording turns the fill
 * into a stop square and a busy capture draws an arc. The fill colour morphs between modes.
 */
internal class ShutterButton(context: Context, private val palette: CameraPalette) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = palette.withAlpha(palette.onSurface, 0.85f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private var video = false
    private var recording = false
    private var busy = false
    private var videoProgress = 0f
    private var modeAnimator: ValueAnimator? = null

    init {
        installPressAnimation(0.9f)
    }

    fun setState(videoMode: Boolean, isRecording: Boolean, isBusy: Boolean) {
        if (video == videoMode && recording == isRecording && busy == isBusy) return
        if (video != videoMode) animateMode(videoMode)
        video = videoMode
        recording = isRecording
        busy = isBusy
        invalidate()
    }

    private fun animateMode(toVideo: Boolean) {
        modeAnimator?.cancel()
        val from = videoProgress
        val to = if (toVideo) 1f else 0f
        modeAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = CameraMotion.MORPH
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                videoProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f
        canvas.drawCircle(cx, cy, radius - 4f * density, ring)

        fill.color = blendColor(palette.onSurface, palette.record, videoProgress)
        canvas.drawCircle(cx, cy, radius - 10f * density, fill)

        if (recording) {
            mark.color = android.graphics.Color.WHITE
            val side = 10f * density
            canvas.drawRoundRect(
                cx - side, cy - side, cx + side, cy + side,
                4f * density, 4f * density, mark
            )
        } else if (busy) {
            arc.color = if (video) android.graphics.Color.WHITE else palette.surface
            canvas.drawArc(
                cx - 17f * density, cy - 17f * density,
                cx + 17f * density, cy + 17f * density,
                -90f, 275f, false, arc
            )
        }
    }
}

/**
 * Icon-only Photo/Video switch with a sliding tonal indicator. The two icons and the pill animates
 * between halves, giving the mode change a visible, springy transition without adding text.
 */
internal class ModeSwitchView(
    context: Context,
    private val palette: CameraPalette,
    private val onModeSelected: (video: Boolean) -> Unit
) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val indicator = Paint(Paint.ANTI_ALIAS_FLAG)
    private val photoIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_camera)?.mutate()
    private val videoIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_video)?.mutate()
    private val indicatorRect = RectF()

    private var video = false
    private var progress = 0f
    private var videoAvailable = true
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.mode_switch)
        installPressAnimation(0.94f)
    }

    fun setMode(video: Boolean, canSwitch: Boolean, videoAvailable: Boolean, animate: Boolean) {
        this.videoAvailable = videoAvailable
        isEnabled = canSwitch
        alpha = if (canSwitch) 1f else 0.38f
        val target = if (video) 1f else 0f
        if (this.video != video) {
            this.video = video
            if (animate) animateTo(target) else setProgress(target)
        } else if (animator == null) {
            setProgress(target)
        }
        stateDescription = context.getString(
            if (video) R.string.mode_video else R.string.mode_photo
        )
        invalidate()
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = CameraMotion.MORPH
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    animator = null
                }
            })
            start()
        }
    }

    private fun setProgress(value: Float) {
        progress = value
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(context.dp(116), widthMeasureSpec),
            resolveSize(context.dp(CameraSize.TOUCH), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = 4f * density
        val radius = height / 2f
        track.color = palette.withAlpha(palette.onSurface, 0.10f)
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, radius, radius, track)

        val half = (width - inset * 2f) / 2f
        val left = inset + progress.coerceIn(0f, 1f) * half
        indicatorRect.set(left, inset, left + half, height - inset)
        indicator.color = palette.primaryContainer
        canvas.drawRoundRect(indicatorRect, radius - inset, radius - inset, indicator)

        drawIcon(canvas, photoIcon, 0, palette.onPrimaryContainer, palette.onSurfaceVariant)
        drawIcon(canvas, videoIcon, 1, palette.onPrimaryContainer, palette.onSurfaceVariant)
    }

    private fun drawIcon(
        canvas: Canvas,
        icon: Drawable?,
        half: Int,
        selectedColor: Int,
        idleColor: Int
    ) {
        icon ?: return
        val selected = (progress >= 0.5f) == (half == 1)
        icon.setTint(if (selected) selectedColor else idleColor)
        val size = context.dp(21)
        val cx = width / 4f + half * width / 2f
        val cy = height / 2f
        icon.setBounds(
            (cx - size / 2f).toInt(), (cy - size / 2f).toInt(),
            (cx + size / 2f).toInt(), (cy + size / 2f).toInt()
        )
        icon.draw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            if (!isPressed) return false
            isPressed = false
            performClick()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            isPressed = true
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            isPressed = false
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        if (!isEnabled) return false
        val next = when {
            video -> false
            videoAvailable -> true
            else -> return false
        }
        haptic()
        video = next
        animateTo(if (next) 1f else 0f)
        stateDescription = context.getString(if (next) R.string.mode_video else R.string.mode_photo)
        onModeSelected(next)
        return super.performClick()
    }
}

/** Red dot + monospaced clock shown while recording. */
internal class RecordingPill(context: Context, palette: CameraPalette) : LinearLayout(context) {
    private val dot = View(context)
    private val clock = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        background = context.pill(palette.withAlpha(palette.surfaceContainerHighest, 0.92f), CameraSize.RECORDING_PILL / 2, 1, palette.withAlpha(palette.outlineVariant, 0.6f))
        setPadding(context.dp(CameraSpace.M), 0, context.dp(CameraSpace.M), 0)
        dot.background = context.pill(palette.record, 4)
        addView(dot, LayoutParams(context.dp(8), context.dp(8)).apply { rightMargin = context.dp(CameraSpace.S) })
        clock.apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(palette.onSurface)
            typeface = Typeface.MONOSPACE
            text = context.getString(R.string.recording_time, 0, 0)
        }
        addView(clock)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setElapsed(seconds: Int) {
        clock.text = getContext().getString(R.string.recording_time, seconds / 60, seconds % 60)
    }

    fun pulse() {
        dot.alpha = if (dot.alpha == 1f) 0.35f else 1f
    }
}
