package com.necra.purecam

import android.content.Context
import android.hardware.SensorManager
import android.view.OrientationEventListener
import com.necra.purecam.core.OrientationMath

/**
 * Physical device rotation for EXIF orientation and upright icons. The activity itself stays
 * locked to portrait.
 */
class OrientationHelper(context: Context, private val onRotationChanged: (Int) -> Unit) {

    private var last = -1

    private val listener = object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
        override fun onOrientationChanged(orientation: Int) {
            val quantized = OrientationMath.quantizeDeviceOrientation(orientation)
            if (quantized >= 0 && quantized != last) {
                last = quantized
                onRotationChanged(quantized)
            }
        }
    }

    fun enable() {
        if (listener.canDetectOrientation()) listener.enable()
    }

    fun disable() = listener.disable()
}
