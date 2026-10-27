package com.rawcam.core

data class FocusPoint(val x: Int, val y: Int)

object FocusMath {

    /**
     * Maps a tap in view coordinates to sensor coordinates.
     *
     * @param contentAspect upright aspect (width/height) of the preview content. When the view
     *   is aspect-correct this equals viewAspect; for the full-screen fill mode the content is
     *   center-cropped and this corrects the tap.
     * @param sensorOrientation SENSOR_ORIENTATION of the active lens.
     * @param crop SCALER_CROP_REGION used for the preview (already includes zoom).
     */
    fun mapTap(
        viewX: Float,
        viewY: Float,
        viewWidth: Int,
        viewHeight: Int,
        contentAspect: Float,
        sensorOrientation: Int,
        crop: RectI,
        activeArray: RectI
    ): FocusPoint {
        if (viewWidth <= 0 || viewHeight <= 0 || crop.width <= 0 || crop.height <= 0) {
            return FocusPoint(crop.centerX, crop.centerY)
        }
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
        var u = (viewX / viewWidth).coerceIn(0f, 1f)
        var v = (viewY / viewHeight).coerceIn(0f, 1f)
        if (contentAspect >= viewAspect) {
            val scale = viewAspect / contentAspect
            u = (1f - scale) / 2f + u * scale
        } else {
            val scale = contentAspect / viewAspect
            v = (1f - scale) / 2f + v * scale
        }
        val (sx, sy) = invertRotation(u, v, sensorOrientation)
        val x = (crop.left + sx * crop.width).toInt()
        val y = (crop.top + sy * crop.height).toInt()
        return FocusPoint(
            x.coerceIn(activeArray.left, activeArray.right),
            y.coerceIn(activeArray.top, activeArray.bottom)
        )
    }

    /** Inverse of the display rotation: upright (u,v) -> sensor (x,y), both normalized. */
    fun invertRotation(u: Float, v: Float, sensorOrientation: Int): Pair<Float, Float> =
        when (OrientationMath.normalizeDegrees(sensorOrientation)) {
            90 -> v to (1f - u)
            180 -> (1f - u) to (1f - v)
            270 -> (1f - v) to u
            else -> u to v
        }

    /** A metering square around the tap, about 10% of the crop, clamped to the active array. */
    fun meteringRect(point: FocusPoint, crop: RectI, activeArray: RectI): RectI {
        val halfW = (crop.width * 0.10f / 2f).toInt().coerceAtLeast(8)
        val halfH = (crop.height * 0.10f / 2f).toInt().coerceAtLeast(8)
        val left = (point.x - halfW).coerceIn(activeArray.left, activeArray.right)
        val right = (point.x + halfW).coerceIn(activeArray.left, activeArray.right)
        val top = (point.y - halfH).coerceIn(activeArray.top, activeArray.bottom)
        val bottom = (point.y + halfH).coerceIn(activeArray.top, activeArray.bottom)
        return RectI(left, top, right, bottom)
    }
}
