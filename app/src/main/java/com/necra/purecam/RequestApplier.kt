package com.necra.purecam

import android.graphics.Rect
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.util.Rational
import android.util.Size
import com.necra.purecam.core.AeMode
import com.necra.purecam.core.AfMode
import com.necra.purecam.core.CaptureIntent
import com.necra.purecam.core.RectI
import com.necra.purecam.core.RequestSpec
import com.necra.purecam.core.TonemapSetting
import com.necra.purecam.core.WbPreset
import com.necra.purecam.core.WbSetting
import kotlin.math.roundToInt

/** Translates a pure RequestSpec into Camera2 keys. */
object RequestApplier {

    fun apply(
        builder: CaptureRequest.Builder,
        spec: RequestSpec,
        jpegOrientation: Int,
        aeRegion: RectI?,
        afRegion: RectI?,
        /** True when the request targets the JPEG reader, whatever its capture intent. */
        jpegTarget: Boolean = false
    ) {
        val still = spec.intent == CaptureIntent.STILL || jpegTarget

        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(
            CaptureRequest.CONTROL_CAPTURE_INTENT,
            when (spec.intent) {
                CaptureIntent.STILL -> CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE
                CaptureIntent.RECORD -> CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_RECORD
                CaptureIntent.PREVIEW -> CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW
            }
        )
        builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_OFF)

        // ---- Exposure ---------------------------------------------------------------
        when (spec.aeMode) {
            AeMode.OFF -> builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            AeMode.ALWAYS_FLASH ->
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
            AeMode.AUTO_FLASH ->
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH)
            AeMode.AUTO -> builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        }
        when {
            spec.torch -> builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
            spec.flashSingle -> builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
            // With an AE-managed flash mode the flash is controlled by AE; writing
            // FLASH_MODE here is ignored per the API contract and confuses some HALs,
            // so leave it out entirely.
            spec.aeMode == AeMode.AUTO_FLASH || spec.aeMode == AeMode.ALWAYS_FLASH -> Unit
            else -> builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        }
        spec.aeFps?.let {
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(it.min, it.max))
        }
        if (spec.aeMode != AeMode.OFF) {
            spec.exposureCompensation?.let {
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it)
            }
        }
        spec.iso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
        spec.exposureNs?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
        spec.frameDurationNs?.let { builder.set(CaptureRequest.SENSOR_FRAME_DURATION, it) }
        spec.postRawBoost?.let { builder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, it) }

        // ---- Focus ------------------------------------------------------------------
        when (spec.afMode) {
            AfMode.CONTINUOUS_PICTURE ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            AfMode.AUTO -> builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            AfMode.OFF -> builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        }
        spec.focusDistance?.let { builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }

        // ---- White balance ----------------------------------------------------------
        when (val wb = spec.wb) {
            is WbSetting.Auto ->
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            is WbSetting.Preset -> {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode(wb.preset))
                builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_FAST)
            }
            is WbSetting.Kelvin -> {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                builder.set(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
                )
                builder.set(
                    CaptureRequest.COLOR_CORRECTION_GAINS,
                    RggbChannelVector(wb.gains[0], wb.gains[1], wb.gains[2], wb.gains[3])
                )
                builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, wb.transform.toColorSpaceTransform())
            }
        }

        // ---- Optional processing: all off ------------------------------------------
        spec.noiseReductionOff?.let {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
        }
        spec.edgeOff?.let {
            builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
        }
        spec.hotPixelOff?.let {
            builder.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_OFF)
        }
        spec.aberrationOff?.let {
            builder.set(
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF
            )
        }
        spec.distortionOff?.let {
            builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_OFF)
        }
        spec.videoStabilizationOff?.let {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
        }
        when (spec.tonemap) {
            TonemapSetting.SRGB_PRESET -> {
                builder.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_PRESET_CURVE)
                builder.set(CaptureRequest.TONEMAP_PRESET_CURVE, CameraMetadata.TONEMAP_PRESET_CURVE_SRGB)
            }
            TonemapSetting.FAST -> builder.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_FAST)
        }

        // ---- Geometry ---------------------------------------------------------------
        builder.set(CaptureRequest.SCALER_CROP_REGION, spec.crop.toRect())
        spec.zoomRatio?.let { builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, it) }

        if (spec.afMode != AfMode.OFF) {
            afRegion?.let {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(MeteringRectangle(it.toRect(), 1000)))
            }
        }
        if (spec.aeMode != AeMode.OFF) {
            aeRegion?.let {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(MeteringRectangle(it.toRect(), 1000)))
            }
        }

        if (still) {
            builder.set(CaptureRequest.JPEG_QUALITY, spec.jpegQuality.toByte())
            builder.set(
                CaptureRequest.JPEG_THUMBNAIL_SIZE,
                Size(spec.thumbnailSize.width, spec.thumbnailSize.height)
            )
            builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
        }
    }

    internal fun awbMode(preset: WbPreset): Int = when (preset) {
        WbPreset.INCANDESCENT -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
        WbPreset.FLUORESCENT -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
        WbPreset.DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
        WbPreset.CLOUDY_DAYLIGHT -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        WbPreset.TWILIGHT -> CameraMetadata.CONTROL_AWB_MODE_TWILIGHT
        WbPreset.MOONLIGHT -> CameraMetadata.CONTROL_AWB_MODE_SHADE
    }

    private fun RectI.toRect(): Rect = Rect(left, top, right, bottom)

    private fun FloatArray.toColorSpaceTransform(): ColorSpaceTransform {
        val elements = Array(9) { i ->
            val value = if (i < size) this[i] else 0f
            Rational((value * 10000f).roundToInt(), 10000)
        }
        return ColorSpaceTransform(elements)
    }
}
