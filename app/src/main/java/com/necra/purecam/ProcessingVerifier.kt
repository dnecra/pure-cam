package com.necra.purecam

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.os.SystemClock
import android.util.Log
import com.necra.purecam.core.AeMode
import com.necra.purecam.core.AfMode
import com.necra.purecam.core.LensProfile
import com.necra.purecam.core.RequestSpec
import com.necra.purecam.core.TonemapSetting
import com.necra.purecam.core.WbSetting
import kotlin.math.abs

data class LiveReadout(
    val iso: Int?,
    val shutterNs: Long?,
    val focusDiopters: Float?,
    val aeState: Int?,
    val afState: Int?,
    val lensState: Int?,
    val awbMode: Int?,
    val gains: FloatArray?,
    val transform: FloatArray?,
    val activePhysicalId: String?,
    val flashState: Int?,
    val timestampMs: Long = SystemClock.elapsedRealtime()
)

object ProcessingVerifier {

    private const val TAG = "PureCam/Verify"

    fun read(result: TotalCaptureResult): LiveReadout = LiveReadout(
        iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
        shutterNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
        focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
        aeState = result.get(CaptureResult.CONTROL_AE_STATE),
        afState = result.get(CaptureResult.CONTROL_AF_STATE),
        lensState = result.get(CaptureResult.LENS_STATE),
        awbMode = result.get(CaptureResult.CONTROL_AWB_MODE),
        gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.toFloatArray(),
        transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.toFloatArray(),
        activePhysicalId = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID),
        flashState = result.get(CaptureResult.FLASH_STATE)
    )

    /**
     * Compares what the device reported with what was requested. Returns human-readable
     * mismatches; every mismatch is also logged. Used by the capability report dialog.
     */
    fun verify(result: TotalCaptureResult, spec: RequestSpec, profile: LensProfile): List<String> {
        val issues = ArrayList<String>()

        fun checkOff(requested: Boolean?, actual: Int?, offValue: Int, name: String) {
            if (requested != true) return
            if (actual != offValue) issues.add("$name: requested OFF, device reported ${actual ?: "null"}")
        }

        checkOff(spec.noiseReductionOff, result.get(CaptureResult.NOISE_REDUCTION_MODE), CameraMetadata.NOISE_REDUCTION_MODE_OFF, "Noise reduction")
        checkOff(spec.edgeOff, result.get(CaptureResult.EDGE_MODE), CameraMetadata.EDGE_MODE_OFF, "Edge/sharpening")
        checkOff(spec.hotPixelOff, result.get(CaptureResult.HOT_PIXEL_MODE), CameraMetadata.HOT_PIXEL_MODE_OFF, "Hot pixel")
        checkOff(
            spec.aberrationOff,
            result.get(CaptureResult.COLOR_CORRECTION_ABERRATION_MODE),
            CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF,
            "Chromatic aberration"
        )
        checkOff(
            spec.distortionOff,
            result.get(CaptureResult.DISTORTION_CORRECTION_MODE),
            CameraMetadata.DISTORTION_CORRECTION_MODE_OFF,
            "Distortion correction"
        )
        checkOff(
            spec.videoStabilizationOff,
            result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE),
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            "Video stabilization"
        )

        val expectedTonemap = when (spec.tonemap) {
            TonemapSetting.SRGB_PRESET -> CameraMetadata.TONEMAP_MODE_PRESET_CURVE
            TonemapSetting.FAST -> CameraMetadata.TONEMAP_MODE_FAST
        }
        val actualTonemap = result.get(CaptureResult.TONEMAP_MODE)
        if (actualTonemap != null && actualTonemap != expectedTonemap) {
            issues.add("Tonemap: requested $expectedTonemap, device reported $actualTonemap")
        }

        if (spec.manualExposure) {
            spec.iso?.let { expected ->
                val actual = result.get(CaptureResult.SENSOR_SENSITIVITY)
                if (actual != null && !closeEnoughInt(actual, expected)) {
                    issues.add("ISO: requested $expected, device reported $actual")
                }
            }
            spec.exposureNs?.let { expected ->
                val actual = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                if (actual != null && !closeEnoughLong(actual, expected)) {
                    issues.add("Shutter: requested $expected ns, device reported $actual ns")
                }
            }
        }

        spec.focusDistance?.let { expected ->
            val actual = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            if (actual != null && abs(actual - expected) > 0.02f) {
                issues.add("Focus: requested $expected diopters, device reported $actual")
            }
        }
        if (spec.afMode == AfMode.OFF) {
            val actualAf = result.get(CaptureResult.CONTROL_AF_MODE)
            if (actualAf != null && actualAf != CameraMetadata.CONTROL_AF_MODE_OFF) {
                issues.add("AF: requested OFF, device reported ${afName(actualAf)}")
            }
        }

        if (spec.aeMode == AeMode.OFF) {
            val actualAe = result.get(CaptureResult.CONTROL_AE_MODE)
            if (actualAe != null && actualAe != CameraMetadata.CONTROL_AE_MODE_OFF) {
                issues.add("AE: requested OFF, device reported ${aeName(actualAe)}")
            }
        }

        val actualAwb = result.get(CaptureResult.CONTROL_AWB_MODE)
        when (val wb = spec.wb) {
            is WbSetting.Auto -> {
                if (actualAwb != null && actualAwb != CameraMetadata.CONTROL_AWB_MODE_AUTO) {
                    issues.add("White balance: requested AUTO, device reported ${awbName(actualAwb)}")
                }
            }
            is WbSetting.Preset -> {
                val expected = RequestApplier.awbMode(wb.preset)
                if (actualAwb != null && actualAwb != expected) {
                    issues.add(
                        "White balance: requested ${wb.preset.name}, device reported ${awbName(actualAwb)}"
                    )
                }
            }
            is WbSetting.Kelvin -> {
                if (actualAwb != null && actualAwb != CameraMetadata.CONTROL_AWB_MODE_OFF) {
                    issues.add(
                        "White balance: requested manual gains (AWB OFF), device reported ${awbName(actualAwb)}"
                    )
                }
                val actualCorrection = result.get(CaptureResult.COLOR_CORRECTION_MODE)
                if (actualCorrection != null &&
                    actualCorrection != CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
                ) {
                    issues.add(
                        "Color correction: requested TRANSFORM_MATRIX, device reported ${modeName(actualCorrection)}"
                    )
                }
            }
        }

        profile.physicalId?.let { expectedPhysical ->
            val actual = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
            if (actual != null && actual != expectedPhysical && spec.needPrecapture.not()) {
                // Strategy C can legitimately choose another lens; this is a warning, not a failure.
                issues.add("Lens: selected physical $expectedPhysical, camera used $actual")
            }
        }

        if (issues.isNotEmpty()) {
            issues.forEach { Log.w(TAG, it) }
            Log.w(TAG, summary(result))
        } else {
            Log.i(TAG, "All requested settings honored. ${summary(result)}")
        }
        return issues
    }

    fun summary(result: TotalCaptureResult): String {
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val shutter = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
        val physical = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        return buildString {
            append("NR=${modeName(result.get(CaptureResult.NOISE_REDUCTION_MODE))} ")
            append("EDGE=${modeName(result.get(CaptureResult.EDGE_MODE))} ")
            append("HOT=${modeName(result.get(CaptureResult.HOT_PIXEL_MODE))} ")
            append("CA=${modeName(result.get(CaptureResult.COLOR_CORRECTION_ABERRATION_MODE))} ")
            append("DIST=${modeName(result.get(CaptureResult.DISTORTION_CORRECTION_MODE))} ")
            append("STAB=${modeName(result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE))} ")
            append("TONEMAP=${modeName(result.get(CaptureResult.TONEMAP_MODE))} ")
            append("CCM=${ccmName(result.get(CaptureResult.COLOR_CORRECTION_MODE))} ")
            append("AE=${aeName(result.get(CaptureResult.CONTROL_AE_MODE))} ")
            append("FLASH=${flashName(result.get(CaptureResult.FLASH_STATE))} ")
            append("AESTATE=${aeStateName(result.get(CaptureResult.CONTROL_AE_STATE))} ")
            append("ISO=$iso SHUTTER=$shutter FOCUS=$focus ")
            append("AF=${afName(result.get(CaptureResult.CONTROL_AF_MODE))} ")
            append("AWB=${awbName(result.get(CaptureResult.CONTROL_AWB_MODE))} ")
            append("PHYS=${physical ?: "-"}")
        }
    }

    private fun modeName(value: Int?): String = when (value) {
        null -> "?"
        0 -> "OFF"
        1 -> "FAST"
        2 -> "HQ"
        else -> value.toString()
    }

    private fun ccmName(value: Int?): String = when (value) {
        null -> "?"
        CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX -> "TRANSFORM_MATRIX"
        CameraMetadata.COLOR_CORRECTION_MODE_FAST -> "FAST"
        CameraMetadata.COLOR_CORRECTION_MODE_HIGH_QUALITY -> "HIGH_QUALITY"
        else -> value.toString()
    }

    private fun aeName(value: Int?): String = when (value) {
        null -> "?"
        CameraMetadata.CONTROL_AE_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_AE_MODE_ON -> "ON"
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH -> "AUTO_FLASH"
        CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> "ALWAYS_FLASH"
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE -> "AUTO_FLASH_REDEYE"
        CameraMetadata.CONTROL_AE_MODE_ON_EXTERNAL_FLASH -> "EXTERNAL_FLASH"
        else -> value.toString()
    }

    private fun flashName(value: Int?): String = when (value) {
        null -> "?"
        CameraMetadata.FLASH_STATE_UNAVAILABLE -> "UNAVAILABLE"
        CameraMetadata.FLASH_STATE_CHARGING -> "CHARGING"
        CameraMetadata.FLASH_STATE_READY -> "READY"
        CameraMetadata.FLASH_STATE_FIRED -> "FIRED"
        CameraMetadata.FLASH_STATE_PARTIAL -> "PARTIAL"
        else -> value.toString()
    }

    private fun aeStateName(value: Int?): String = when (value) {
        null -> "?"
        CameraMetadata.CONTROL_AE_STATE_INACTIVE -> "INACTIVE"
        CameraMetadata.CONTROL_AE_STATE_SEARCHING -> "SEARCHING"
        CameraMetadata.CONTROL_AE_STATE_CONVERGED -> "CONVERGED"
        CameraMetadata.CONTROL_AE_STATE_LOCKED -> "LOCKED"
        CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED -> "FLASH_REQUIRED"
        CameraMetadata.CONTROL_AE_STATE_PRECAPTURE -> "PRECAPTURE"
        else -> value.toString()
    }

    private fun afName(value: Int?): String = when (value) {
        null -> "?"
        CameraMetadata.CONTROL_AF_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_AF_MODE_AUTO -> "AUTO"
        CameraMetadata.CONTROL_AF_MODE_MACRO -> "MACRO"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONTINUOUS_VIDEO"
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONTINUOUS_PICTURE"
        CameraMetadata.CONTROL_AF_MODE_EDOF -> "EDOF"
        else -> value.toString()
    }

    private fun awbName(value: Int?): String = when (value) {
        null -> "?"
        CameraMetadata.CONTROL_AWB_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_AWB_MODE_AUTO -> "AUTO"
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "INCANDESCENT"
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "FLUORESCENT"
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "WARM_FLUORESCENT"
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "DAYLIGHT"
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "CLOUDY_DAYLIGHT"
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> "TWILIGHT"
        CameraMetadata.CONTROL_AWB_MODE_SHADE -> "SHADE"
        else -> value.toString()
    }

    /** Devices quantize ISO; a 1% difference is reporting precision, not a ignored setting. */
    private fun closeEnoughInt(actual: Int, expected: Int): Boolean {
        val delta = abs(actual - expected)
        return delta <= (expected / 100).coerceAtLeast(1)
    }

    private fun closeEnoughLong(actual: Long, expected: Long): Boolean {
        val delta = abs(actual - expected)
        return delta <= (expected / 100).coerceAtLeast(1_000L)
    }

    private fun ColorSpaceTransform.toFloatArray(): FloatArray {
        val e = IntArray(18)
        copyElements(e, 0)
        return FloatArray(9) { i ->
            val d = e[i * 2 + 1]
            if (d == 0) 0f else e[i * 2].toFloat() / d
        }
    }

    private fun RggbChannelVector.toFloatArray(): FloatArray =
        floatArrayOf(red, greenEven, greenOdd, blue)
}
