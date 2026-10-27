package com.rawcam.core

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExposureMathTest {

    private lateinit var previousLocale: Locale

    @Before
    fun useUsLocale() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun isoStops_areThirdStopStepsIncludingBothEnds() {
        val stops = ExposureMath.isoStops(RangeI(100, 800))

        assertEquals(listOf(100, 126, 159, 200, 252, 317, 400, 504, 635, 800), stops)
        assertTrue(stops.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun isoStops_handlesNarrowRange() {
        val stops = ExposureMath.isoStops(RangeI(100, 101))

        assertEquals(listOf(100, 101), stops)
    }

    @Test
    fun shutterStops_areThirdStopStepsWithinRange() {
        val stops = ExposureMath.shutterStops(RangeL(1_000_000L, 8_000_000L))

        assertEquals(1_000_000L, stops.first())
        assertEquals(8_000_000L, stops.last())
        assertTrue(stops.zipWithNext().all { (a, b) -> b > a })
        assertTrue(stops.all { it in 1_000_000L..8_000_000L })
    }

    @Test
    fun nearestIndex_picksClosestValue() {
        val values = listOf(100L, 200L, 300L)

        assertEquals(1, ExposureMath.nearestIndex(values, 240L))
        assertEquals(2, ExposureMath.nearestIndex(values, 260L))
        assertEquals(0, ExposureMath.nearestIndex(emptyList(), 260L))
    }

    @Test
    fun nearestIsoIndex_picksClosestValue() {
        val values = listOf(100, 200, 400)

        assertEquals(0, ExposureMath.nearestIsoIndex(values, 120))
        assertEquals(1, ExposureMath.nearestIsoIndex(values, 240))
        assertEquals(0, ExposureMath.nearestIsoIndex(emptyList(), 240))
    }

    @Test
    fun formatShutter_usesFractionsAndWholeSeconds() {
        assertEquals("--", ExposureMath.formatShutter(0L))
        assertEquals("--", ExposureMath.formatShutter(-1L))
        assertEquals("1/8000", ExposureMath.formatShutter(125_000L))
        assertEquals("1/125", ExposureMath.formatShutter(8_000_000L))
        assertEquals("1/30", ExposureMath.formatShutter(33_333_333L))
        assertEquals("1/2", ExposureMath.formatShutter(500_000_000L))
        assertEquals("2\"", ExposureMath.formatShutter(2_000_000_000L))
        assertEquals("4\"", ExposureMath.formatShutter(4_000_000_000L))
        assertEquals("1.6\"", ExposureMath.formatShutter(1_600_000_000L))
    }

    @Test
    fun formatExposure_combinesIsoAndShutter() {
        assertEquals("ISO 200  1/125", ExposureMath.formatExposure(200, 8_000_000L))
        assertEquals("200", ExposureMath.formatIso(200))
    }

    @Test
    fun previewValues_shortensOnlyLongExposures() {
        val profile = TestProfiles.lens(isoRange = RangeI(50, 3200))
        val short = ExposureValues(100, 50_000_000L)

        assertEquals(short, ExposureMath.previewValues(short, profile))
        assertFalse(ExposureMath.isPreviewApproximate(short))

        val long = ExposureValues(100, 1_000_000_000L)
        val clamped = ExposureMath.previewValues(long, profile)
        assertEquals(ExposureMath.MAX_PREVIEW_EXPOSURE_NS, clamped.shutterNs)
        assertEquals(1000, clamped.iso)
        assertTrue(ExposureMath.isPreviewApproximate(long))
    }

    @Test
    fun previewValues_clampsScaledIsoToDeviceMaximum() {
        val profile = TestProfiles.lens(isoRange = RangeI(50, 3200))

        val clamped = ExposureMath.previewValues(ExposureValues(400, 1_000_000_000L), profile)

        assertEquals(3200, clamped.iso)
        assertEquals(ExposureMath.MAX_PREVIEW_EXPOSURE_NS, clamped.shutterNs)
    }

    @Test
    fun initialManual_isSixtiethIsoOneHundredClamped() {
        val profile = TestProfiles.lens(
            isoRange = RangeI(200, 800),
            exposureRange = RangeL(1_000_000L, 1_000_000_000L)
        )

        val values = ExposureMath.initialManual(profile)

        assertEquals(200, values.iso)
        assertEquals(16_666_667L, values.shutterNs)
    }

    @Test
    fun initialManual_worksWithoutRanges() {
        val profile = TestProfiles.lens(isoRange = null, exposureRange = null)

        val values = ExposureMath.initialManual(profile)

        assertEquals(100, values.iso)
        assertEquals(16_666_667L, values.shutterNs)
    }

    @Test
    fun frameDuration_isAtLeastDefaultAndExposure() {
        assertEquals(33_333_333L, ExposureMath.frameDurationNs(10_000_000L, 1_000_000_000L))
        assertEquals(1_000_000_000L, ExposureMath.frameDurationNs(1_000_000_000L, 1_000_000_000L))
        assertEquals(2_000_000_000L, ExposureMath.frameDurationNs(2_000_000_000L, 500_000_000L))
    }

    @Test
    fun estimatedCaptureTimeout_allowsLongExposures() {
        assertEquals(3000L, ExposureMath.estimatedCaptureTimeoutMs(0L))
        assertEquals(3001L, ExposureMath.estimatedCaptureTimeoutMs(1_000_000L))
        assertEquals(4000L, ExposureMath.estimatedCaptureTimeoutMs(1_000_000_000L))
        assertEquals(13_000L, ExposureMath.estimatedCaptureTimeoutMs(10_000_000_000L))
    }

    @Test
    fun stopsBetween_countsFullStops() {
        assertEquals(1.0, ExposureMath.stopsBetween(2_000_000_000L, 1_000_000_000L), 1e-9)
        assertEquals(2.0, ExposureMath.stopsBetween(4_000_000_000L, 1_000_000_000L), 1e-9)
        assertEquals(0.0, ExposureMath.stopsBetween(0L, 1_000_000_000L), 1e-9)
    }
}
