package com.rawcam.core

import kotlin.math.roundToInt

object ManualFocus {

    const val STEPS = 1000

    /** Diopters are reciprocal metres: 0 is infinity, the lens minimum is the closest focus. */
    fun dioptersAt(progress: Int, minFocusDiopters: Float): Float =
        (progress.coerceIn(0, STEPS).toFloat() / STEPS) * minFocusDiopters.coerceAtLeast(0f)

    fun progressFor(diopters: Float, minFocusDiopters: Float): Int {
        if (minFocusDiopters <= 0f) return 0
        return ((diopters / minFocusDiopters) * STEPS).roundToInt().coerceIn(0, STEPS)
    }

    /** Metric distance is only meaningful on calibrated lenses (plan section 6.14). */
    fun label(diopters: Float, calibrated: Boolean): String {
        if (!calibrated) {
            return if (diopters <= 0.0001f) "\u221e (relative)" else "relative"
        }
        if (diopters <= 0.0001f) return "\u221e"
        val metres = 1.0 / diopters
        return when {
            metres >= 100 -> "\u221e"
            metres >= 10 -> "${metres.roundToInt()} m"
            metres >= 1 -> String.format("%.1f m", metres)
            metres >= 0.1 -> String.format("%.2f m", metres)
            else -> "${(metres * 100).roundToInt()} cm"
        }
    }

    fun focusAssistZoom(baseZoom: Float): Float = (baseZoom * 3f).coerceAtLeast(3f)
}
