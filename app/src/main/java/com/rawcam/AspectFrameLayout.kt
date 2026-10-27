package com.rawcam

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * Centres a child inside the available space at a fixed width/height ratio.
 * Used for the rounded 3:4 (or 9:16, 2:3, ...) preview frame from camera-ui.html.
 */
class AspectFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var aspectRatio: Float = 3f / 4f
        set(value) {
            val safe = if (value > 0f && value.isFinite()) value else 3f / 4f
            if (field != safe) {
                field = safe
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
        val maxHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (maxWidth <= 0 || maxHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        var width = maxWidth
        var height = (width / aspectRatio).roundToInt()
        if (height > maxHeight) {
            height = maxHeight
            width = (height * aspectRatio).roundToInt()
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }
}
