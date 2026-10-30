package com.necra.purecam.core

/** Stream sizes and upright ratios used to keep the preview framed without changing captures. */
data class AspectPlan(
    /** Ratio of the camera or encoder output buffer, before display rotation. */
    val outputAspect: Float,
    val jpegSize: SizeI,
    val previewSize: SizeI,
    /** Upright ratio used to fit the unmodified stream in the viewfinder. */
    val displayAspect: Float,
    val contentAspect: Float
)

object AspectMath {

    fun planNative(profile: LensProfile, maxPreviewWidth: Int = 1920): AspectPlan {
        val jpeg = largestSize(profile.jpegSizes)
            ?: SizeI(profile.activeArray.width, profile.activeArray.height)
        return createPlan(profile, jpeg, jpeg.aspect, profile.previewSizes, maxPreviewWidth)
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
