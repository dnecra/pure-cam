package com.necra.purecam.core

object OrientationMath {

    fun normalizeDegrees(degrees: Int): Int = ((degrees % 360) + 360) % 360

    /** Quantizes an OrientationEventListener value to 0/90/180/270. Returns -1 when unknown. */
    fun quantizeDeviceOrientation(degrees: Int): Int {
        if (degrees == -1 || degrees < 0) return -1
        return (normalizeDegrees(degrees) + 45) / 90 % 4 * 90
    }

    /**
     * [android.view.OrientationEventListener] reports the physical, clockwise rotation of the
     * device (90° = left edge up). Camera2's preview/aspect formulas expect the display rotation,
     * which turns the other way, so it is the negation of the listener value.
     */
    fun displayRotation(deviceOrientation: Int): Int = normalizeDegrees(-deviceOrientation)

    /** Camera2 JPEG_ORIENTATION for physical orientation from OrientationEventListener. */
    fun jpegOrientation(sensorOrientation: Int, deviceOrientation: Int, facing: Facing): Int {
        val device = if (facing == Facing.FRONT) -deviceOrientation else deviceOrientation
        return normalizeDegrees(sensorOrientation + device)
    }

    /** Icons are rotated the opposite way so they stay upright while the layout is portrait. */
    fun iconRotation(deviceOrientation: Int): Float =
        -normalizeDegrees(deviceOrientation).toFloat()
}
