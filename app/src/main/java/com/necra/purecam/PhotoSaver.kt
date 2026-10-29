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
 * Writes a captured JPEG to MediaStore. When the user picked a ratio that the sensor does not
 * produce natively, the frame is center-cropped (and rotated upright) in software. A capture
 * that already matches the requested ratio is written untouched, EXIF orientation included.
 */
class PhotoSaver(
    private val context: Context,
    private val onSaved: (Uri, Int) -> Unit,
    private val onError: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "rawcam-save") }
    private val closed = AtomicBoolean(false)

    /**
     * @param cropAspect requested ratio in sensor coordinates (width / height). Pass 0 to save
     *   the camera's bytes untouched.
     * @param orientationDegrees clockwise rotation needed to make the frame upright; used only
     *   when a crop forces a decode/re-encode.
     */
    fun save(
        jpeg: ByteArray,
        cropAspect: Float = 0f,
        orientationDegrees: Int = 0,
        timestamp: Long = System.currentTimeMillis()
    ) {
        if (closed.get()) return
        executor.execute {
            val output = if (needsCrop(jpeg, cropAspect)) {
                try {
                    cropJpeg(jpeg, cropAspect, orientationDegrees)
                } catch (t: Throwable) {
                    // Never lose the photo because the crop failed.
                    Log.w("PureCam/Save", "crop failed; saving the full frame", t)
                    jpeg
                }
            } else {
                jpeg
            }

            val resolver = context.contentResolver
            val name = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))}.jpg"
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
                    out.write(output)
                    out.flush()
                } ?: throw IOException("Could not open MediaStore output")
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                Log.i(
                    "PureCam/Save",
                    "saved $name ${output.size} bytes (capture ${jpeg.size}) sha256=${sha256(output).take(16)}"
                )
                mainHandler.post { onSaved(uri, output.size) }
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

    /** True when the requested ratio differs enough from the captured buffer to be worth a crop. */
    private fun needsCrop(jpeg: ByteArray, cropAspect: Float): Boolean {
        if (cropAspect <= 0f) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return false
        val native = options.outWidth.toFloat() / options.outHeight.toFloat()
        return abs(native - cropAspect) / native > CROP_TOLERANCE
    }

    private fun cropJpeg(jpeg: ByteArray, cropAspect: Float, orientationDegrees: Int): ByteArray {
        val decoder = BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size, false)
            ?: return jpeg
        try {
            val width = decoder.width
            val height = decoder.height
            if (width <= 0 || height <= 0) return jpeg

            val ratio = cropAspect.coerceIn(0.2f, 5f)
            var cropWidth = width
            var cropHeight = (width / ratio).roundToInt()
            if (cropHeight > height) {
                cropHeight = height
                cropWidth = (height * ratio).roundToInt()
            }
            cropWidth = cropWidth.coerceIn(2, width)
            cropHeight = cropHeight.coerceIn(2, height)
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
            ) ?: return jpeg

            val upright = if (orientationDegrees % 360 == 0) {
                region
            } else {
                val matrix = Matrix().apply { postRotate(orientationDegrees.toFloat()) }
                Bitmap.createBitmap(region, 0, 0, region.width, region.height, matrix, true)
            }
            try {
                val out = ByteArrayOutputStream()
                val ok = upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                return if (ok) out.toByteArray() else jpeg
            } finally {
                if (upright !== region) upright.recycle()
                region.recycle()
            }
        } finally {
            decoder.recycle()
        }
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
