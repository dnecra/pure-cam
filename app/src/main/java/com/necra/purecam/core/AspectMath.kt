package com.necra.purecam.core

import kotlin.math.roundToInt

/** Everything needed to size the preview view, the JPEG stream and the sensor crop. */
data class AspectPlan(
    /** Requested aspect in sensor coordinates (width / height). */
    val targetAspect: Float,
    /** Aspect of the JPEG buffer (width / height). */
    val outputAspect: Float,
    val jpegSize: SizeI,
    val previewSize: SizeI,
    /** Crop at zoom 1, in sensor coordinates. Zoom is applied by RequestBuilder. */
    val crop: RectI,
    /** Aspect of the upright preview frame (width / height); the ratio the user picked. */
    val displayAspect: Float,
    /** Aspect of the upright capture stream (width / height), before the crop is applied. */
    val contentAspect: Float
)

object AspectMath {

    private const val ASPECT_TOLERANCE = 0.02f

    /** Ratios the UI offers, in cycle order. Every one is produced by cropping the capture. */
    val CROP_ASPECTS: List<Aspect> = listOf(Aspect.R4_3, Aspect.R16_9, Aspect.R1_1, Aspect.R2_1)

    /**
     * Screen ratio is width / height in the current (portrait-locked) display.
     * A rotated sensor needs the inverse ratio for an upright framing.
     */
    fun sensorTargetAspect(aspect: Aspect, screenAspect: Float, sensorOrientation: Int): Float {
        if (!aspect.isFull) return aspect.ratio
        val safe = screenAspect.coerceIn(0.3f, 3.3f)
        return if (sensorOrientation % 180 == 90) 1f / safe else safe
    }

    /**
     * Sizes the preview for the requested ratio. The capture stream always stays on the
     * largest native JPEG; the requested ratio is applied as a crop of that buffer, so
     * changing the ratio never has to restart the camera session.
     */
    fun plan(
        profile: LensProfile,
        aspect: Aspect,
        screenAspect: Float,
        maxPreviewWidth: Int = 1920,
        fullscreenAspect: Float? = null,
        jpegSize: SizeI? = null
    ): AspectPlan {
        val target = if (aspect == Aspect.R2_1 && fullscreenAspect != null &&
            fullscreenAspect.isFinite() && fullscreenAspect > 0.1f
        ) {
            // The 2:1 option is the fullscreen framing: use the viewfinder card's aspect.
            val safe = fullscreenAspect.coerceIn(0.3f, 3.3f)
            if (profile.sensorOrientation % 180 == 90) 1f / safe else safe
        } else {
            sensorTargetAspect(aspect, screenAspect, profile.sensorOrientation)
        }
        val jpeg = jpegSize?.takeIf { it in profile.jpegSizes }
            ?: largestSize(profile.jpegSizes)
            ?: SizeI(profile.activeArray.width, profile.activeArray.height)
        val outputAspect = jpeg.aspect
        val preview = previewSize(profile.previewSizes, outputAspect, maxPreviewWidth) ?: jpeg
        val rotated = profile.sensorOrientation % 180 == 90
        val display = if (rotated) 1f / target else target
        val content = if (rotated) 1f / preview.aspect else preview.aspect
        return AspectPlan(
            targetAspect = target,
            outputAspect = outputAspect,
            jpegSize = jpeg,
            previewSize = preview,
            // The request keeps the full native framing; cropping happens on the JPEG.
            crop = cropRect(profile.activeArray, outputAspect, 1f),
            displayAspect = display,
            contentAspect = content
        )
    }

    /**
     * Plan for a fixed output buffer, such as a video encoder surface. The preview keeps the
     * same aspect so what the user frames is exactly what gets recorded.
     */
    fun planForOutput(
        profile: LensProfile,
        output: SizeI,
        previewSizes: List<SizeI> = profile.previewSizes,
        maxPreviewWidth: Int = 1920
    ): AspectPlan {
        val outputAspect = output.aspect
        val preview = previewSize(previewSizes, outputAspect, maxPreviewWidth) ?: output
        val display = if (profile.sensorOrientation % 180 == 90) 1f / outputAspect else outputAspect
        return AspectPlan(
            targetAspect = outputAspect,
            outputAspect = outputAspect,
            jpegSize = output,
            previewSize = preview,
            crop = cropRect(profile.activeArray, outputAspect, 1f),
            displayAspect = display,
            contentAspect = display
        )
    }

    /** Largest buffer available; the capture always uses this so every ratio has data to crop. */
    fun largestSize(sizes: List<SizeI>): SizeI? = sizes.maxByOrNull { it.area }

    /** Longest edge kept when a crop has to be re-encoded; bounds the memory of high-MP sensors. */
    const val MAX_SAVED_EDGE = 4096

    /**
     * Size of the JPEG that will actually be written for [plan]: the centre crop in upright
     * orientation, downsampled so the longest edge stays within [MAX_SAVED_EDGE].
     */
    fun savedSize(plan: AspectPlan, sensorOrientation: Int): SizeI {
        val w = plan.jpegSize.width
        val h = plan.jpegSize.height
        if (w <= 0 || h <= 0) return plan.jpegSize
        val ratio = plan.targetAspect.coerceIn(0.2f, 5f)
        var cropW = w
        var cropH = (w / ratio).roundToInt()
        if (cropH > h) {
            cropH = h
            cropW = (h * ratio).roundToInt()
        }
        cropW = cropW.coerceIn(2, w)
        cropH = cropH.coerceIn(2, h)
        var sample = 1
        while (maxOf(cropW, cropH) / sample > MAX_SAVED_EDGE) sample *= 2
        val outW = (cropW / sample).coerceAtLeast(1)
        val outH = (cropH / sample).coerceAtLeast(1)
        return if (sensorOrientation % 180 == 90) SizeI(outH, outW) else SizeI(outW, outH)
    }

    /** Largest size whose aspect is closest to [target]. */
    fun bestSize(sizes: List<SizeI>, target: Float): SizeI? {
        if (sizes.isEmpty()) return null
        return sizes.minWithOrNull(
            compareBy<SizeI> { relativeAspectError(it.aspect, target) }
                .thenByDescending { it.area }
        )
    }

    /** Preview sizes are capped so the preview does not waste power at full still resolution. */
    fun previewSize(sizes: List<SizeI>, target: Float, maxWidth: Int): SizeI? {
        val usable = sizes.filter { it.width <= maxWidth || sizes.size == 1 }
        if (usable.isEmpty()) return bestSize(sizes, target)
        val near = usable.filter { relativeAspectError(it.aspect, target) <= ASPECT_TOLERANCE }
        val pool = near.ifEmpty { usable }
        return pool.maxByOrNull { it.area }
    }

    fun relativeAspectError(a: Float, b: Float): Float {
        if (a <= 0f || b <= 0f) return Float.MAX_VALUE
        return kotlin.math.abs(kotlin.math.ln(a / b))
    }

    /**
     * Largest [ratio] rectangle inside [active], divided by [zoom], centered.
     * Dimensions are rounded to even values, which keeps JPEG encoders happy.
     */
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

    /** How much of the preview content is visible when it is center-cropped into the view. */
    fun centerCropFractions(contentAspect: Float, viewAspect: Float): FloatArray {
        // [uScale, uOffset, vScale, vOffset]
        return if (contentAspect >= viewAspect) {
            val scale = viewAspect / contentAspect
            floatArrayOf(scale, (1f - scale) / 2f, 1f, 0f)
        } else {
            val scale = contentAspect / viewAspect
            floatArrayOf(1f, 0f, scale, (1f - scale) / 2f)
        }
    }
}
