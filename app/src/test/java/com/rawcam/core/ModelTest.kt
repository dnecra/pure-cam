package com.rawcam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {

    @Test
    fun sizeI_exposesAspectAreaAndText() {
        val size = SizeI(4000, 3000)

        assertEquals(4f / 3f, size.aspect, 1e-6f)
        assertEquals(12_000_000L, size.area)
        assertEquals("4000x3000", size.toString())
    }

    @Test
    fun rectI_exposesGeometryAndClamps() {
        val rect = RectI(100, 200, 1100, 900)

        assertEquals(1000, rect.width)
        assertEquals(700, rect.height)
        assertEquals(1000f / 700f, rect.aspect, 1e-6f)
        assertEquals(600, rect.centerX)
        assertEquals(550, rect.centerY)
        assertEquals("[100,200,1100,900]", rect.toString())

        val clamped = RectI(-50, 100, 1200, 2000).clampTo(RectI(0, 0, 1000, 1000))
        assertEquals(RectI(0, 100, 1000, 1000), clamped)
    }

    @Test
    fun rangeI_clampsAndInterpolates() {
        val range = RangeI(100, 300)

        assertEquals(100, range.clamp(0))
        assertEquals(200, range.clamp(200))
        assertEquals(300, range.clamp(500))
        assertTrue(range.contains(200))
        assertFalse(range.contains(301))
        assertEquals(200, range.span)
        assertEquals(0.5f, range.fraction(200), 1e-6f)
        assertEquals(200, range.at(0.5f))
        assertEquals(100, range.at(-1f))
        assertEquals(300, range.at(2f))
    }

    @Test
    fun zeroSpanRange_hasZeroFraction() {
        val range = RangeI(50, 50)

        assertEquals(0f, range.fraction(50), 0f)
        assertEquals(50, range.at(0.7f))
    }

    @Test
    fun rangeL_clampsAndContains() {
        val range = RangeL(1_000L, 5_000L)

        assertEquals(1_000L, range.clamp(0L))
        assertEquals(5_000L, range.clamp(9_000L))
        assertTrue(range.contains(3_000L))
        assertFalse(range.contains(5_001L))
        assertEquals("[1000..5000]", range.toString())
    }

    @Test
    fun rangeF_clamps() {
        val range = RangeF(0.5f, 2.5f)

        assertEquals(0.5f, range.clamp(0f), 0f)
        assertEquals(2.5f, range.clamp(9f), 0f)
        assertEquals(1.5f, range.clamp(1.5f), 0f)
    }

    @Test
    fun rangesRejectInvertedBounds() {
        assertThrows(IllegalArgumentException::class.java) { RangeI(10, 1) }
        assertThrows(IllegalArgumentException::class.java) { RangeL(10, 1) }
        assertThrows(IllegalArgumentException::class.java) { RangeF(10f, 1f) }
    }

    @Test
    fun noProcessingFlags_areTheSingleSourceOfTruth() {
        assertEquals(100, NoProcessingFlags.JPEG_QUALITY)
        assertEquals(SizeI(0, 0), NoProcessingFlags.THUMBNAIL_SIZE)
        assertEquals(100, NoProcessingFlags.POST_RAW_BOOST)
    }
}
