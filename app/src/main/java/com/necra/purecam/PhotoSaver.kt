package com.necra.purecam

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.necra.purecam.core.OverlayStyle
import com.necra.purecam.core.JpegExif
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

                // Mirror in display space by changing the EXIF orientation. Flipping encoded
                // pixels directly uses sensor axes, which becomes a vertical flip when the
                // JPEG is rotated for portrait display.
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

    /** Flips the displayed image horizontally by swapping its EXIF orientation pair. */
    private fun mirrorJpeg(jpeg: ByteArray): ByteArray? = try {
        val result = jpeg.copyOf()
        val orientation = JpegExif.readOrientation(result)
        if (JpegExif.writeOrientation(result, JpegExif.mirroredOrientation(orientation))) result else null
    } catch (t: Throwable) {
        Log.w("PureCam/Save", "mirrorJpeg failed; saving unflipped", t)
        null
    }
}
