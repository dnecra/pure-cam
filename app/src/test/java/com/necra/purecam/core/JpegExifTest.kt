package com.necra.purecam.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegExifTest {

    @Test
    fun mirroredOrientation_swapsMirrorPairs() {
        assertEquals(JpegExif.FLIP_HORIZONTAL, JpegExif.mirroredOrientation(JpegExif.NORMAL))
        assertEquals(JpegExif.NORMAL, JpegExif.mirroredOrientation(JpegExif.FLIP_HORIZONTAL))
        assertEquals(JpegExif.FLIP_VERTICAL, JpegExif.mirroredOrientation(JpegExif.ROTATE_180))
        assertEquals(JpegExif.ROTATE_180, JpegExif.mirroredOrientation(JpegExif.FLIP_VERTICAL))
        assertEquals(JpegExif.ROTATE_90, JpegExif.mirroredOrientation(JpegExif.TRANSPOSE))
        assertEquals(JpegExif.TRANSPOSE, JpegExif.mirroredOrientation(JpegExif.ROTATE_90))
        assertEquals(JpegExif.ROTATE_270, JpegExif.mirroredOrientation(JpegExif.TRANSVERSE))
        assertEquals(JpegExif.TRANSVERSE, JpegExif.mirroredOrientation(JpegExif.ROTATE_270))
    }

    @Test
    fun readOrientation_littleEndian() {
        assertEquals(JpegExif.ROTATE_180, JpegExif.readOrientation(exifJpeg(3, littleEndian = true)))
        assertEquals(JpegExif.ROTATE_90, JpegExif.readOrientation(exifJpeg(6, littleEndian = true)))
    }

    @Test
    fun readOrientation_bigEndian() {
        assertEquals(JpegExif.ROTATE_180, JpegExif.readOrientation(exifJpeg(3, littleEndian = false)))
        assertEquals(JpegExif.ROTATE_270, JpegExif.readOrientation(exifJpeg(8, littleEndian = false)))
    }

    @Test
    fun writeOrientation_replacesTagInPlace() {
        for (littleEndian in listOf(true, false)) {
            val jpeg = exifJpeg(JpegExif.NORMAL, littleEndian)
            assertTrue(JpegExif.writeOrientation(jpeg, JpegExif.TRANSVERSE))
            assertEquals(JpegExif.TRANSVERSE, JpegExif.readOrientation(jpeg))
        }
    }

    @Test
    fun writeOrientation_onlyTouchesTheTagValue() {
        val littleEndian = exifJpeg(JpegExif.ROTATE_180, littleEndian = true)
        val bigEndian = exifJpeg(JpegExif.ROTATE_180, littleEndian = false)

        assertTrue(JpegExif.writeOrientation(littleEndian, JpegExif.FLIP_VERTICAL))
        assertTrue(JpegExif.writeOrientation(bigEndian, JpegExif.FLIP_VERTICAL))

        assertEquals(JpegExif.FLIP_VERTICAL, JpegExif.readOrientation(littleEndian))
        assertEquals(JpegExif.FLIP_VERTICAL, JpegExif.readOrientation(bigEndian))
    }

    @Test
    fun jpegWithoutExif_isLeftAlone() {
        val plain = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        assertEquals(JpegExif.NORMAL, JpegExif.readOrientation(plain))
        assertFalse(JpegExif.writeOrientation(plain, JpegExif.FLIP_HORIZONTAL))
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()),
            plain
        )
    }

    /** Builds a JPEG with a single APP1/EXIF IFD0 containing an Orientation tag. */
    private fun exifJpeg(orientation: Int, littleEndian: Boolean): ByteArray {
        val tiff = mutableListOf<Int>()
        if (littleEndian) {
            tiff += listOf('I'.code, 'I'.code, 42, 0, 8, 0, 0, 0)
            tiff += listOf(1, 0)
            tiff += listOf(0x12, 0x01, 3, 0)
            tiff += listOf(1, 0, 0, 0)
            tiff += listOf(orientation, 0, 0, 0)
            tiff += listOf(0, 0, 0, 0)
        } else {
            tiff += listOf('M'.code, 'M'.code, 0, 42, 0, 0, 0, 8)
            tiff += listOf(0, 1)
            tiff += listOf(0x01, 0x12, 0, 3)
            tiff += listOf(0, 0, 0, 1)
            tiff += listOf(0, orientation, 0, 0)
            tiff += listOf(0, 0, 0, 0)
        }

        val payload = mutableListOf<Int>()
        payload += listOf('E'.code, 'x'.code, 'i'.code, 'f'.code, 0, 0)
        payload += tiff

        val out = mutableListOf<Int>()
        out += listOf(0xFF, 0xD8, 0xFF, 0xE1)
        val length = payload.size + 2
        out += listOf((length shr 8) and 0xFF, length and 0xFF)
        out += payload
        out += listOf(0xFF, 0xD9)
        return out.map { it.toByte() }.toByteArray()
    }
}
