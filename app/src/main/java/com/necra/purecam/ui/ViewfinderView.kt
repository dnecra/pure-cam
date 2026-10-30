package com.necra.purecam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.view.TextureView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Rounded, aspect-correct preview card. It measures itself as the largest [aspect] rectangle that
 * fits the space its parent offers, so the camera frame always stays centred and undistorted.
 */
internal class AspectFrame(context: Context, private val palette: CameraPalette) : FrameLayout(context) {
    private val radius = context.dp(CameraSize.CARD_RADIUS).toFloat()
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1).toFloat()
        color = palette.withAlpha(palette.onSurface, 0.16f)
    }

    var aspect: Float = 3f / 4f
        set(value) {
            val next = value.coerceIn(0.2f, 5f)
            if (abs(next - field) < 0.0001f) return
            field = next
            requestLayout()
            invalidate()
        }

    init {
        setBackgroundColor(Color.BLACK)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
        val maxHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (maxWidth <= 0 || maxHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val frameWidth: Int
        val frameHeight: Int
        if (maxWidth.toFloat() / maxHeight > aspect) {
            frameHeight = maxHeight
            frameWidth = (frameHeight * aspect).roundToInt()
        } else {
            frameWidth = maxWidth
            frameHeight = (frameWidth / aspect).roundToInt()
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(frameWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(frameHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val inset = border.strokeWidth / 2f
        canvas.drawRoundRect(
            inset, inset, width - inset, height - inset,
            radius, radius, border
        )
    }
}

/** Preview surface that keeps a centre-crop transform in sync with its own layout. */
internal class PreviewTexture(context: Context) : TextureView(context) {
    var contentAspect: Float? = null
        set(value) {
            if (field == value) return
            field = value
            applyTransform()
        }

    fun applyTransform() {
        val aspect = contentAspect ?: return
        if (width <= 0 || height <= 0 || !aspect.isFinite() || aspect <= 0f) return
        val viewAspect = width.toFloat() / height
        val cx = width / 2f
        val cy = height / 2f
        val matrix = Matrix().apply {
            if (aspect >= viewAspect) setScale(aspect / viewAspect, 1f, cx, cy)
            else setScale(1f, viewAspect / aspect, cx, cy)
        }
        setTransform(matrix)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyTransform()
    }
}

/** Thirds grid; decorative only, so it is hidden from accessibility services. */
internal class GridView(context: Context, palette: CameraPalette) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.withAlpha(palette.onSurface, 0.28f)
        strokeWidth = context.dp(1).toFloat()
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawLine(w / 3f, 0f, w / 3f, h, paint)
        canvas.drawLine(w * 2f / 3f, 0f, w * 2f / 3f, h, paint)
        canvas.drawLine(0f, h / 3f, w, h / 3f, paint)
        canvas.drawLine(0f, h * 2f / 3f, w, h * 2f / 3f, paint)
    }
}

/** Tap-to-focus ring that fades out on its own. */
internal class FocusRingView(context: Context, palette: CameraPalette) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.onSurface
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.5f)
    }
    private var focusX = 0f
    private var focusY = 0f
    private val hide = Runnable {
        animate().alpha(0f).setDuration(200).withEndAction { visibility = GONE }.start()
    }

    init {
        visibility = GONE
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun showAt(x: Float, y: Float) {
        focusX = x
        focusY = y
        removeCallbacks(hide)
        animate().cancel()
        visibility = VISIBLE
        alpha = 0f
        scaleX = 1.3f
        scaleY = 1.3f
        invalidate()
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160)
            .withEndAction { postDelayed(hide, 650) }
            .start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = context.dp(28).toFloat()
        canvas.drawCircle(focusX, focusY, radius, paint)
        canvas.drawCircle(focusX, focusY, context.dp(3).toFloat(), paint)
    }
}
