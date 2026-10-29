package com.rawcam.core

/** Builds realistic-enough LensProfile values so the pure logic can be tested on the JVM. */
object TestProfiles {

    private val IDENTITY = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    )

    fun lens(
        id: String = "camera:0",
        cameraId: String = "0",
        physicalId: String? = null,
        mode: LensMode = LensMode.DIRECT,
        facing: Facing = Facing.BACK,
        label: String = "1\u00d7",
        focalEquivalentMm: Float = 26f,
        baseZoom: Float = 1f,
        sensorOrientation: Int = 90,
        activeArray: RectI = RectI(0, 0, 4000, 3000),
        logicalActiveArray: RectI = activeArray,
        jpegSizes: List<SizeI> = listOf(
            SizeI(4000, 3000),
            SizeI(4000, 2250),
            SizeI(3000, 3000)
        ),
        previewSizes: List<SizeI> = listOf(
            SizeI(1920, 1440),
            SizeI(1280, 960),
            SizeI(1920, 1080)
        ),
        videoSizes: List<SizeI> = listOf(
            SizeI(3840, 2160),
            SizeI(1920, 1080),
            SizeI(1280, 720)
        ),
        videoFpsRanges: List<IntRange> = listOf(24..24, 30..30, 60..60),
        isoRange: RangeI? = RangeI(50, 3200),
        maxAnalogIso: Int? = 800,
        exposureRange: RangeL? = RangeL(100_000L, 1_000_000_000L),
        maxFrameDurationNs: Long = 1_000_000_000L,
        postRawBoostRange: RangeI? = RangeI(100, 800),
        minFocusDiopters: Float = 10f,
        focusCalibrated: Boolean = true,
        awbPresets: Set<WbPreset> = WbPreset.entries.toSet(),
        alwaysFlashSupported: Boolean = true,
        afContinuousSupported: Boolean = true,
        afAutoSupported: Boolean = true,
        manualSensor: Boolean = true,
        manualPostProcessing: Boolean = true,
        noiseReductionOff: Boolean = true,
        edgeOff: Boolean = true,
        hotPixelOff: Boolean = true,
        aberrationOff: Boolean = true,
        distortionOff: Boolean = true,
        videoStabilizationOff: Boolean = true,
        tonemapPresetCurve: Boolean = true,
        flashAvailable: Boolean = true,
        maxZoom: Float = 5f,
        zoomRatioRange: RangeF? = RangeF(0.5f, 10f),
        colorTransform1: FloatArray? = IDENTITY.copyOf(),
        colorTransform2: FloatArray? = IDENTITY.copyOf(),
        referenceIlluminant1: Int = 1,
        referenceIlluminant2: Int = 20
    ): LensProfile = LensProfile(
        id = id,
        cameraId = cameraId,
        physicalId = physicalId,
        mode = mode,
        facing = facing,
        label = label,
        focalEquivalentMm = focalEquivalentMm,
        baseZoom = baseZoom,
        sensorOrientation = sensorOrientation,
        activeArray = activeArray,
        logicalActiveArray = logicalActiveArray,
        pixelArray = activeArray,
        jpegSizes = jpegSizes,
        previewSizes = previewSizes,
        videoSizes = videoSizes,
        videoFpsRanges = videoFpsRanges,
        isoRange = isoRange,
        maxAnalogIso = maxAnalogIso,
        exposureRange = exposureRange,
        maxFrameDurationNs = maxFrameDurationNs,
        postRawBoostRange = postRawBoostRange,
        minFocusDiopters = minFocusDiopters,
        focusCalibrated = focusCalibrated,
        awbPresets = awbPresets,
        alwaysFlashSupported = alwaysFlashSupported,
        afContinuousSupported = afContinuousSupported,
        afAutoSupported = afAutoSupported,
        manualSensor = manualSensor,
        manualPostProcessing = manualPostProcessing,
        noiseReductionOff = noiseReductionOff,
        edgeOff = edgeOff,
        hotPixelOff = hotPixelOff,
        aberrationOff = aberrationOff,
        distortionOff = distortionOff,
        videoStabilizationOff = videoStabilizationOff,
        tonemapPresetCurve = tonemapPresetCurve,
        flashAvailable = flashAvailable,
        maxZoom = maxZoom,
        zoomRatioRange = zoomRatioRange,
        colorTransform1 = colorTransform1,
        colorTransform2 = colorTransform2,
        referenceIlluminant1 = referenceIlluminant1,
        referenceIlluminant2 = referenceIlluminant2
    )

    fun manualState(
        profile: LensProfile,
        iso: Int = 200,
        shutterNs: Long = 16_666_667L,
        diopters: Float = 2f,
        wb: WhiteBalanceMode = WhiteBalanceMode.Auto,
        flash: Boolean = false,
        zoom: Float = 1f
    ): CameraState = CameraState(
        lens = profile,
        aspect = Aspect.R4_3,
        flash = flash,
        zoom = zoom,
        exposure = ExposureMode.Manual(iso, shutterNs),
        focus = FocusMode.Manual(diopters),
        wb = wb
    )

    fun autoState(
        profile: LensProfile,
        flash: Boolean = false,
        zoom: Float = 1f,
        wb: WhiteBalanceMode = WhiteBalanceMode.Auto
    ): CameraState = CameraState(
        lens = profile,
        aspect = Aspect.R4_3,
        flash = flash,
        zoom = zoom,
        exposure = ExposureMode.Auto,
        focus = FocusMode.Auto,
        wb = wb
    )
}
