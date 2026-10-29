package com.necra.purecam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.necra.purecam.core.AspectMath
import com.necra.purecam.core.ExportFormat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Writes a captured JPEG to MediaStore.
 *
 * The sensor always streams its largest JPEG. When the selected ratio differs from that
 * buffer the frame is centre-cropped and rotated upright in software. A JPEG capture that
 * already matches the requested ratio is written untouched, EXIF orientation included.
 * HEIC captures are always re-encoded from the same JPEG source.
 */
class PhotoSaver(
    private val context: Context,
    private val onSaved: (Uri, Int) -> Unit,
    private val onError: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "purecam-save") }
    private val closed = AtomicBoolean(false)

    /**
     * @param cropAspect requested ratio in sensor coordinates (width / height). Pass 0 to save
     *   the camera's bytes untouched.
     * @param orientationDegrees clockwise rotation needed to make the frame upright; used only
     *   when a crop or a HEIC export forces a decode/re-encode.
     * @param format container to write. JPEG is byte-identical when no crop is needed.
     */
    fun save(
        jpeg: ByteArray,
        cropAspect: Float = 0f,
        orientationDegrees: Int = 0,
        format: ExportFormat = ExportFormat.JPEG,
        timestamp: Long = System.currentTimeMillis()
    ) {
        if (closed.get()) return
        executor.execute {
            val encoded = encode(jpeg, cropAspect, orientationDegrees, format)

            val resolver = context.contentResolver
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
            val name = "IMG_$stamp.${encoded.format.extension}"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, encoded.format.mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
                put(MediaStore.Images.Media.DATE_TAKEN, timestamp)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            var uri: Uri? = null
            try {
                uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("MediaStore insert returned null")
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(encoded.bytes)
                    out.flush()
                } ?: throw IOException("Could not open MediaStore output")
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                Log.i(
                    "PureCam/Save",
                    "saved $name ${encoded.bytes.size} bytes (capture ${jpeg.size}) " +
                        "sha256=${sha256(encoded.bytes).take(16)}"
                )
                mainHandler.post { onSaved(uri, encoded.bytes.size) }
            } catch (t: Throwable) {
                Log.e("PureCam/Save", "save failed", t)
                if (uri != null) {
                    runCatching { resolver.delete(uri, null, null) }
                }
                mainHandler.post { onError(t.message ?: "Could not save photo") }
            }
        }
    }

    fun shutdown() {
        if (closed.compareAndSet(false, true)) executor.shutdown()
    }

    private data class Encoded(val bytes: ByteArray, val format: ExportFormat)

    /**
     * Returns the bytes to write. JPEG captures that need no crop keep the camera's original
     * bytes; everything else is decoded once, cropped, rotated and re-compressed.
     */
    private fun encode(
        jpeg: ByteArray,
        cropAspect: Float,
        orientationDegrees: Int,
        format: ExportFormat
    ): Encoded {
        if (format == ExportFormat.JPEG && !needsCrop(jpeg, cropAspect)) {
            return Encoded(jpeg, ExportFormat.JPEG)
        }
        return try {
            transcode(jpeg, cropAspect, orientationDegrees, format)
        } catch (t: Throwable) {
            // Never lose the photo because the conversion failed.
            Log.w("PureCam/Save", "encode failed; saving the full frame as JPEG", t)
            Encoded(jpeg, ExportFormat.JPEG)
        }
    }

    /** True when the requested ratio differs enough from the captured buffer to be worth a crop. */
    private fun needsCrop(jpeg: ByteArray, cropAspect: Float): Boolean {
        if (cropAspect <= 0f) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return false
        val native = options.outWidth.toFloat() / options.outHeight.toFloat()
        return abs(native - cropAspect) / native > CROP_TOLERANCE
    }

    private fun transcode(
        jpeg: ByteArray,
        cropAspect: Float,
        orientationDegrees: Int,
        format: ExportFormat
    ): Encoded {
        val decoder = BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size, false)
            ?: return Encoded(jpeg, ExportFormat.JPEG)
        try {
            val width = decoder.width
            val height = decoder.height
            if (width <= 0 || height <= 0) return Encoded(jpeg, ExportFormat.JPEG)

            var cropWidth = width
            var cropHeight = height
            if (cropAspect > 0f) {
                val ratio = cropAspect.coerceIn(0.2f, 5f)
                cropHeight = (width / ratio).roundToInt()
                if (cropHeight > height) {
                    cropHeight = height
                    cropWidth = (height * ratio).roundToInt()
                }
                cropWidth = cropWidth.coerceIn(2, width)
                cropHeight = cropHeight.coerceIn(2, height)
            }
            val left = (width - cropWidth) / 2
            val top = (height - cropHeight) / 2

            var sample = 1
            while (maxOf(cropWidth, cropHeight) / sample > AspectMath.MAX_SAVED_EDGE) sample *= 2

            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val region = decoder.decodeRegion(
                Rect(left, top, left + cropWidth, top + cropHeight),
                options
            ) ?: return Encoded(jpeg, ExportFormat.JPEG)

            val upright = if (orientationDegrees % 360 == 0) {
                region
            } else {
                val matrix = Matrix().apply { postRotate(orientationDegrees.toFloat()) }
                Bitmap.createBitmap(region, 0, 0, region.width, region.height, matrix, true)
            }
            try {
                if (format == ExportFormat.HEIC) {
                    HeicEncoder.encode(upright, context.cacheDir)?.let {
                        return Encoded(it, ExportFormat.HEIC)
                    }
                    // The device cannot encode HEIF: fall back to JPEG rather than failing.
                    Log.w("PureCam/Save", "HEIC encode unavailable; writing JPEG instead")
                }
                compress(upright, Bitmap.CompressFormat.JPEG)?.let {
                    return Encoded(it, ExportFormat.JPEG)
                }
                return Encoded(jpeg, ExportFormat.JPEG)
            } finally {
                if (upright !== region) upright.recycle()
                region.recycle()
            }
        } finally {
            decoder.recycle()
        }
    }

    private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat): ByteArray? {
        val out = ByteArrayOutputStream()
        return runCatching {
            if (bitmap.compress(format, JPEG_QUALITY, out)) out.toByteArray() else null
        }.getOrNull()
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val JPEG_QUALITY = 95
        const val CROP_TOLERANCE = 0.01f
    }
}
