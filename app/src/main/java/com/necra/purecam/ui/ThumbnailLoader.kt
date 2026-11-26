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

        val upright = applyExifOrientation(bitmap, exif)
        upright.asImageBitmap()
    }.getOrNull()
}

/**
 * Applies an EXIF orientation value (rotation plus the horizontal flip of the mirrored values
 * 2/4/5/7, which saved front stills use) so the bitmap is upright.
 */
internal fun applyExifOrientation(bitmap: Bitmap, exif: Int): Bitmap {
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
    if (rotation == 0 && !mirror) return bitmap
    val matrix = Matrix().apply {
        postRotate(rotation.toFloat())
        if (mirror) postScale(-1f, 1f)
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        .also { if (it !== bitmap) bitmap.recycle() }
}

/**
 * The thumbnail the camera embedded in a fresh JPEG, made upright the way the saved photo will
 * display, or null when the JPEG has none. [mirrorHorizontal] mirrors the preview exactly like
 * the saver will mirror the file.
 *
 * Camera2 normally leaves the thumbnail unrotated next to the EXIF orientation tag, but a few
 * HALs rotate it already; its shape against the main image tells the two apart.
 */
internal fun previewFromJpeg(
    jpeg: ByteArray,
    width: Int,
    height: Int,
    mirrorHorizontal: Boolean
): Bitmap? {
    val exif = ExifInterface(java.io.ByteArrayInputStream(jpeg))
    if (!exif.hasThumbnail()) return null
    val thumb = exif.thumbnailBitmap ?: return null
    val tag = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    val swapsAxes = tag in intArrayOf(
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_TRANSVERSE,
        ExifInterface.ORIENTATION_ROTATE_270
    )
    val displayPortrait = if (swapsAxes) width > height else height > width
    val thumbPortrait = thumb.height > thumb.width
    val displayOrientation = if (displayPortrait == thumbPortrait) {
        // Already upright (or the shapes cannot tell): only the mirror, if any, is left to do.
        ExifInterface.ORIENTATION_NORMAL
    } else {
        tag
    }
    val finalOrientation = if (mirrorHorizontal) {
        com.necra.purecam.core.JpegExif.mirroredOrientation(displayOrientation)
    } else {
        displayOrientation
    }
    return applyExifOrientation(thumb, finalOrientation)
}
