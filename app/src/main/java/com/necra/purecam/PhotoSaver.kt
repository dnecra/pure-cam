package com.necra.purecam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.necra.purecam.core.OverlayStyle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Writes the camera-produced JPEG to MediaStore. With no overlays selected the bytes are
 * stored untouched; overlays are composited (and the JPEG re-encoded) on this worker thread.
 */
class PhotoSaver(
    private val context: Context,
    private val onSaved: (Uri, Int) -> Unit,
    private val onError: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "purecam-save") }
    private val closed = AtomicBoolean(false)

    fun save(
        jpeg: ByteArray,
        width: Int,
        height: Int,
        timestamp: Long = System.currentTimeMillis(),
        overlays: Set<OverlayStyle> = emptySet(),
        mirrorHorizontal: Boolean = false
    ) {
        if (closed.get()) return
        executor.execute {
            var uri: Uri? = null
            try {
                // Apply film overlays first (re-encodes from bitmap if any are active).
                val afterOverlays = FilmOverlays.apply(jpeg, overlays)

                // Mirror the saved JPEG if the front camera was used. We read the EXIF
                // orientation so we can re-apply it after the flip, keeping the gallery
                // display correct. The flip is done in the pixel domain (no EXIF trick)
                // so every viewer — including those that ignore EXIF — sees the right image.
                val payload = if (mirrorHorizontal) {
                    mirrorJpeg(afterOverlays) ?: afterOverlays
                } else {
                    afterOverlays
                }
                val now = System.currentTimeMillis()
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_$stamp.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
                    put(MediaStore.Images.Media.DATE_TAKEN, timestamp)
                    put(MediaStore.Images.Media.DATE_ADDED, now / 1000L)
                    put(MediaStore.Images.Media.DATE_MODIFIED, now / 1000L)
                    put(MediaStore.Images.Media.WIDTH, width)
                    put(MediaStore.Images.Media.HEIGHT, height)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("MediaStore insert returned null")
                resolver.openOutputStream(uri)?.use { output ->
                    output.write(payload)
                    output.flush()
                } ?: throw IOException("Could not open MediaStore output")
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null
                )
                mainHandler.post { onSaved(uri, payload.size) }
            } catch (t: Throwable) {
                Log.e("PureCam/Save", "save failed", t)
                if (uri != null) runCatching { context.contentResolver.delete(uri, null, null) }
                mainHandler.post { onError(t.message ?: "Could not save photo") }
            }
        }
    }

    fun shutdown() {
        if (closed.compareAndSet(false, true)) executor.shutdown()
    }

    /**
     * Flips a JPEG horizontally in pixel space. The EXIF orientation of the input is read
     * first and the mirrored equivalent is written back so every viewer displays the photo
     * upright. Returns null on any failure so the caller can fall back to the unflipped bytes.
     */
    private fun mirrorJpeg(jpeg: ByteArray): ByteArray? = try {
        // Read the EXIF orientation from the original bytes.
        val originalOrientation = ByteArrayInputStream(jpeg).use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }

        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null

        val matrix = Matrix().apply { postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f) }
        val flipped = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        bitmap.recycle()

        val out = ByteArrayOutputStream(jpeg.size)
        flipped.compress(Bitmap.CompressFormat.JPEG, 95, out)
        flipped.recycle()

        // Map the original orientation to its horizontally-mirrored equivalent so the
        // gallery still shows the photo the right way up after the pixel flip.
        val mirroredOrientation = mirrorOrientation(originalOrientation)
        val resultBytes = out.toByteArray()

        // Write the mirrored orientation back into the JPEG EXIF using a temp file, since
        // ExifInterface.saveAttributes() requires a file path on older API levels.
        val tmp = java.io.File.createTempFile("purecam_flip", ".jpg")
        try {
            tmp.writeBytes(resultBytes)
            val exif = ExifInterface(tmp.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, mirroredOrientation.toString())
            exif.saveAttributes()
            tmp.readBytes()
        } finally {
            tmp.delete()
        }
    } catch (t: Throwable) {
        Log.w("PureCam/Save", "mirrorJpeg failed; saving unflipped", t)
        null
    }

    /**
     * Returns the EXIF orientation value that corresponds to horizontally flipping [orientation].
     * Covers all 8 standard EXIF orientation values.
     */
    private fun mirrorOrientation(orientation: Int): Int = when (orientation) {
        ExifInterface.ORIENTATION_NORMAL -> ExifInterface.ORIENTATION_FLIP_HORIZONTAL
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifInterface.ORIENTATION_NORMAL
        ExifInterface.ORIENTATION_ROTATE_180 -> ExifInterface.ORIENTATION_FLIP_VERTICAL
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifInterface.ORIENTATION_ROTATE_180
        ExifInterface.ORIENTATION_ROTATE_90 -> ExifInterface.ORIENTATION_TRANSPOSE
        ExifInterface.ORIENTATION_TRANSPOSE -> ExifInterface.ORIENTATION_ROTATE_90
        ExifInterface.ORIENTATION_ROTATE_270 -> ExifInterface.ORIENTATION_TRANSVERSE
        ExifInterface.ORIENTATION_TRANSVERSE -> ExifInterface.ORIENTATION_ROTATE_270
        else -> orientation
    }
}
