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

    /** Ratios the aspect maths understands, in cycle order. */
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
     * Framing plan for a requested ratio. Production stills use [rawPlan] and apply the selected
     * ratio at save time with [saveCropAspect]; this helper remains for the aspect maths and its
     * tests, where the preview follows the requested ratio while the capture stays uncropped.
     */
    fun plan(
        profile: LensProfile,
        aspect: Aspect,
        screenAspect: Float,
        maxPreviewWidth: Int = 1920,
        fullscreenAspect: Float? = null
    ): AspectPlan {
        val target = if (aspect.isFull && fullscreenAspect != null &&
            fullscreenAspect.isFinite() && fullscreenAspect > 0.1f
        ) {
            // "Full" fills the viewfinder box, whatever ratio that box currently has.
            val safe = fullscreenAspect.coerceIn(0.3f, 3.3f)
            if (profile.sensorOrientation % 180 == 90) 1f / safe else safe
        } else {
            sensorTargetAspect(aspect, screenAspect, profile.sensorOrientation)
        }
        // Stills always use the largest buffer the lens offers; the ratio is a centre crop.
        val jpeg = largestSize(profile.jpegSizes)
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
     * Plan for the untouched still stream: the preview uses the native JPEG aspect and no crop is
     * applied. Used for the photo viewfinder now that cropping is a save-time option.
     */
    fun rawPlan(profile: LensProfile, maxPreviewWidth: Int = 1920): AspectPlan {
        val jpeg = largestSize(profile.jpegSizes)
            ?: SizeI(profile.activeArray.width, profile.activeArray.height)
        val outputAspect = jpeg.aspect
        val preview = previewSize(profile.previewSizes, outputAspect, maxPreviewWidth) ?: jpeg
        val display = if (profile.sensorOrientation % 180 == 90) 1f / outputAspect else outputAspect
        return AspectPlan(
            targetAspect = outputAspect,
            outputAspect = outputAspect,
            jpegSize = jpeg,
            previewSize = preview,
            crop = cropRect(profile.activeArray, outputAspect, 1f),
            displayAspect = display,
            contentAspect = display
        )
    }

    /**
     * Crop ratio in sensor coordinates for a save option. Fullscreen uses the current display
     * aspect so the saved frame matches the phone screen. RAW returns 0, meaning "do not crop".
     */
    fun saveCropAspect(crop: SaveCrop, screenAspect: Float, sensorOrientation: Int): Float =
        when (crop) {
            SaveCrop.RAW -> 0f
            SaveCrop.R16_9 -> sensorTargetAspect(Aspect.R16_9, screenAspect, sensorOrientation)
            SaveCrop.FULLSCREEN -> sensorTargetAspect(Aspect.FULL, screenAspect, sensorOrientation)
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
    fun savedSize(plan: AspectPlan, sensorOrientation: Int): SizeI =
        savedSize(plan.jpegSize, plan.targetAspect, sensorOrientation)

    /**
     * Size written for [jpeg] after a centre crop. A non-positive [cropAspect] means the original
     * bytes are kept, so the result is the full-resolution buffer.
     */
    fun savedSize(jpeg: SizeI, cropAspect: Float, sensorOrientation: Int): SizeI {
        val w = jpeg.width
        val h = jpeg.height
        if (w <= 0 || h <= 0) return jpeg
        if (cropAspect <= 0f) return jpeg
        val ratio = cropAspect.coerceIn(0.2f, 5f)
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
