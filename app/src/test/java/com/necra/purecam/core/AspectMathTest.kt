package com.necra.purecam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AspectMathTest {

    @Test
    fun planNative_keepsLargestJpegRatioAndFitsRotatedPreview() {
        val profile = TestProfiles.lens(sensorOrientation = 90)

        val plan = AspectMath.planNative(profile)

        assertEquals(4f / 3f, plan.outputAspect, 1e-6f)
        assertEquals(SizeI(4000, 3000), plan.jpegSize)
        assertEquals(SizeI(1920, 1440), plan.previewSize)
        assertEquals(0.75f, plan.displayAspect, 1e-6f)
        assertEquals(0.75f, plan.contentAspect, 1e-6f)
    }

    @Test
    fun planForOutput_keepsTheEncoderStreamRatio() {
        val profile = TestProfiles.lens(sensorOrientation = 90)

        val plan = AspectMath.planForOutput(profile, SizeI(1920, 1080))

        assertEquals(16f / 9f, plan.outputAspect, 1e-6f)
        assertEquals(9f / 16f, plan.displayAspect, 1e-6f)
    }

    @Test
    fun previewSize_respectsWidthCapAndPrefersMatchingStreamRatio() {
        val sizes = listOf(
            SizeI(4000, 3000),
            SizeI(1920, 1440),
            SizeI(1280, 960),
            SizeI(1920, 1080)
        )

        assertEquals(SizeI(1920, 1440), AspectMath.previewSize(sizes, 4f / 3f, 1920))
        assertEquals(SizeI(1280, 960), AspectMath.previewSize(sizes, 4f / 3f, 1500))
    }

    @Test
    fun cropRect_zoomIsCenteredAndKeepsStreamRatio() {
        val crop = AspectMath.cropRect(RectI(0, 0, 4000, 3000), 4f / 3f, 2f)

        assertEquals(RectI(1000, 750, 3000, 2250), crop)
        assertEquals(4f / 3f, crop.aspect, 1e-6f)
    }

    @Test
    fun cropRect_invalidRatioIsClampedToUsableRange() {
        val narrow = AspectMath.cropRect(RectI(0, 0, 4000, 3000), 0f, 1f)
        val wide = AspectMath.cropRect(RectI(0, 0, 4000, 3000), 100f, 1f)

        assertTrue(narrow.width > 0 && narrow.height > 0)
        assertTrue(wide.width > 0 && wide.height > 0)
    }
}
