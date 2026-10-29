package com.necra.purecam.core

/** A width/height pair. Kept free of Android types so the math is unit-testable. */
data class SizeI(val width: Int, val height: Int) {
    val aspect: Float get() = width.toFloat() / height.toFloat()
    val area: Long get() = width.toLong() * height.toLong()
    override fun toString(): String = "${width}x$height"
}

/** Left/top/right/bottom rectangle in sensor pixels. */
data class RectI(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val aspect: Float get() = width.toFloat() / height.toFloat()
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    fun clampTo(other: RectI): RectI = RectI(
        left.coerceIn(other.left, other.right),
        top.coerceIn(other.top, other.bottom),
        right.coerceIn(other.left, other.right),
        bottom.coerceIn(other.top, other.bottom)
    )
    override fun toString(): String = "[$left,$top,$right,$bottom]"
}

data class RangeI(val min: Int, val max: Int) {
    init {
        require(min <= max) { "min > max: $min > $max" }
    }

    val span: Int get() = max - min

    fun clamp(value: Int): Int = value.coerceIn(min, max)
    fun contains(value: Int): Boolean = value in min..max
    fun fraction(value: Int): Float = if (span == 0) 0f else (clamp(value) - min).toFloat() / span
    fun at(fraction: Float): Int = (min + span * fraction.coerceIn(0f, 1f)).toInt()
    override fun toString(): String = "[$min..$max]"
}

data class RangeL(val min: Long, val max: Long) {
    init {
        require(min <= max) { "min > max: $min > $max" }
    }

    fun clamp(value: Long): Long = value.coerceIn(min, max)
    fun contains(value: Long): Boolean = value in min..max
    override fun toString(): String = "[$min..$max]"
}

data class RangeF(val min: Float, val max: Float) {
    init {
        require(min <= max) { "min > max: $min > $max" }
    }

    fun clamp(value: Float): Float = value.coerceIn(min, max)
    override fun toString(): String = "[$min..$max]"
}

enum class Facing { BACK, FRONT, EXTERNAL }

/** How a lens is reached. See plan section 3.3. */
enum class LensMode { DIRECT, PHYSICAL, LOGICAL }

enum class Aspect(val label: String, val ratio: Float) {
    R4_3("4:3", 4f / 3f),
    R3_2("3:2", 3f / 2f),
    R16_9("16:9", 16f / 9f),
    R1_1("1:1", 1f),
    R2_1("2:1", 2f),
    FULL("Full", 0f);

    val isFull: Boolean get() = this == FULL
}

/** White-balance presets that Camera2 can select directly. CCT is the nominal value used for display. */
enum class WbPreset(val label: String, val cct: Int) {
    INCANDESCENT("Incandescent", 2856),
    FLUORESCENT("Fluorescent", 3800),
    WARM_FLUORESCENT("Warm fluor.", 3500),
    DAYLIGHT("Daylight", 5500),
    CLOUDY_DAYLIGHT("Cloudy", 6500),
    TWILIGHT("Twilight", 7000),
    SHADE("Shade", 7500)
}

sealed interface ExposureMode {
    data object Auto : ExposureMode
    data class Manual(val iso: Int, val shutterNs: Long) : ExposureMode
}

sealed interface FocusMode {
    data object Auto : FocusMode
    data class Manual(val diopters: Float) : FocusMode
}

sealed interface WhiteBalanceMode {
    data object Auto : WhiteBalanceMode
    data class Preset(val preset: WbPreset) : WhiteBalanceMode
    data class Kelvin(val kelvin: Int) : WhiteBalanceMode
}

/**
 * Everything the user has chosen. Immutable; every UI change produces a new copy.
 * The lens profile carries the device capabilities needed to clamp the choices.
 */
data class CameraState(
    val lens: LensProfile,
    val aspect: Aspect,
    val flash: Boolean,
    val zoom: Float,
    val exposure: ExposureMode,
    val focus: FocusMode,
    val wb: WhiteBalanceMode,
    /** Selected JPEG buffer size; null means the lens's largest still size. */
    val photoResolution: SizeI? = null
) {
    companion object {
        fun defaults(profile: LensProfile, photoResolution: SizeI? = null): CameraState = CameraState(
            lens = profile,
            aspect = Aspect.R4_3,
            flash = false,
            zoom = 1f,
            exposure = ExposureMode.Auto,
            focus = FocusMode.Auto,
            wb = WhiteBalanceMode.Auto,
            photoResolution = photoResolution?.takeIf { it in profile.jpegSizes }
        )
    }

    fun clamped(): CameraState {
        val e = when (exposure) {
            is ExposureMode.Auto -> ExposureMode.Auto
            is ExposureMode.Manual -> if (lens.manualSensor && lens.isoRange != null && lens.exposureRange != null) {
                ExposureMode.Manual(
                    lens.isoRange.clamp(exposure.iso),
                    lens.exposureRange.clamp(exposure.shutterNs)
                )
            } else {
                ExposureMode.Auto
            }
        }
        val f = when (focus) {
            is FocusMode.Auto -> FocusMode.Auto
            is FocusMode.Manual -> if (lens.manualSensor && lens.minFocusDiopters > 0f) {
                FocusMode.Manual(focus.diopters.coerceIn(0f, lens.minFocusDiopters))
            } else {
                FocusMode.Auto
            }
        }
        val w = when (wb) {
            is WhiteBalanceMode.Auto -> WhiteBalanceMode.Auto
            is WhiteBalanceMode.Preset -> if (lens.awbPresets.contains(wb.preset)) {
                wb
            } else {
                WhiteBalanceMode.Auto
            }
            is WhiteBalanceMode.Kelvin -> if (lens.supportsKelvin) {
                WhiteBalanceMode.Kelvin(ManualWhiteBalance.clampKelvin(wb.kelvin))
            } else {
                WhiteBalanceMode.Auto
            }
        }
        return copy(
            flash = flash && lens.flashAvailable,
            zoom = zoom.coerceIn(1f, lens.maxZoom.coerceAtLeast(1f)),
            exposure = e,
            focus = f,
            wb = w
        )
    }

    /** Switching lens carries values over when still in range and resets focus to infinity. */
    fun withLens(newLens: LensProfile): CameraState = copy(
        lens = newLens,
        zoom = 1f,
        focus = FocusMode.Auto,
        photoResolution = photoResolution?.takeIf { it in newLens.jpegSizes },
        exposure = when (exposure) {
            is ExposureMode.Manual -> ExposureMode.Manual(
                newLens.isoRange?.clamp(exposure.iso) ?: exposure.iso,
                newLens.exposureRange?.clamp(exposure.shutterNs) ?: exposure.shutterNs
            )
            else -> exposure
        }
    ).clamped()
}

/**
 * Device capabilities for one selectable lens. All values are plain Kotlin types so the
 * request builder and its tests never need an Android runtime.
 */
data class LensProfile(
    val id: String,
    val cameraId: String,
    val physicalId: String?,
    val mode: LensMode,
    val facing: Facing,
    val label: String,
    val focalEquivalentMm: Float,
    val baseZoom: Float,
    val sensorOrientation: Int,
    val activeArray: RectI,
    /** Crop coordinates for a request sent to the logical camera (strategy C / fallback). */
    val logicalActiveArray: RectI,
    val pixelArray: RectI?,
    val jpegSizes: List<SizeI>,
    val previewSizes: List<SizeI>,
    /** Buffers the encoder can consume; empty when the lens cannot record video. */
    val videoSizes: List<SizeI> = emptyList(),
    val videoFpsRanges: List<IntRange> = emptyList(),
    val isoRange: RangeI?,
    val maxAnalogIso: Int?,
    val exposureRange: RangeL?,
    val maxFrameDurationNs: Long,
    val postRawBoostRange: RangeI?,
    val minFocusDiopters: Float,
    val focusCalibrated: Boolean,
    val awbPresets: Set<WbPreset>,
    val alwaysFlashSupported: Boolean,
    val afContinuousSupported: Boolean,
    val afAutoSupported: Boolean,
    val manualSensor: Boolean,
    val manualPostProcessing: Boolean,
    val noiseReductionOff: Boolean,
    val edgeOff: Boolean,
    val hotPixelOff: Boolean,
    val aberrationOff: Boolean,
    val distortionOff: Boolean,
    val videoStabilizationOff: Boolean,
    val tonemapPresetCurve: Boolean,
    val flashAvailable: Boolean,
    val maxZoom: Float,
    val zoomRatioRange: RangeF?,
    val colorTransform1: FloatArray?,
    val colorTransform2: FloatArray?,
    val referenceIlluminant1: Int,
    val referenceIlluminant2: Int
) {
    val supportsKelvin: Boolean
        get() = manualPostProcessing && colorTransform1 != null && colorTransform2 != null

    val supportsManualExposure: Boolean
        get() = manualSensor && isoRange != null && exposureRange != null

    val supportsManualFocus: Boolean
        get() = manualSensor && minFocusDiopters > 0f

    val supportsVideo: Boolean
        get() = videoSizes.isNotEmpty()

    val hasMultipleNativeZooms: Boolean
        get() = zoomRatioRange != null

    val sensorIsRotated: Boolean
        get() = sensorOrientation % 180 == 90
}
