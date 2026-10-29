package com.necra.purecam.core

/** Everything needed to size the preview view, the JPEG stream and the sensor crop. */
data class AspectPlan(
    /** Requested aspect in sensor coordinates (width / height). */
    val targetAspect: Float,
    /** Aspect of the JPEG buffer that was actually available (width / height). */
    val outputAspect: Float,
    val jpegSize: SizeI,
    val previewSize: SizeI,
    /** Crop at zoom 1. Zoom is applied by RequestBuilder. */
    val crop: RectI,
    /** Aspect of the upright preview (width / height). */
    val displayAspect: Float
)

object AspectMath {

    private const val ASPECT_TOLERANCE = 0.02f

    /** Native still ratios the device can actually write without cropping/re-encoding. */
    fun supportedAspects(jpegSizes: List<SizeI>): List<Aspect> {
        if (jpegSizes.isEmpty()) return listOf(Aspect.R4_3, Aspect.FULL)
        val result = Aspect.entries.filter { aspect ->
            if (aspect.isFull) return@filter true
            jpegSizes.any { relativeAspectError(it.aspect, aspect.ratio) <= ASPECT_TOLERANCE }
        }.toMutableList()
        if (result.none { !it.isFull }) {
            // No standard ratio matched; keep 4:3 so there is always a working choice.
            result.add(0, Aspect.R4_3)
        }
        return result.distinct()
    }

    /**
     * Screen ratio is width / height in the current (portrait-locked) display.
     * A rotated sensor needs the inverse ratio for an upright framing.
     */
    fun sensorTargetAspect(aspect: Aspect, screenAspect: Float, sensorOrientation: Int): Float {
        if (!aspect.isFull) return aspect.ratio
        val safe = screenAspect.coerceIn(0.3f, 3.3f)
        return if (sensorOrientation % 180 == 90) 1f / safe else safe
    }

    fun plan(
        profile: LensProfile,
        aspect: Aspect,
        screenAspect: Float,
        maxPreviewWidth: Int = 1920
    ): AspectPlan {
        val target = sensorTargetAspect(aspect, screenAspect, profile.sensorOrientation)
        val jpeg = bestSize(profile.jpegSizes, target)
            ?: SizeI(profile.activeArray.width, profile.activeArray.height)
        val outputAspect = jpeg.aspect
        val preview = previewSize(profile.previewSizes, outputAspect, maxPreviewWidth) ?: jpeg
        val display = if (profile.sensorOrientation % 180 == 90) 1f / outputAspect else outputAspect
        return AspectPlan(
            targetAspect = target,
            outputAspect = outputAspect,
            jpegSize = jpeg,
            previewSize = preview,
            crop = cropRect(profile.activeArray, outputAspect, 1f),
            displayAspect = display
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
            displayAspect = display
        )
    }

    /** Largest size whose aspect is closest to [target]. */
    fun bestSize(sizes: List<SizeI>, target: Float): SizeI? {        if (sizes.isEmpty()) return null
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
