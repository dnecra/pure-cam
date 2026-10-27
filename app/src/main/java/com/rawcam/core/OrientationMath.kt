package com.rawcam.core

object OrientationMath {

    fun normalizeDegrees(degrees: Int): Int = ((degrees % 360) + 360) % 360

    /** Quantizes an OrientationEventListener value to 0/90/180/270. Returns -1 when unknown. */
    fun quantizeDeviceOrientation(degrees: Int): Int {
        if (degrees == -1 || degrees < 0) return -1
        return (normalizeDegrees(degrees) + 45) / 90 % 4 * 90
    }

    /** Plan section 6.11. */
    fun jpegOrientation(sensorOrientation: Int, deviceRotation: Int, frontFacing: Boolean): Int {
        val sensor = normalizeDegrees(sensorOrientation)
        val device = normalizeDegrees(deviceRotation)
        return if (frontFacing) {
            (sensor + device) % 360
        } else {
            (sensor - device + 360) % 360
        }
    }

    /** Icons are rotated the opposite way so they stay upright while the layout is portrait. */
    fun iconRotation(deviceRotation: Int): Float = -normalizeDegrees(deviceRotation).toFloat()
}
