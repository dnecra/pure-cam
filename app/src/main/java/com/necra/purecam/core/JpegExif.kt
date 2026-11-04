package com.necra.purecam.core

/**
 * Dependency-free EXIF orientation reader/writer for JPEG byte arrays.
 *
 * Camera2 writes the requested rotation into the EXIF Orientation tag (0x0112) instead of
 * rotating the JPEG pixels, so the selfie mirror can be applied losslessly by flipping that tag
 * to its horizontally mirrored counterpart. No decode or re-encode is involved.
 */
object JpegExif {

    /** EXIF orientation values, as defined by the TIFF/EXIF specification. */
    const val NORMAL = 1
    const val FLIP_HORIZONTAL = 2
    const val ROTATE_180 = 3
    const val FLIP_VERTICAL = 4
    const val TRANSPOSE = 5
    const val ROTATE_90 = 6
    const val TRANSVERSE = 7
    const val ROTATE_270 = 8

    private const val ORIENTATION_TAG = 0x0112
    private const val TYPE_SHORT = 3

    /**
     * Returns the EXIF value that draws [orientation] as a horizontal mirror image.
     *
     * A horizontal flip in display space composes with each orientation by swapping the two
     * values of its mirrored pair: 1↔2, 3↔4, 5↔6, 7↔8.
     */
    fun mirroredOrientation(orientation: Int): Int = when (orientation) {
        NORMAL -> FLIP_HORIZONTAL
        FLIP_HORIZONTAL -> NORMAL
        ROTATE_180 -> FLIP_VERTICAL
        FLIP_VERTICAL -> ROTATE_180
        TRANSPOSE -> ROTATE_90
        ROTATE_90 -> TRANSPOSE
        TRANSVERSE -> ROTATE_270
        ROTATE_270 -> TRANSVERSE
        else -> orientation
    }

    /** Reads the EXIF Orientation tag, defaulting to [NORMAL] when it is absent/unreadable. */
    fun readOrientation(jpeg: ByteArray): Int {
        val slot = findOrientationSlot(jpeg) ?: return NORMAL
        val value = readU16(jpeg, slot.offset, slot.littleEndian)
        return if (value in NORMAL..ROTATE_270) value else NORMAL
    }

    /**
     * Overwrites the EXIF Orientation tag in place and returns true on success. The JPEG pixels
     * and every other byte are left untouched.
     */
    fun writeOrientation(jpeg: ByteArray, orientation: Int): Boolean {
        if (orientation !in NORMAL..ROTATE_270) return false
        val slot = findOrientationSlot(jpeg) ?: return false
        writeU16(jpeg, slot.offset, orientation, slot.littleEndian)
        return true
    }

    private data class OrientationSlot(val offset: Int, val littleEndian: Boolean)

    private fun findOrientationSlot(jpeg: ByteArray): OrientationSlot? {
        if (jpeg.size < 4) return null
        if (u8(jpeg, 0) != 0xFF || u8(jpeg, 1) != 0xD8) return null

        var pos = 2
        while (pos + 4 <= jpeg.size) {
            if (u8(jpeg, pos) != 0xFF) return null
            val marker = u8(jpeg, pos + 1)
            // Standalone markers carry no length field.
            if (marker == 0x01 || marker in 0xD0..0xD7 || marker == 0xD8) {
                pos += 2
                continue
            }
            // Start of scan: EXIF metadata can only appear before the image data.
            if (marker == 0xDA || marker == 0xD9) return null

            val segmentLength = readU16(jpeg, pos + 2, littleEndian = false)
            if (segmentLength < 2) return null
            val payload = pos + 4
            val segmentEnd = payload + segmentLength - 2
            if (segmentEnd > jpeg.size) return null

            if (marker == 0xE1 && segmentLength >= 8 && isExifHeader(jpeg, payload)) {
                return findTiffOrientation(jpeg, payload + 6, segmentEnd)
            }
            pos = segmentEnd
        }
        return null
    }

    private fun isExifHeader(jpeg: ByteArray, offset: Int): Boolean =
        jpeg.size >= offset + 6 &&
            u8(jpeg, offset) == 'E'.code &&
            u8(jpeg, offset + 1) == 'x'.code &&
            u8(jpeg, offset + 2) == 'i'.code &&
            u8(jpeg, offset + 3) == 'f'.code &&
            u8(jpeg, offset + 4) == 0 &&
            u8(jpeg, offset + 5) == 0

    private fun findTiffOrientation(jpeg: ByteArray, tiff: Int, end: Int): OrientationSlot? {
        if (tiff + 8 > end) return null
        val littleEndian = when {
            u8(jpeg, tiff) == 'I'.code && u8(jpeg, tiff + 1) == 'I'.code -> true
            u8(jpeg, tiff) == 'M'.code && u8(jpeg, tiff + 1) == 'M'.code -> false
            else -> return null
        }
        if (readU16(jpeg, tiff + 2, littleEndian) != 42) return null

        val ifdOffset = readU32(jpeg, tiff + 4, littleEndian)
        val ifd = tiff + ifdOffset
        if (ifd < tiff || ifd + 2 > end) return null

        val entryCount = readU16(jpeg, ifd, littleEndian)
        for (index in 0 until entryCount) {
            val entry = ifd + 2 + index * 12
            if (entry + 12 > end) return null
            if (readU16(jpeg, entry, littleEndian) != ORIENTATION_TAG) continue
            val type = readU16(jpeg, entry + 2, littleEndian)
            val count = readU32(jpeg, entry + 4, littleEndian)
            if (type != TYPE_SHORT || count < 1) return null
            return OrientationSlot(entry + 8, littleEndian)
        }
        return null
    }

    private fun u8(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xFF

    private fun readU16(bytes: ByteArray, offset: Int, littleEndian: Boolean): Int {
        val a = u8(bytes, offset)
        val b = u8(bytes, offset + 1)
        return if (littleEndian) a or (b shl 8) else (a shl 8) or b
    }

    private fun readU32(bytes: ByteArray, offset: Int, littleEndian: Boolean): Int {
        val a = u8(bytes, offset)
        val b = u8(bytes, offset + 1)
        val c = u8(bytes, offset + 2)
        val d = u8(bytes, offset + 3)
        return if (littleEndian) {
            a or (b shl 8) or (c shl 16) or (d shl 24)
        } else {
            (a shl 24) or (b shl 16) or (c shl 8) or d
        }
    }

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int, littleEndian: Boolean) {
        if (littleEndian) {
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        } else {
            bytes[offset] = ((value shr 8) and 0xFF).toByte()
            bytes[offset + 1] = (value and 0xFF).toByte()
        }
    }
}
