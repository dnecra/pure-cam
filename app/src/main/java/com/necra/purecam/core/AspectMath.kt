package com.necra.purecam.core

/** Camera stream sizes and upright ratios for preview framing and saved captures. */
data class AspectPlan(
    /** Ratio of the camera or encoder output buffer, before display rotation. */
    val outputAspect: Float,
    val jpegSize: SizeI,
    val previewSize: SizeI,
    /** Upright ratio used to fit the unmodified stream in the viewfinder. */
    val displayAspect: Float,
    val contentAspect: Float
)

/** Saved framing shared by photo and video capture. Portrait crop is the default. */
enum class CaptureAspectMode {
    PORTRAIT_9_16,
    ORIGINAL
}

object AspectMath {

    fun planNative(profile: LensProfile, maxPreviewWidth: Int = 1920): AspectPlan {
        val jpeg = largestSize(profile.jpegSizes)
            ?: SizeI(profile.activeArray.width, profile.activeArray.height)
        return createPlan(profile, jpeg, jpeg.aspect, profile.previewSizes, maxPreviewWidth)
    }

    fun planForCapture(profile: LensProfile, mode: CaptureAspectMode): AspectPlan = when (mode) {
        CaptureAspectMode.ORIGINAL -> planNative(profile)
        CaptureAspectMode.PORTRAIT_9_16 -> planForDisplayAspect(profile, 9f / 16f)
    }

    /** Sensor-space ratio needed to show [displayAspect] after the device's JPEG/video rotation. */
    fun outputAspectForDisplay(profile: LensProfile, displayAspect: Float): Float =
        if (profile.sensorOrientation % 180 == 90) 1f / displayAspect else displayAspect

    /** Preferred video buffer ratio for the user's selected display framing. */
    fun videoOutputAspect(profile: LensProfile, mode: CaptureAspectMode): Float = when (mode) {
        CaptureAspectMode.ORIGINAL -> profile.nativeAspect
        CaptureAspectMode.PORTRAIT_9_16 -> outputAspectForDisplay(profile, 9f / 16f)
    }

    /**
     * Build a camera plan from a supported JPEG size closest to the requested upright ratio.
     * Keeping the JPEG stream at that ratio lets the camera produce the crop directly, without
     * decoding and re-encoding the captured image in app code.
     */
    fun planForDisplayAspect(
        profile: LensProfile,
        displayAspect: Float,
        maxPreviewWidth: Int = 1920
    ): AspectPlan {
        val targetAspect = outputAspectForDisplay(profile, displayAspect)
        val matchingJpeg = profile.jpegSizes
            .filter { relativeAspectError(it.aspect, targetAspect) < 0.01f }
            .maxByOrNull { it.area }
        val jpeg = matchingJpeg
            ?: largestSize(profile.jpegSizes)
            ?: SizeI(profile.activeArray.width, profile.activeArray.height)
        val outputAspect = jpeg.aspect
        return createPlan(profile, jpeg, outputAspect, profile.previewSizes, maxPreviewWidth)
    }

    /** Center-cropped encoded dimensions for a stream buffer and target sensor-space ratio. */
    fun cropSize(size: SizeI, targetAspect: Float): SizeI {
        if (size.width <= 0 || size.height <= 0 || targetAspect <= 0f) return size
        return if (size.aspect > targetAspect) {
            SizeI((size.height * targetAspect).toInt().coerceAtLeast(1), size.height)
        } else {
            SizeI(size.width, (size.width / targetAspect).toInt().coerceAtLeast(1))
        }
    }

    /** Plan for a fixed video encoder buffer; its stream ratio is preserved in the saved clip. */
    fun planForOutput(
        profile: LensProfile,
        output: SizeI,
        previewSizes: List<SizeI> = profile.previewSizes,
        maxPreviewWidth: Int = 1920
    ): AspectPlan = createPlan(profile, output, output.aspect, previewSizes, maxPreviewWidth)

    private fun createPlan(
        profile: LensProfile,
        output: SizeI,
        outputAspect: Float,
        previewSizes: List<SizeI>,
        maxPreviewWidth: Int
    ): AspectPlan {
        val preview = previewSize(previewSizes, outputAspect, maxPreviewWidth) ?: output
        val rotated = profile.sensorOrientation % 180 == 90
        return AspectPlan(
            outputAspect = outputAspect,
            jpegSize = output,
            previewSize = preview,
            displayAspect = uprightAspect(outputAspect, rotated),
            contentAspect = uprightAspect(preview.aspect, rotated)
        )
    }

    private fun uprightAspect(aspect: Float, rotated: Boolean): Float =
        if (rotated) 1f / aspect else aspect

    fun largestSize(sizes: List<SizeI>): SizeI? = sizes.maxByOrNull { it.area }

    fun previewSize(sizes: List<SizeI>, target: Float, maxWidth: Int): SizeI? {
        val usable = sizes.filter { it.width <= maxWidth || sizes.size == 1 }
        val pool = usable.ifEmpty { sizes }
        return pool.minWithOrNull(
            compareBy<SizeI> { relativeAspectError(it.aspect, target) }
                .thenByDescending { it.area }
        )
    }

    fun relativeAspectError(a: Float, b: Float): Float {
        if (a <= 0f || b <= 0f) return Float.MAX_VALUE
        return kotlin.math.abs(kotlin.math.ln(a / b))
    }

    /** Largest ratio-preserving rectangle inside [active], divided by [zoom], centered. */
    fun cropRect(active: RectI, ratio: Float, zoom: Float): RectI {
        val safeRatio = ratio.coerceIn(0.2f, 5f)
        val z = zoom.coerceAtLeast(1f)
        var w = active.width.toFloat()
        var h = w / safeRatio
        if (h > active.height) {
            h = active.height.toFloat()
            w = h * safeRatio
        }
        w /= z
        h /= z
        var wi = w.toInt().coerceAtLeast(2).let { if (it % 2 == 0) it else it - 1 }
        var hi = h.toInt().coerceAtLeast(2).let { if (it % 2 == 0) it else it - 1 }
        wi = wi.coerceAtMost(active.width)
        hi = hi.coerceAtMost(active.height)
        val left = active.left + (active.width - wi) / 2
        val top = active.top + (active.height - hi) / 2
        return RectI(left, top, left + wi, top + hi)
    }
}
