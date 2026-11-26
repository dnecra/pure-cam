package com.necra.purecam.core

enum class CaptureIntent { PREVIEW, STILL, RECORD }

enum class AeMode { AUTO, AUTO_FLASH, ALWAYS_FLASH, OFF }

enum class AfMode { CONTINUOUS_PICTURE, AUTO, OFF }

enum class TonemapSetting { SRGB_PRESET, FAST }

sealed interface WbSetting {
    data object Auto : WbSetting
    data class Preset(val preset: WbPreset) : WbSetting
    data class Kelvin(val gains: FloatArray, val transform: FloatArray) : WbSetting
}

/**
 * A fully resolved set of Camera2 settings. Producing this is pure; applying it to a
 * CaptureRequest.Builder happens in the Android layer.
 */
data class RequestSpec(
    val intent: CaptureIntent,
    val aeMode: AeMode,
    val flashSingle: Boolean,
    /** Continuous light for video; stills never use it. */
    val torch: Boolean,
    val aeFps: RangeI?,
    val afMode: AfMode,
    val focusDistance: Float?,
    val wb: WbSetting,
    val noiseReductionOff: Boolean?,
    val edgeOff: Boolean?,
    val hotPixelOff: Boolean?,
    val aberrationOff: Boolean?,
    val distortionOff: Boolean?,
    val videoStabilizationOff: Boolean?,
    val tonemap: TonemapSetting,
    val iso: Int?,
    val exposureNs: Long?,
    /** Auto-exposure compensation in device steps; null when AE is off. */
    val exposureCompensation: Int?,
    val frameDurationNs: Long?,
    val postRawBoost: Int?,
    val zoomRatio: Float?,
    val crop: RectI,
    val jpegQuality: Int,
    val thumbnailSize: SizeI,
    val needPrecapture: Boolean,
    val manualExposure: Boolean,
    val approximatePreview: Boolean,
    val relativeZoom: Float
)

object NoProcessingFlags {
    const val JPEG_QUALITY = 100
    val THUMBNAIL_SIZE = SizeI(0, 0)
    const val POST_RAW_BOOST = 100
}

object RequestBuilder {

    /**
     * @param outputAspect aspect of the JPEG buffer chosen for this session (width/height).
     * @param strategy the lens strategy actually in use; a PHYSICAL lens can fall back to LOGICAL.
     * @param previewClamp true for preview requests, where long exposures are shortened.
     * @param focusAssist true while the focus slider is being dragged (preview only).
     * @param fallbackTransform last COLOR_CORRECTION_TRANSFORM seen from auto AWB.
     * @param videoFps recording frame rate; required for [CaptureIntent.RECORD] so the sensor
     *   runs at exactly that rate and manual exposure is shortened to fit one frame.
     * @param aeFpsRange AE target range advertised by the camera, when available. Preview and
     *   still requests use it to keep the exposure headroom appropriate for their intent.
     * @param videoMode true while the screen is in video mode; the flash becomes a steady torch.
     * @param flashMetering true for the one-shot precapture request: use AE ON and
     *   FLASH_MODE_SINGLE, matching the final still request so the HAL meters the same flash
     *   configuration it will use for the photo. Normal preview only arms AUTO_FLASH and never
     *   forces the LED.
     */
    fun build(
        state: CameraState,
        profile: LensProfile,
        intent: CaptureIntent,
        outputAspect: Float,
        strategy: LensMode,
        previewClamp: Boolean = true,
        focusAssist: Boolean = false,
        fallbackTransform: FloatArray? = null,
        videoFps: Int? = null,
        aeFpsRange: RangeI? = null,
        videoMode: Boolean = false,
        exposureCompensation: Int? = null,
        flashMetering: Boolean = false,
        lateFlashBehavior: Boolean = false,
        jpegQuality: Int = NoProcessingFlags.JPEG_QUALITY,
        thumbnailSize: SizeI? = null
    ): RequestSpec {
        val manual = state.exposure as? ExposureMode.Manual
        val useManual = manual != null && profile.supportsManualExposure
        val flashOn = state.flash && profile.flashAvailable
        val preview = intent == CaptureIntent.PREVIEW
        val record = intent == CaptureIntent.RECORD
        val aeManagedFlashMode = when {
            profile.alwaysFlashSupported -> AeMode.ALWAYS_FLASH
            profile.autoFlashSupported -> AeMode.AUTO_FLASH
            else -> null
        }
        val fps = (videoFps ?: VideoMath.DEFAULT_FPS).coerceIn(1, 240)

        val exposure = if (manual != null && useManual) {
            when {
                record -> VideoMath.clampExposure(
                    ExposureValues(manual.iso, manual.shutterNs),
                    fps,
                    profile
                )
                // In video mode the preview shows exactly what the encoder will get.
                preview && videoMode -> VideoMath.clampExposure(
                    ExposureValues(manual.iso, manual.shutterNs),
                    fps,
                    profile
                )
                preview && previewClamp -> ExposureMath.previewValues(
                    ExposureValues(manual.iso, manual.shutterNs),
                    profile
                )
                else -> ExposureValues(manual.iso, manual.shutterNs)
            }
        } else {
            null
        }

        val approximate = preview && manual != null && ExposureMath.isPreviewApproximate(
            ExposureValues(manual.iso, manual.shutterNs)
        )

        val ae = when {
            useManual -> AeMode.OFF
            // Torch is a separate flash mode, so video keeps Auto exposure.
            record -> AeMode.AUTO
            !flashOn -> AeMode.AUTO
            // In Normal mode, use the camera's own AE-managed flash mode for precapture so the
            // HAL can meter the scene before the main flash.
            flashMetering -> aeManagedFlashMode ?: AeMode.AUTO
            // The repeating preview only needs to be flash-capable so the precapture handshake
            // can run. AUTO_FLASH arms the flash without firing it on every frame; forcing
            // ALWAYS_FLASH here would strobe the LED continuously.
            preview -> if (profile.autoFlashSupported) AeMode.AUTO_FLASH else AeMode.AUTO
            // Still: force the LED with FLASH_MODE_SINGLE and plain auto exposure. This is the
            // only combination that reliably fires the LED on the still; ALWAYS_FLASH/AUTO_FLASH
            // are ignored or fire during precapture instead on some HALs. Because plain AE is not
            // flash-aware, Normal compensates with an adaptive EV bias (see CameraController).
            else -> AeMode.AUTO
        }

        val af = when (state.focus) {
            is FocusMode.Auto -> when {
                profile.afContinuousSupported -> AfMode.CONTINUOUS_PICTURE
                profile.afAutoSupported -> AfMode.AUTO
                else -> AfMode.OFF
            }
            is FocusMode.Manual -> AfMode.OFF
        }

        val focusDistance = (state.focus as? FocusMode.Manual)
            ?.takeIf { profile.supportsManualFocus }
            ?.diopters
            ?.coerceIn(0f, profile.minFocusDiopters)

        val wb: WbSetting = when (val w = state.wb) {
            is WhiteBalanceMode.Auto -> WbSetting.Auto
            is WhiteBalanceMode.Preset ->
                if (profile.awbPresets.contains(w.preset)) WbSetting.Preset(w.preset) else WbSetting.Auto
            is WhiteBalanceMode.Kelvin ->
                if (profile.supportsKelvin) {
                    val result = ManualWhiteBalance.computeKelvin(profile, w.kelvin, fallbackTransform)
                    WbSetting.Kelvin(result.gains, result.transform)
                } else {
                    WbSetting.Auto
                }
        }

        val useZoomRatio = strategy == LensMode.LOGICAL && profile.zoomRatioRange != null
        val assist = preview && focusAssist
        val assistFactor = if (assist) 3f else 1f
        val absoluteZoom = (profile.baseZoom * state.zoom * assistFactor).coerceAtLeast(0.1f)
        val cropZoom = if (useZoomRatio) 1f else state.zoom * assistFactor
        val cropArray = if (strategy == LensMode.LOGICAL) profile.logicalActiveArray else profile.activeArray
        val crop = AspectMath.cropRect(cropArray, outputAspect, cropZoom)
        val zoomRatio = if (useZoomRatio) {
            val range = profile.zoomRatioRange
            range.clamp(absoluteZoom)
        } else {
            null
        }

        val postRaw = if (useManual) {
            val range = profile.postRawBoostRange
            if (range != null && range.contains(NoProcessingFlags.POST_RAW_BOOST)) {
                NoProcessingFlags.POST_RAW_BOOST
            } else {
                null
            }
        } else {
            null
        }

        return RequestSpec(
            intent = intent,
            aeMode = ae,
            // Force the main still pulse (Normal and Late). The Normal metering request also
            // uses SINGLE when the device has no AE-managed flash mode.
            flashSingle = !record && flashOn &&
                (!preview || (flashMetering && aeManagedFlashMode == null)),
            torch = flashOn && (record || (preview && videoMode)),
            aeFps = when {
                aeFpsRange != null -> aeFpsRange
                record -> RangeI(fps, fps)
                else -> null
            },
            afMode = af,
            focusDistance = focusDistance,
            wb = wb,
            noiseReductionOff = true.takeIf { profile.noiseReductionOff },
            edgeOff = true.takeIf { profile.edgeOff },
            hotPixelOff = true.takeIf { profile.hotPixelOff },
            aberrationOff = true.takeIf { profile.aberrationOff },
            distortionOff = true.takeIf { profile.distortionOff },
            videoStabilizationOff = true.takeIf { profile.videoStabilizationOff },
            tonemap = if (profile.tonemapPresetCurve) TonemapSetting.SRGB_PRESET else TonemapSetting.FAST,
            iso = exposure?.iso,
            exposureNs = exposure?.shutterNs,
            exposureCompensation = if (useManual) null else exposureCompensation,
            frameDurationNs = if (record) {
                VideoMath.frameDurationNs(fps)
            } else {
                // Manual exposure has no AE range to honour, so a preview would be capped at
                // 30 fps by the default frame duration. Follow the preview rate instead.
                exposure?.let {
                    val floorNs = if (preview && aeFpsRange != null) {
                        VideoMath.frameDurationNs(aeFpsRange.max)
                    } else {
                        ExposureMath.DEFAULT_FRAME_NS
                    }
                    ExposureMath.frameDurationNs(it.shutterNs, profile.maxFrameDurationNs, floorNs)
                }
            },
            postRawBoost = postRaw,
            zoomRatio = zoomRatio,
            crop = crop,
            jpegQuality = jpegQuality.coerceIn(1, 100),
            thumbnailSize = thumbnailSize ?: NoProcessingFlags.THUMBNAIL_SIZE,
            // Normal uses AE precapture for accurate single-flash metering. Late deliberately
            // keeps the direct, no-precapture capture path.
            needPrecapture = !lateFlashBehavior && !preview && !record && flashOn && !useManual,
            manualExposure = useManual,
            approximatePreview = approximate,
            relativeZoom = state.zoom
        )
    }
}
