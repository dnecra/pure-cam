package com.necra.purecam.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Decodes a small, upright thumbnail for the gallery button without touching the full JPEG.
 * Video entries use the platform thumbnailer. Returns null for missing or unreadable files so
 * the placeholder stays visible.
 */
internal fun loadThumbnail(context: Context, uri: Uri?, targetPx: Int): ImageBitmap? {
    if (uri == null) return null
    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
    if (mime?.startsWith("video/") == true) {
        return runCatching {
            context.contentResolver.loadThumbnail(uri, Size(targetPx, targetPx), null).asImageBitmap()
        }.getOrNull()
    }
    return runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val exif = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        // Mirrored orientations (2/4/5/7) are used by saved front stills, so the thumbnail has
        // to apply both the rotation and the horizontal flip described by the tag.
        val (rotation, mirror) = when (exif) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> 0 to true
            ExifInterface.ORIENTATION_ROTATE_180 -> 180 to false
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180 to true
            ExifInterface.ORIENTATION_TRANSPOSE -> 90 to true
            ExifInterface.ORIENTATION_ROTATE_90 -> 90 to false
            ExifInterface.ORIENTATION_TRANSVERSE -> 270 to true
            ExifInterface.ORIENTATION_ROTATE_270 -> 270 to false
            else -> 0 to false
        }

        val upright = if (rotation == 0 && !mirror) {
            bitmap
        } else {
            val matrix = Matrix().apply {
                postRotate(rotation.toFloat())
                if (mirror) postScale(-1f, 1f)
            }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it !== bitmap) bitmap.recycle() }
        }
        upright.asImageBitmap()
    }.getOrNull()
}
