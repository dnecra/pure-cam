package com.necra.purecam.core

enum class CaptureIntent { PREVIEW, STILL, RECORD }

enum class AeMode { AUTO, ALWAYS_FLASH, OFF }

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
     * @param videoFpsRange AE target range advertised by the camera, when available. Preview
     *   uses it to request the chosen video rate or the fastest safe photo-preview range.
     * @param videoMode true while the screen is in video mode; the flash becomes a steady torch.
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
        videoFpsRange: RangeI? = null,
        videoMode: Boolean = false,
        exposureCompensation: Int? = null
    ): RequestSpec {
        val manual = state.exposure as? ExposureMode.Manual
        val useManual = manual != null && profile.supportsManualExposure
        val flashOn = state.flash && profile.flashAvailable
        val preview = intent == CaptureIntent.PREVIEW
        val record = intent == CaptureIntent.RECORD
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
            flashOn && profile.alwaysFlashSupported -> AeMode.ALWAYS_FLASH
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
            range?.clamp(absoluteZoom) ?: absoluteZoom
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
            flashSingle = !preview && !record && useManual && flashOn,
            torch = flashOn && (record || (preview && videoMode)),
            aeFps = when {
                videoFpsRange != null -> videoFpsRange
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
                exposure?.let { ExposureMath.frameDurationNs(it.shutterNs, profile.maxFrameDurationNs) }
            },
            postRawBoost = postRaw,
            zoomRatio = zoomRatio,
            crop = crop,
            jpegQuality = NoProcessingFlags.JPEG_QUALITY,
            thumbnailSize = NoProcessingFlags.THUMBNAIL_SIZE,
            needPrecapture = !preview && !record && ae == AeMode.ALWAYS_FLASH,
            manualExposure = useManual,
            approximatePreview = approximate,
            relativeZoom = state.zoom
        )
    }
}
