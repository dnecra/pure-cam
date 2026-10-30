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
 * The camera always streams its largest native JPEG. When the frame needs no crop and no
 * mirroring the original bytes are stored untouched, EXIF orientation included. Otherwise the
 * frame is centre-cropped, rotated upright and, for the front camera, mirrored in software.
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
     * @param cropAspect requested ratio in sensor coordinates (width / height). Pass 0 to keep
     *   the camera's full frame.
     * @param orientationDegrees clockwise rotation needed to make the frame upright; used only
     *   when a crop or the mirror forces a decode/re-encode.
     * @param mirror flips the saved frame horizontally, matching the front-camera preview.
     */
    fun save(
        jpeg: ByteArray,
        cropAspect: Float = 0f,
        orientationDegrees: Int = 0,
        mirror: Boolean = false,
        timestamp: Long = System.currentTimeMillis()
    ) {
        if (closed.get()) return
        executor.execute {
            val encoded = encode(jpeg, cropAspect, orientationDegrees, mirror)

            val resolver = context.contentResolver
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
            val name = "IMG_$stamp.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
                put(MediaStore.Images.Media.DATE_TAKEN, timestamp)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            var uri: Uri? = null
            try {
                uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("MediaStore insert returned null")
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(encoded)
                    out.flush()
                } ?: throw IOException("Could not open MediaStore output")
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                Log.i(
                    "PureCam/Save",
                    "saved $name ${encoded.size} bytes (capture ${jpeg.size}) " +
                        "sha256=${sha256(encoded).take(16)}"
                )
                mainHandler.post { onSaved(uri, encoded.size) }
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

    /**
     * Returns the bytes to write. A capture that needs no crop and no mirror keeps the camera's
     * original bytes; everything else is decoded once, cropped, rotated and re-compressed.
     */
    private fun encode(
        jpeg: ByteArray,
        cropAspect: Float,
        orientationDegrees: Int,
        mirror: Boolean
    ): ByteArray {
        if (!mirror && !needsCrop(jpeg, cropAspect)) return jpeg
        return try {
            transcode(jpeg, cropAspect, orientationDegrees, mirror) ?: jpeg
        } catch (t: Throwable) {
            // Never lose the photo because the conversion failed.
            Log.w("PureCam/Save", "encode failed; saving the full frame as JPEG", t)
            jpeg
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
        mirror: Boolean
    ): ByteArray? {
        val decoder = BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size, false) ?: return null
        try {
            val width = decoder.width
            val height = decoder.height
            if (width <= 0 || height <= 0) return null

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
            ) ?: return null

            val rotation = orientationDegrees % 360
            val needsTransform = rotation != 0 || mirror
            val upright = if (!needsTransform) {
                region
            } else {
                val matrix = Matrix().apply {
                    if (rotation != 0) postRotate(rotation.toFloat())
                    if (mirror) postScale(-1f, 1f)
                }
                Bitmap.createBitmap(region, 0, 0, region.width, region.height, matrix, true)
            }
            try {
                return compress(upright)
            } finally {
                if (upright !== region) upright.recycle()
                region.recycle()
            }
        } finally {
            decoder.recycle()
        }
    }

    private fun compress(bitmap: Bitmap): ByteArray? {
        val out = ByteArrayOutputStream()
        return runCatching {
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) out.toByteArray() else null
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
