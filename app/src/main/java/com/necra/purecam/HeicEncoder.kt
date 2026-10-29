package com.necra.purecam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.File

/**
 * Best-effort single-image HEIC encoder.
 *
 * Android exposes no `Bitmap` → HEIF API, so this drives the platform HEVC encoder for one
 * key frame and muxes it into a HEIF container. Every step is guarded: if the device has no
 * HEVC encoder or the muxer rejects the frame, [encode] returns null and the caller writes
 * the JPEG it already has.
 */
internal object HeicEncoder {

    private const val TAG = "PureCam/Heic"
    private const val TIMEOUT_MS = 4_000L
    private const val EVEN_MASK = 0xFFFFFFFE.toInt()

    /** True when this device advertises a HEVC encoder. */
    fun isSupported(): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any {
                it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
            }
        }
    }.getOrDefault(false)

    fun encode(bitmap: Bitmap, cacheDir: File): ByteArray? {
        // HEVC requires even dimensions.
        val width = bitmap.width and EVEN_MASK
        val height = bitmap.height and EVEN_MASK
        if (width < 2 || height < 2) return null

        var codec: MediaCodec? = null
        var surface: Surface? = null
        var muxer: MediaMuxer? = null
        var file: File? = null
        var muxerStarted = false
        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_HEVC,
                width,
                height
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    (width.toLong() * height * 2L).coerceIn(2_000_000L, 40_000_000L).toInt()
                )
                setInteger(MediaFormat.KEY_FRAME_RATE, 1)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()

            file = File.createTempFile("purecam_", ".heic", cacheDir)
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_HEIF)

            codec.start()

            val canvas = surface.lockCanvas(null)
            try {
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(
                    bitmap,
                    null,
                    Rect(0, 0, width, height),
                    Paint(Paint.FILTER_BITMAP_FLAG)
                )
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
            codec.signalEndOfInputStream()

            val info = MediaCodec.BufferInfo()
            var track = -1
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            while (true) {
                val index = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0 && track >= 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }

                    index == MediaCodec.INFO_TRY_AGAIN_LATER ->
                        if (SystemClock.elapsedRealtime() > deadline) break
                }
                if (SystemClock.elapsedRealtime() > deadline) break
            }
            if (!muxerStarted) return null
            muxer.stop()
            muxerStarted = false
            return file.readBytes().takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            Log.w(TAG, "HEIC encode failed", t)
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { surface?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            file?.delete()
        }
    }
}
