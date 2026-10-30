package com.necra.purecam

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

/** Publishes the camera's original JPEG bytes without cropping or recompression. */
class PhotoSaver(
    private val context: Context,
    private val onSaved: (Uri, Int) -> Unit,
    private val onError: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "purecam-save") }
    private val closed = AtomicBoolean(false)

    fun save(jpeg: ByteArray, timestamp: Long = System.currentTimeMillis()) {
        if (closed.get()) return
        executor.execute {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
            val name = "IMG_$stamp.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
                put(MediaStore.Images.Media.DATE_TAKEN, timestamp)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            var uri: Uri? = null
            try {
                uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("MediaStore insert returned null")
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(jpeg)
                    out.flush()
                } ?: throw IOException("Could not open MediaStore output")
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null
                )
                Log.i(
                    "PureCam/Save",
                    "saved $name ${jpeg.size} bytes sha256=${sha256(jpeg).take(16)}"
                )
                mainHandler.post { onSaved(uri, jpeg.size) }
            } catch (t: Throwable) {
                Log.e("PureCam/Save", "save failed", t)
                if (uri != null) runCatching { resolver.delete(uri, null, null) }
                mainHandler.post { onError(t.message ?: "Could not save photo") }
            }
        }
    }

    fun shutdown() {
        if (closed.compareAndSet(false, true)) executor.shutdown()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
