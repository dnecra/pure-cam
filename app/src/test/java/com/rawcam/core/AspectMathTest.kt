package com.rawcam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AspectMathTest {

    @Test
    fun supportedAspects_keepsOnlyNativeRatiosAndAlwaysFull() {
        val sizes = listOf(SizeI(4000, 3000), SizeI(4000, 2250), SizeI(3000, 3000))

        val aspects = AspectMath.supportedAspects(sizes)

        assertEquals(listOf(Aspect.R4_3, Aspect.R16_9, Aspect.R1_1, Aspect.FULL), aspects)
    }

    @Test
    fun supportedAspects_emptySizesStillOffersWorkingChoice() {
        assertEquals(listOf(Aspect.R4_3, Aspect.FULL), AspectMath.supportedAspects(emptyList()))
    }

    @Test
    fun supportedAspects_unusualRatiosFallBackToFourThree() {
        val aspects = AspectMath.supportedAspects(listOf(SizeI(1000, 800)))

        assertEquals(listOf(Aspect.R4_3, Aspect.FULL), aspects)
    }

    @Test
    fun relativeAspectError_isSymmetricAndRejectsNonPositive() {
        val a = AspectMath.relativeAspectError(4f / 3f, 16f / 9f)
        val b = AspectMath.relativeAspectError(16f / 9f, 4f / 3f)

        assertEquals(a, b, 1e-6f)
        assertTrue(a > 0f)
        assertEquals(Float.MAX_VALUE, AspectMath.relativeAspectError(0f, 1f), 0f)
    }

    @Test
    fun bestSize_prefersClosestAspectThenLargestArea() {
        val sizes = listOf(
            SizeI(1000, 750),
            SizeI(2000, 1500),
            SizeI(1920, 1080)
        )

        assertEquals(SizeI(2000, 1500), AspectMath.bestSize(sizes, 4f / 3f))
        assertEquals(SizeI(1920, 1080), AspectMath.bestSize(sizes, 16f / 9f))
        assertEquals(null, AspectMath.bestSize(emptyList(), 4f / 3f))
    }

    @Test
    fun previewSize_respectsWidthCapAndAspect() {
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
    fun cropRect_fullSensorAtZoomOne() {
        val crop = AspectMath.cropRect(RectI(0, 0, 4000, 3000), 4f / 3f, 1f)

        assertEquals(RectI(0, 0, 4000, 3000), crop)
    }

    @Test
    fun cropRect_squareIsCentered() {
        val crop = AspectMath.cropRect(RectI(0, 0, 4000, 3000), 1f, 1f)

        assertEquals(RectI(500, 0, 3500, 3000), crop)
    }

    @Test
    fun cropRect_zoomIsCenteredAndKeepsAspect() {
        val crop = AspectMath.cropRect(RectI(0, 0, 4000, 3000), 4f / 3f, 2f)

        assertEquals(RectI(1000, 750, 3000, 2250), crop)
        assertEquals(4f / 3f, crop.aspect, 1e-6f)
    }

    @Test
    fun cropRect_offsetsActiveArrayAndForcesEvenDimensions() {
        val crop = AspectMath.cropRect(RectI(100, 50, 4101, 3051), 4f / 3f, 1f)

        assertEquals(0, crop.width % 2)
        assertEquals(0, crop.height % 2)
        assertEquals(100 + (4001 - crop.width) / 2, crop.left)
        assertEquals(50 + (3001 - crop.height) / 2, crop.top)
        assertTrue(crop.right <= 4101)
        assertTrue(crop.bottom <= 3051)
    }

    @Test
    fun cropRect_invalidRatioIsClampedToUsableRange() {
        val narrow = AspectMath.cropRect(RectI(0, 0, 4000, 3000), 0f, 1f)
        val wide = AspectMath.cropRect(RectI(0, 0, 4000, 3000), 100f, 1f)

        assertTrue(narrow.width > 0 && narrow.height > 0)
        assertTrue(wide.width > 0 && wide.height > 0)
        assertTrue(narrow.height <= 3000)
    }

    @Test
    fun sensorTargetAspect_invertsScreenAspectForRotatedSensors() {
        assertEquals(4f / 3f, AspectMath.sensorTargetAspect(Aspect.R4_3, 0.5f, 90), 1e-6f)
        assertEquals(2f, AspectMath.sensorTargetAspect(Aspect.FULL, 0.5f, 90), 1e-6f)
        assertEquals(0.5f, AspectMath.sensorTargetAspect(Aspect.FULL, 0.5f, 0), 1e-6f)
    }

    @Test
    fun plan_sizesJpegPreviewAndDisplayForRotatedSensor() {
        val profile = TestProfiles.lens(sensorOrientation = 90)

        val plan = AspectMath.plan(profile, Aspect.R4_3, screenAspect = 0.5f)

        assertEquals(4f / 3f, plan.targetAspect, 1e-6f)
        assertEquals(4f / 3f, plan.outputAspect, 1e-6f)
        assertEquals(SizeI(4000, 3000), plan.jpegSize)
        assertEquals(SizeI(1920, 1440), plan.previewSize)
        assertEquals(0.75f, plan.displayAspect, 1e-6f)
        assertEquals(RectI(0, 0, 4000, 3000), plan.crop)
    }

    @Test
    fun plan_fullModeUsesScreenAspectForRotatedSensor() {
        val profile = TestProfiles.lens(sensorOrientation = 90)

        val plan = AspectMath.plan(profile, Aspect.FULL, screenAspect = 0.5f)

        assertEquals(2f, plan.targetAspect, 1e-6f)
        assertEquals(SizeI(4000, 2250), plan.jpegSize)
        assertEquals(1f / (4000f / 2250f), plan.displayAspect, 1e-6f)
    }

    @Test
    fun plan_fallsBackToActiveArrayWithoutJpegSizes() {
        val profile = TestProfiles.lens(jpegSizes = emptyList(), previewSizes = emptyList())

        val plan = AspectMath.plan(profile, Aspect.R4_3, screenAspect = 0.5f)

        assertEquals(SizeI(4000, 3000), plan.jpegSize)
        assertEquals(SizeI(4000, 3000), plan.previewSize)
    }

    @Test
    fun centerCropFractions_matchesTransformDirection() {
        val wide = AspectMath.centerCropFractions(16f / 9f, 4f / 3f)
        assertEquals(0.75f, wide[0], 1e-6f)
        assertEquals(0.125f, wide[1], 1e-6f)
        assertEquals(1f, wide[2], 1e-6f)
        assertEquals(0f, wide[3], 1e-6f)

        val tall = AspectMath.centerCropFractions(4f / 3f, 16f / 9f)
        assertEquals(1f, tall[0], 1e-6f)
        assertEquals(0f, tall[1], 1e-6f)
        assertEquals(0.75f, tall[2], 1e-6f)
        assertEquals(0.125f, tall[3], 1e-6f)
    }

    @Test
    fun plan_neverReturnsNullSizes() {
        val profile = TestProfiles.lens(
            activeArray = RectI(0, 0, 1, 1),
            jpegSizes = emptyList(),
            previewSizes = emptyList()
        )

        val plan = AspectMath.plan(profile, Aspect.R4_3, screenAspect = 0.5f)

        assertNotNull(plan.jpegSize)
        assertNotNull(plan.previewSize)
        assertTrue(plan.crop.width > 0)
        assertTrue(plan.crop.height > 0)
    }
}
