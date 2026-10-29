package com.necra.purecam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoMathTest {

    private val lens = TestProfiles.lens()

    @Test
    fun recordableSizes_keepOnlyLandscape16By9Buffers() {
        val sizes = listOf(
            SizeI(1920, 1080),
            SizeI(1080, 1920),
            SizeI(4000, 3000),
            SizeI(3840, 2160),
            SizeI(1920, 1088),
            SizeI(8192, 4320)
        )

        assertEquals(
            listOf(SizeI(3840, 2160), SizeI(1920, 1088), SizeI(1920, 1080)),
            VideoMath.recordableSizes(sizes)
        )
    }

    @Test
    fun sizeFor_matchesQualityByWidthWithinTolerance() {
        val sizes = listOf(SizeI(3840, 2160), SizeI(1920, 1080), SizeI(1280, 720))

        assertEquals(SizeI(3840, 2160), VideoMath.sizeFor(VideoQuality.UHD_4K, sizes))
        assertEquals(SizeI(1920, 1080), VideoMath.sizeFor(VideoQuality.FHD_1080P, sizes))
        assertEquals(SizeI(1280, 720), VideoMath.sizeFor(VideoQuality.HD_720P, sizes))
        assertNull(VideoMath.sizeFor(VideoQuality.UHD_4K, listOf(SizeI(1920, 1080))))
    }

    @Test
    fun sizeFor_prefersCanonicalEncoderSizeOverASensorCrop() {
        // Pixel-style map: the 4:3 sensor's 16:9 crop is listed before the standard 4K buffer.
        val sizes = listOf(SizeI(4032, 3024), SizeI(4032, 2268), SizeI(3840, 2160), SizeI(1920, 1080))

        assertEquals(SizeI(3840, 2160), VideoMath.sizeFor(VideoQuality.UHD_4K, sizes))

        // When only the crop exists, it is still recordable.
        val cropOnly = listOf(SizeI(4032, 3024), SizeI(4032, 2268), SizeI(1920, 1080))
        assertEquals(SizeI(4032, 2268), VideoMath.sizeFor(VideoQuality.UHD_4K, cropOnly))
    }

    @Test
    fun availableQualities_andDefaultPreference() {
        val sizes = listOf(SizeI(1920, 1080), SizeI(1280, 720))

        assertEquals(
            listOf(VideoQuality.FHD_1080P, VideoQuality.HD_720P),
            VideoMath.availableQualities(sizes)
        )
        assertEquals(VideoQuality.FHD_1080P, VideoMath.defaultQuality(VideoMath.availableQualities(sizes)))
        assertEquals(
            VideoQuality.HD_720P,
            VideoMath.defaultQuality(listOf(VideoQuality.HD_720P, VideoQuality.UHD_4K))
        )
        assertNull(VideoMath.defaultQuality(emptyList()))
        assertEquals(emptyList<VideoQuality>(), VideoMath.availableQualities(listOf(SizeI(4000, 3000))))
    }

    @Test
    fun supportedFps_prefersFixedRanges() {
        assertEquals(
            listOf(24, 30),
            VideoMath.supportedFps(listOf(15..15, 24..24, 30..30, 30..60))
        )
        // No 24/30/60 range: the device's own fixed rate is offered instead.
        assertEquals(listOf(25), VideoMath.supportedFps(listOf(25..25)))
        // Only bounded ranges: candidate rates inside them are still usable.
        assertEquals(listOf(24, 30), VideoMath.supportedFps(listOf(15..30)))
        assertEquals(listOf(30), VideoMath.supportedFps(emptyList()))
    }

    @Test
    fun fpsFor_clampsToNearestAdvertisedRate() {
        assertEquals(30, VideoMath.fpsFor(30, listOf(24, 30, 60)))
        assertEquals(24, VideoMath.fpsFor(25, listOf(24, 30)))
        assertEquals(60, VideoMath.fpsFor(120, listOf(30, 60)))
        assertEquals(30, VideoMath.fpsFor(30, emptyList()))
    }

    @Test
    fun aeRangeFor_neverInventsARange() {
        assertEquals(30..30, VideoMath.aeRangeFor(30, listOf(24..24, 30..30)))
        assertEquals(15..30, VideoMath.aeRangeFor(30, listOf(15..30, 60..60)))
        assertNull(VideoMath.aeRangeFor(30, listOf(24..24, 60..60)))
        assertNull(VideoMath.aeRangeFor(30, emptyList()))
    }

    @Test
    fun frameDuration_isOneOverFps() {
        assertEquals(33_333_333L, VideoMath.frameDurationNs(30))
        assertEquals(41_666_666L, VideoMath.frameDurationNs(24))
        assertEquals(16_666_666L, VideoMath.frameDurationNs(60))
        assertEquals(1_000_000_000L, VideoMath.frameDurationNs(1))
    }

    @Test
    fun clampExposure_shortensShutterAndRaisesIsoToKeepBrightness() {
        val within = ExposureValues(400, 8_000_000L)
        assertEquals(within, VideoMath.clampExposure(within, 30, lens))
        assertFalse(VideoMath.isExposureClamped(within, 30))

        val long = ExposureValues(100, 100_000_000L)
        val clamped = VideoMath.clampExposure(long, 30, lens)

        assertEquals(33_333_333L, clamped.shutterNs)
        assertEquals(300, clamped.iso)
        assertTrue(VideoMath.isExposureClamped(long, 30))
    }

    @Test
    fun clampExposure_respectsTheLensIsoRange() {
        val tiny = TestProfiles.lens(isoRange = RangeI(50, 200))

        val clamped = VideoMath.clampExposure(ExposureValues(150, 100_000_000L), 30, tiny)

        assertEquals(200, clamped.iso)
    }

    @Test
    fun bitRate_scalesWithPixelsAndFps() {
        val hd = VideoMath.bitRate(SizeI(1280, 720), 30)
        val uhd = VideoMath.bitRate(SizeI(3840, 2160), 30)
        val uhd60 = VideoMath.bitRate(SizeI(3840, 2160), 60)

        assertTrue(uhd > hd)
        assertTrue(uhd60 > uhd)
        assertEquals(2_000_000, VideoMath.bitRate(SizeI(16, 16), 1))
    }

    @Test
    fun formatting_isStable() {
        assertEquals("0:00", VideoMath.formatDuration(0))
        assertEquals("0:07", VideoMath.formatDuration(7_999))
        assertEquals("12:34", VideoMath.formatDuration(((12 * 60) + 34) * 1_000L))
        assertEquals("5.0 Mbps", VideoMath.formatBitRate(5_000_000))
    }
}
