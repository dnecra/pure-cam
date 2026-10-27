package com.rawcam

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Writes the camera's JPEG bytes straight to MediaStore. Nothing is decoded, rotated,
 * cropped, scaled or re-encoded here.
 */
class PhotoSaver(
    private val context: Context,
    private val onSaved: (Uri, Int) -> Unit,
    private val onError: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "rawcam-save") }
    private val closed = AtomicBoolean(false)

    fun save(jpeg: ByteArray, timestamp: Long = System.currentTimeMillis()) {
        if (closed.get()) return
        executor.execute {
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
                    out.write(jpeg)
                    out.flush()
                } ?: throw IOException("Could not open MediaStore output")
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                Log.i("RawCam/Save", "saved $name ${jpeg.size} bytes sha256=${sha256(jpeg).take(16)}")
                mainHandler.post { onSaved(uri, jpeg.size) }
            } catch (t: Throwable) {
                Log.e("RawCam/Save", "save failed", t)
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

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
