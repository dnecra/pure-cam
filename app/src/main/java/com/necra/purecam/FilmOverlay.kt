package com.necra.purecam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.media.ExifInterface
import android.util.Log
import com.necra.purecam.core.OverlayStyle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Draws procedural film effects onto a camera JPEG.
 *
 * Everything is generated from a per-shot random seed, so the dust pattern, burn marks and
 * light leaks are different on every photo. Overlays never touch the Camera2 pipeline: the
 * capture stays pure, and the effect is composited onto the original JPEG before it is written
 * to MediaStore.
 */
object FilmOverlays {

    private const val TAG = "PureCam/Overlay"

    /**
     * Re-encodes the JPEG whenever at least one overlay is active. The original EXIF
     * orientation is carried over so the gallery still displays the photo upright.
     *
     * Any failure falls back to the untouched JPEG: a saved photo is never lost because of an
     * overlay.
     */
    fun apply(
        jpeg: ByteArray,
        styles: Set<OverlayStyle>,
        seed: Long = System.nanoTime()
    ): ByteArray {
        if (styles.isEmpty() || jpeg.size < 4) return jpeg
        val decoded = try {
            BitmapFactory.decodeByteArray(
                jpeg,
                0,
                jpeg.size,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                }
            )
        } catch (t: Throwable) {
            Log.w(TAG, "could not decode JPEG", t)
            null
        } ?: return jpeg

        val bitmap = if (decoded.isMutable) {
            decoded
        } else {
            decoded.copy(Bitmap.Config.ARGB_8888, true)?.also { decoded.recycle() }
        } ?: return jpeg

        return try {
            val rng = Random(seed)
            val orientation = readOrientation(jpeg)
            val canvas = Canvas(bitmap)
            val width = bitmap.width.toFloat()
            val height = bitmap.height.toFloat()

            if (OverlayStyle.BURN_IN in styles) drawBurnIn(canvas, width, height, rng)
            if (OverlayStyle.LIGHT_LEAK in styles) drawLightLeak(canvas, width, height, rng)
            if (OverlayStyle.DUST in styles) drawDustAndNoise(canvas, width, height, rng)

            val output = ByteArrayOutputStream(jpeg.size / 2)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output)
            withOrientation(output.toByteArray(), orientation)
        } catch (t: Throwable) {
            Log.w(TAG, "overlay render failed; saving the original JPEG", t)
            jpeg
        } finally {
            bitmap.recycle()
        }
    }

    // ------------------------------------------------------------------ grain + dust

    private fun drawDustAndNoise(canvas: Canvas, width: Float, height: Float, rng: Random) {
        drawGrain(canvas, width, height, rng)

        val minDim = min(width, height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Out-of-focus dust: small ellipses in white or black, like debris on the negative.
        val specks = 55 + rng.nextInt(70)
        repeat(specks) {
            val cx = rng.nextFloat() * width
            val cy = rng.nextFloat() * height
            val radius = minDim * (0.0008f + rng.nextFloat() * 0.0032f)
            val stretch = 1f + rng.nextFloat() * 1.4f
            val white = rng.nextBoolean()
            paint.reset()
            paint.isAntiAlias = true
            paint.color = if (white) Color.WHITE else Color.BLACK
            paint.alpha = if (white) 30 + rng.nextInt(65) else 40 + rng.nextInt(85)
            canvas.save()
            canvas.rotate(rng.nextFloat() * 360f, cx, cy)
            canvas.drawOval(
                cx - radius * stretch,
                cy - radius,
                cx + radius * stretch,
                cy + radius,
                paint
            )
            canvas.restore()
        }

        // A few fine hairs and scratches.
        repeat(4 + rng.nextInt(4)) {
            val startX = rng.nextFloat() * width
            val startY = rng.nextFloat() * height
            val path = Path()
            path.moveTo(startX, startY)
            var x = startX
            var y = startY
            repeat(4) {
                x += (rng.nextFloat() - 0.5f) * minDim * 0.22f
                y += (rng.nextFloat() - 0.5f) * minDim * 0.22f
                path.lineTo(x, y)
            }
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (minDim * 0.0007f).coerceAtLeast(1f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.WHITE
            paint.alpha = 18 + rng.nextInt(35)
            canvas.drawPath(path, paint)
        }

        repeat(1 + rng.nextInt(3)) {
            val x = rng.nextFloat() * width
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (minDim * 0.0006f).coerceAtLeast(1f)
            paint.color = Color.WHITE
            paint.alpha = 10 + rng.nextInt(22)
            canvas.drawLine(x, 0f, x + (rng.nextFloat() - 0.5f) * width * 0.02f, height, paint)
        }
    }

    private fun drawGrain(canvas: Canvas, width: Float, height: Float, rng: Random) {
        val longSide = max(width, height)
        val grainWidth = longSide.coerceAtMost(900f).toInt().coerceAtLeast(320)
        val grainHeight = (grainWidth * height / width).toInt().coerceAtLeast(1)
        val pixels = IntArray(grainWidth * grainHeight)
        for (i in pixels.indices) {
            val value = 96 + rng.nextInt(64)
            pixels[i] = Color.argb(255, value, value, value)
        }
        val grain = Bitmap.createBitmap(pixels, grainWidth, grainHeight, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            blendMode = BlendMode.OVERLAY
            alpha = 55
        }
        canvas.drawBitmap(grain, null, RectF(0f, 0f, width, height), paint)
        grain.recycle()
    }

    // ------------------------------------------------------------------ burn-in

    private fun drawBurnIn(canvas: Canvas, width: Float, height: Float, rng: Random) {
        val minDim = min(width, height)

        // Warm vignette: transparent in the centre, charred brown towards the corners.
        val vignette = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width / 2f,
                height / 2f,
                hypot(width, height) * 0.62f,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                    Color.argb(165, 56, 28, 10)
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
            blendMode = BlendMode.MULTIPLY
        }
        canvas.drawRect(0f, 0f, width, height, vignette)

        // A faint ember rim on one corner keeps the burn from looking like a plain vignette.
        val cornerX = if (rng.nextBoolean()) 0f else width
        val cornerY = if (rng.nextBoolean()) 0f else height
        val ember = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            blendMode = BlendMode.SCREEN
            shader = RadialGradient(
                cornerX,
                cornerY,
                minDim * (0.35f + rng.nextFloat() * 0.35f),
                intArrayOf(
                    Color.argb(110, 255, 116, 38),
                    Color.argb(40, 220, 60, 18),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width, height, ember)
    }

    // ------------------------------------------------------------------ light leak

    private fun drawLightLeak(canvas: Canvas, width: Float, height: Float, rng: Random) {
        val palette = intArrayOf(
            0xFFFF9A3C.toInt(),
            0xFFFF5A2A.toInt(),
            0xFFE8452B.toInt(),
            0xFFFFD28A.toInt(),
            0xFFD94A8C.toInt()
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.SCREEN }
        val maxDim = max(width, height)

        repeat(1 + rng.nextInt(3)) {
            val color = palette[rng.nextInt(palette.size)]
            val side = rng.nextInt(4)
            val cx: Float
            val cy: Float
            when (side) {
                0 -> { cx = rng.nextFloat() * width; cy = -height * 0.25f }
                1 -> { cx = width + width * 0.25f; cy = rng.nextFloat() * height }
                2 -> { cx = rng.nextFloat() * width; cy = height + height * 0.25f }
                else -> { cx = -width * 0.25f; cy = rng.nextFloat() * height }
            }
            paint.reset()
            paint.isAntiAlias = true
            paint.blendMode = BlendMode.SCREEN
            paint.shader = RadialGradient(
                cx,
                cy,
                maxDim * (0.45f + rng.nextFloat() * 0.85f),
                intArrayOf(
                    withAlpha(color, 195),
                    withAlpha(color, 95),
                    withAlpha(color, 0)
                ),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width, height, paint)
        }

        // A thin bright streak, as if light slipped past the film door.
        val streak = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            blendMode = BlendMode.SCREEN
            color = Color.WHITE
            alpha = 22 + rng.nextInt(26)
        }
        val left = rng.nextFloat() * width
        val top = rng.nextFloat() * height * 0.25f
        val thickness = max(2f, min(width, height) * (0.002f + rng.nextFloat() * 0.006f))
        canvas.save()
        canvas.rotate((rng.nextFloat() - 0.5f) * 30f, left, top)
        canvas.drawRect(left, top, left + width * (0.15f + rng.nextFloat() * 0.5f), top + thickness, streak)
        canvas.restore()
    }

    // ------------------------------------------------------------------ helpers

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    private fun readOrientation(jpeg: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(jpeg))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (t: Throwable) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /**
     * Inserts a minimal EXIF APP1 block carrying just the orientation tag. Bitmap.compress
     * writes no metadata, so without this a rotated capture would be shown on its side.
     */
    private fun withOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        if (orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        ) {
            return jpeg
        }
        if (jpeg.size < 2 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return jpeg

        val app1 = ByteArray(36)
        app1[0] = 0xFF.toByte()
        app1[1] = 0xE1.toByte()
        app1[2] = 0x00
        app1[3] = 0x22 // segment length: 2 + 32 payload bytes
        app1[4] = 'E'.code.toByte()
        app1[5] = 'x'.code.toByte()
        app1[6] = 'i'.code.toByte()
        app1[7] = 'f'.code.toByte()
        app1[8] = 0
        app1[9] = 0
        app1[10] = 'I'.code.toByte() // little-endian TIFF
        app1[11] = 'I'.code.toByte()
        app1[12] = 0x2A
        app1[13] = 0x00
        app1[14] = 0x08
        app1[15] = 0x00
        app1[16] = 0x00
        app1[17] = 0x00
        app1[18] = 0x01 // one IFD entry
        app1[19] = 0x00
        app1[20] = 0x12 // tag 0x0112: orientation
        app1[21] = 0x01
        app1[22] = 0x03 // type SHORT
        app1[23] = 0x00
        app1[24] = 0x01 // count 1
        app1[25] = 0x00
        app1[26] = 0x00
        app1[27] = 0x00
        app1[28] = (orientation and 0xFF).toByte()
        app1[29] = 0x00
        // bytes 30..35: padding and the next-IFD offset (zero)

        val out = ByteArray(jpeg.size + app1.size)
        jpeg.copyInto(out, 0, 0, 2)
        app1.copyInto(out, 2)
        jpeg.copyInto(out, app1.size + 2, 2, jpeg.size)
        return out
    }
}
