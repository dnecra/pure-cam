package com.necra.purecam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestBuilderTest {

    private val lens = TestProfiles.lens()

    private fun build(
        state: CameraState,
        intent: CaptureIntent = CaptureIntent.STILL,
        outputAspect: Float = 4f / 3f,
        strategy: LensMode = state.lens.mode,
        previewClamp: Boolean = true,
        focusAssist: Boolean = false,
        fallbackTransform: FloatArray? = null,
        videoFps: Int? = null,
        videoFpsRange: RangeI? = null,
        videoMode: Boolean = false,
        flashMetering: Boolean = false
    ): RequestSpec = RequestBuilder.build(
        state = state,
        profile = state.lens,
        intent = intent,
        outputAspect = outputAspect,
        strategy = strategy,
        previewClamp = previewClamp,
        focusAssist = focusAssist,
        fallbackTransform = fallbackTransform,
        videoFps = videoFps,
        videoFpsRange = videoFpsRange,
        videoMode = videoMode,
        flashMetering = flashMetering
    )

    @Test
    fun autoStill_usesNeutralDefaultsAndAllProcessingOff() {
        val spec = build(TestProfiles.autoState(lens))

        assertEquals(CaptureIntent.STILL, spec.intent)
        assertEquals(AeMode.AUTO, spec.aeMode)
        assertEquals(AfMode.CONTINUOUS_PICTURE, spec.afMode)
        assertNull(spec.iso)
        assertNull(spec.exposureNs)
        assertNull(spec.frameDurationNs)
        assertNull(spec.focusDistance)
        assertNull(spec.postRawBoost)
        assertNull(spec.zoomRatio)
        assertFalse(spec.manualExposure)
        assertFalse(spec.needPrecapture)
        assertFalse(spec.flashSingle)
        assertEquals(WbSetting.Auto, spec.wb)
        assertEquals(TonemapSetting.SRGB_PRESET, spec.tonemap)
        assertEquals(true, spec.noiseReductionOff)
        assertEquals(true, spec.edgeOff)
        assertEquals(true, spec.hotPixelOff)
        assertEquals(true, spec.aberrationOff)
        assertEquals(true, spec.distortionOff)
        assertEquals(true, spec.videoStabilizationOff)
        assertEquals(NoProcessingFlags.JPEG_QUALITY, spec.jpegQuality)
        assertEquals(NoProcessingFlags.THUMBNAIL_SIZE, spec.thumbnailSize)
        assertEquals(RectI(0, 0, 4000, 3000), spec.crop)
        assertEquals(1f, spec.relativeZoom, 0f)
    }

    @Test
    fun manualStillWithFlash_usesAeOffAndSingleFlash() {
        val state = TestProfiles.manualState(lens, iso = 400, shutterNs = 8_000_000L, flash = true)

        val spec = build(state)

        assertEquals(AeMode.OFF, spec.aeMode)
        assertEquals(400, spec.iso)
        assertEquals(8_000_000L, spec.exposureNs)
        assertEquals(33_333_333L, spec.frameDurationNs)
        assertEquals(100, spec.postRawBoost)
        assertTrue(spec.flashSingle)
        assertTrue(spec.manualExposure)
        assertFalse(spec.needPrecapture)
    }

    @Test
    fun manualPreviewWithFlash_doesNotUseSingleFlash() {
        val state = TestProfiles.manualState(lens, iso = 400, shutterNs = 8_000_000L, flash = true)

        val spec = build(state, intent = CaptureIntent.PREVIEW)

        assertEquals(AeMode.OFF, spec.aeMode)
        assertFalse(spec.flashSingle)
    }

    @Test
    fun autoStillWithFlash_requiresPrecapture() {
        val state = TestProfiles.autoState(lens, flash = true)

        val still = build(state)
        val preview = build(state, intent = CaptureIntent.PREVIEW)

        // AUTO_FLASH is only used for the precapture handshake; the still request forces the
        // LED with FLASH_MODE_SINGLE and plain auto exposure, which every HAL honours.
        assertEquals(AeMode.AUTO, still.aeMode)
        assertTrue(still.needPrecapture)
        assertTrue(still.flashSingle)
        // Preview only arms AUTO_FLASH for the precapture handshake; it never forces the LED.
        assertEquals(AeMode.AUTO_FLASH, preview.aeMode)
        assertFalse(preview.needPrecapture)
    }

    @Test
    fun autoFlashFallbackIsUsedWhenAlwaysFlashIsUnavailable() {
        val profile = TestProfiles.lens(alwaysFlashSupported = false, autoFlashSupported = true)
        val state = TestProfiles.autoState(profile, flash = true)

        val still = build(state)
        val preview = build(state, intent = CaptureIntent.PREVIEW)

        assertEquals(AeMode.AUTO, still.aeMode)
        assertTrue(still.needPrecapture)
        assertTrue(still.flashSingle)
        assertEquals(AeMode.AUTO_FLASH, preview.aeMode)
    }

    @Test
    fun precaptureUsesFlashAeModeOnEveryDevice() {
        val autoOnly = TestProfiles.lens(alwaysFlashSupported = false, autoFlashSupported = true)
        val alwaysOnly = TestProfiles.lens(alwaysFlashSupported = true, autoFlashSupported = false)

        assertEquals(
            AeMode.AUTO_FLASH,
            build(
                TestProfiles.autoState(autoOnly, flash = true),
                intent = CaptureIntent.PREVIEW,
                flashMetering = true
            ).aeMode
        )
        assertEquals(
            AeMode.ALWAYS_FLASH,
            build(
                TestProfiles.autoState(alwaysOnly, flash = true),
                intent = CaptureIntent.PREVIEW,
                flashMetering = true
            ).aeMode
        )
    }

    @Test
    fun flashWithoutAnyFlashAeModeFallsBackToPlainAuto() {
        val profile = TestProfiles.lens(alwaysFlashSupported = false, autoFlashSupported = false)
        val state = TestProfiles.autoState(profile, flash = true)

        val still = build(state)

        assertEquals(AeMode.AUTO, still.aeMode)
        assertFalse(still.needPrecapture)
        // Without any AE flash mode the LED is forced directly on the still request.
        assertTrue(still.flashSingle)
    }

    @Test
    fun flashRequiresDeviceSupport() {
        val state = TestProfiles.autoState(
            TestProfiles.lens(flashAvailable = false, alwaysFlashSupported = false),
            flash = true
        )

        val spec = build(state)

        assertEquals(AeMode.AUTO, spec.aeMode)
        assertFalse(spec.flashSingle)
        assertFalse(spec.needPrecapture)
    }

    @Test
    fun manualExposurePreview_isClampedButStillIsExact() {
        val profile = TestProfiles.lens(isoRange = RangeI(50, 3200))
        val state = TestProfiles.manualState(profile, iso = 100, shutterNs = 1_000_000_000L)

        val preview = build(state, intent = CaptureIntent.PREVIEW)
        val still = build(state, intent = CaptureIntent.STILL)

        assertEquals(1000, preview.iso)
        assertEquals(ExposureMath.MAX_PREVIEW_EXPOSURE_NS, preview.exposureNs)
        assertTrue(preview.approximatePreview)

        assertEquals(100, still.iso)
        assertEquals(1_000_000_000L, still.exposureNs)
        assertFalse(still.approximatePreview)
    }

    @Test
    fun previewClampCanBeDisabled() {
        val state = TestProfiles.manualState(lens, iso = 100, shutterNs = 1_000_000_000L)

        val spec = build(state, intent = CaptureIntent.PREVIEW, previewClamp = false)

        assertEquals(100, spec.iso)
        assertEquals(1_000_000_000L, spec.exposureNs)
        assertTrue(spec.approximatePreview)
    }

    @Test
    fun manualExposureIsIgnoredWithoutManualSensorCapability() {
        val profile = TestProfiles.lens(manualSensor = false)
        val state = TestProfiles.manualState(profile, iso = 400, shutterNs = 8_000_000L, flash = true)

        val spec = build(state)

        // Manual exposure is unavailable, but auto exposure with flash still works.
        assertEquals(AeMode.AUTO, spec.aeMode)
        assertNull(spec.iso)
        assertNull(spec.exposureNs)
        assertNull(spec.postRawBoost)
        assertFalse(spec.manualExposure)
        assertTrue(spec.flashSingle)
    }

    @Test
    fun postRawBoostIsSkippedWhenRangeExcludesOneHundred() {
        val profile = TestProfiles.lens(postRawBoostRange = RangeI(200, 800))
        val state = TestProfiles.manualState(profile, iso = 400, shutterNs = 8_000_000L)

        assertNull(build(state).postRawBoost)
    }

    @Test
    fun manualFocus_setsClampedDiopters() {
        val profile = TestProfiles.lens(minFocusDiopters = 10f)
        val state = TestProfiles.manualState(profile, diopters = 25f)

        val spec = build(state)

        assertEquals(AfMode.OFF, spec.afMode)
        assertEquals(10f, spec.focusDistance!!, 0f)
    }

    @Test
    fun manualFocusIsIgnoredWithoutSupport() {
        val profile = TestProfiles.lens(minFocusDiopters = 0f)
        val state = TestProfiles.manualState(profile, diopters = 2f)

        val spec = build(state)

        assertEquals(AfMode.OFF, spec.afMode)
        assertNull(spec.focusDistance)
    }

    @Test
    fun autoFocusFallsBackThroughAvailableModes() {
        assertEquals(
            AfMode.AUTO,
            build(TestProfiles.autoState(TestProfiles.lens(afContinuousSupported = false))).afMode
        )
        assertEquals(
            AfMode.OFF,
            build(
                TestProfiles.autoState(
                    TestProfiles.lens(afContinuousSupported = false, afAutoSupported = false)
                )
            ).afMode
        )
        assertEquals(AfMode.CONTINUOUS_PICTURE, build(TestProfiles.autoState(lens)).afMode)
    }

    @Test
    fun whiteBalancePresetIsOnlyUsedWhenAvailable() {
        val supported = TestProfiles.autoState(
            TestProfiles.lens(awbPresets = setOf(WbPreset.DAYLIGHT)),
            wb = WhiteBalanceMode.Preset(WbPreset.DAYLIGHT)
        )
        val unsupported = TestProfiles.autoState(
            TestProfiles.lens(awbPresets = setOf(WbPreset.DAYLIGHT)),
            wb = WhiteBalanceMode.Preset(WbPreset.MOONLIGHT)
        )

        assertEquals(WbSetting.Preset(WbPreset.DAYLIGHT), build(supported).wb)
        assertEquals(WbSetting.Auto, build(unsupported).wb)
    }

    @Test
    fun kelvinWhiteBalanceProducesGainsAndTransform() {
        val state = TestProfiles.autoState(lens, wb = WhiteBalanceMode.Kelvin(5000))

        val spec = build(state)

        val wb = spec.wb as WbSetting.Kelvin
        assertEquals(4, wb.gains.size)
        assertEquals(9, wb.transform.size)
        assertEquals(1f, wb.gains[1], 0f)
        assertEquals(1f, wb.gains[2], 0f)
    }

    @Test
    fun kelvinWhiteBalanceFallsBackToAutoWithoutCalibration() {
        val profile = TestProfiles.lens(colorTransform1 = null, colorTransform2 = null)
        val state = TestProfiles.autoState(profile, wb = WhiteBalanceMode.Kelvin(5000))

        assertEquals(WbSetting.Auto, build(state).wb)
    }

    @Test
    fun unsupportedProcessingFlagsAreOmitted() {
        val profile = TestProfiles.lens(
            noiseReductionOff = false,
            edgeOff = false,
            hotPixelOff = false,
            aberrationOff = false,
            distortionOff = false,
            videoStabilizationOff = false,
            tonemapPresetCurve = false
        )
        val spec = build(TestProfiles.autoState(profile))

        assertNull(spec.noiseReductionOff)
        assertNull(spec.edgeOff)
        assertNull(spec.hotPixelOff)
        assertNull(spec.aberrationOff)
        assertNull(spec.distortionOff)
        assertNull(spec.videoStabilizationOff)
        assertEquals(TonemapSetting.FAST, spec.tonemap)
    }

    @Test
    fun logicalStrategyUsesLogicalArrayAndZoomRatio() {
        val profile = TestProfiles.lens(
            activeArray = RectI(0, 0, 4000, 3000),
            logicalActiveArray = RectI(0, 0, 8000, 6000),
            zoomRatioRange = RangeF(0.5f, 10f)
        )
        val state = TestProfiles.autoState(profile, zoom = 2f)

        val spec = build(state, strategy = LensMode.LOGICAL)

        assertEquals(RectI(0, 0, 8000, 6000), spec.crop)
        assertEquals(2f, spec.zoomRatio!!, 1e-6f)
    }

    @Test
    fun logicalStrategyWithoutZoomRatioCropsInstead() {
        val profile = TestProfiles.lens(
            activeArray = RectI(0, 0, 4000, 3000),
            logicalActiveArray = RectI(0, 0, 8000, 6000),
            zoomRatioRange = null
        )
        val state = TestProfiles.autoState(profile, zoom = 2f)

        val spec = build(state, strategy = LensMode.LOGICAL)

        assertEquals(RectI(2000, 1500, 6000, 4500), spec.crop)
        assertNull(spec.zoomRatio)
    }

    @Test
    fun physicalStrategyUsesPhysicalArrayAndCropZoom() {
        val profile = TestProfiles.lens(
            mode = LensMode.PHYSICAL,
            activeArray = RectI(0, 0, 4000, 3000),
            logicalActiveArray = RectI(0, 0, 8000, 6000)
        )
        val state = TestProfiles.autoState(profile, zoom = 2f)

        val spec = build(state, strategy = LensMode.PHYSICAL)

        assertEquals(RectI(1000, 750, 3000, 2250), spec.crop)
        assertNull(spec.zoomRatio)
    }

    @Test
    fun focusAssistZoomsPreviewOnly() {
        val profile = TestProfiles.lens(
            activeArray = RectI(0, 0, 4000, 3000),
            logicalActiveArray = RectI(0, 0, 8000, 6000),
            zoomRatioRange = RangeF(0.5f, 10f)
        )
        val state = TestProfiles.autoState(profile)

        val assisted = build(state, intent = CaptureIntent.PREVIEW, strategy = LensMode.LOGICAL, focusAssist = true)
        val still = build(state, intent = CaptureIntent.STILL, strategy = LensMode.LOGICAL, focusAssist = true)

        assertEquals(3f, assisted.zoomRatio!!, 1e-6f)
        assertEquals(1f, still.zoomRatio!!, 1e-6f)
    }

    @Test
    fun cropFollowsRequestedOutputAspect() {
        val spec = build(TestProfiles.autoState(lens), outputAspect = 16f / 9f)

        assertEquals(RectI(0, 375, 4000, 2625), spec.crop)
        assertEquals(16f / 9f, spec.crop.aspect, 1e-3f)
    }

    @Test
    fun kelvinFallsBackToDeviceTransformWhenProvided() {
        val state = TestProfiles.autoState(lens, wb = WhiteBalanceMode.Kelvin(5000))
        val fallback = FloatArray(9) { 0.25f }

        val wb = build(state, fallbackTransform = fallback).wb as WbSetting.Kelvin

        assertNotNull(wb.transform)
        assertEquals(0.25f, wb.transform[0], 0f)
        assertEquals(0.25f, wb.transform[8], 0f)
    }

    @Test
    fun record_usesAutoExposureTorchAndPinnedFrameRate() {
        val state = TestProfiles.manualState(lens, iso = 200, shutterNs = 8_000_000L, flash = true)

        val spec = build(
            state,
            intent = CaptureIntent.RECORD,
            videoFps = 30,
            videoFpsRange = RangeI(30, 30)
        )

        assertEquals(CaptureIntent.RECORD, spec.intent)
        // Manual exposure is still honored, but the torch replaces single-flash and AE stays off.
        assertEquals(AeMode.OFF, spec.aeMode)
        assertEquals(200, spec.iso)
        assertEquals(8_000_000L, spec.exposureNs)
        assertTrue(spec.torch)
        assertFalse(spec.flashSingle)
        assertEquals(RangeI(30, 30), spec.aeFps)
        assertEquals(33_333_333L, spec.frameDurationNs)
        assertFalse(spec.needPrecapture)
    }

    @Test
    fun recordWithAutoExposure_doesNotUseFlashModes() {
        val spec = build(
            TestProfiles.autoState(lens, flash = true),
            intent = CaptureIntent.RECORD,
            videoFps = 24,
            videoFpsRange = RangeI(24, 24)
        )

        assertEquals(AeMode.AUTO, spec.aeMode)
        assertTrue(spec.torch)
        assertFalse(spec.flashSingle)
        assertFalse(spec.needPrecapture)
        assertEquals(RangeI(24, 24), spec.aeFps)
        assertEquals(41_666_666L, spec.frameDurationNs)
    }

    @Test
    fun recordWithoutFlash_hasNoTorch() {
        val spec = build(
            TestProfiles.autoState(lens, flash = false),
            intent = CaptureIntent.RECORD,
            videoFps = 30
        )

        assertFalse(spec.torch)
        assertEquals(RangeI(30, 30), spec.aeFps)
    }

    @Test
    fun recordClampsLongManualShutterToFrameDuration() {
        val state = TestProfiles.manualState(lens, iso = 100, shutterNs = 100_000_000L)

        val spec = build(state, intent = CaptureIntent.RECORD, videoFps = 30)

        assertEquals(33_333_333L, spec.exposureNs)
        // Brightness preserved: 100 ISO x 3 (shutter shortened by the same factor).
        assertEquals(300, spec.iso)
        assertFalse(spec.approximatePreview)
    }

    @Test
    fun videoModePreview_turnsFlashIntoATorch() {
        val state = TestProfiles.autoState(lens, flash = true)

        val preview = build(state, intent = CaptureIntent.PREVIEW, videoMode = true)
        val still = build(state, intent = CaptureIntent.STILL, videoMode = true)

        assertTrue(preview.torch)
        assertFalse(still.torch)
        assertTrue(still.flashSingle)
    }

    @Test
    fun videoModePreview_clampsManualShutterLikeRecording() {
        val state = TestProfiles.manualState(lens, iso = 100, shutterNs = 100_000_000L)

        val preview = build(state, intent = CaptureIntent.PREVIEW, videoMode = true, videoFps = 30)

        assertEquals(33_333_333L, preview.exposureNs)
        assertEquals(300, preview.iso)
        assertFalse(preview.approximatePreview)
    }
}
