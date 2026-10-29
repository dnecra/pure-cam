package com.rawcam

import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.rawcam.core.VideoConfig
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the camera's recorder surface straight into MediaStore. Like [PhotoSaver] this does no
 * post-processing: MediaRecorder writes the MP4 itself from the frames Camera2 delivers, and the
 * pending MediaStore row is published only after the file has been finalized.
 */
class VideoRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var startedAtMs = 0L
    private var started = false

    val isStarted: Boolean get() = started

    fun elapsedMs(): Long =
        if (started) (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L) else 0L

    /**
     * Creates the pending MediaStore entry and prepares the encoder. Throws when the device
     * cannot record with the requested audio configuration; the caller may retry silently.
     */
    fun open(config: VideoConfig, orientationHint: Int, withAudio: Boolean): Surface {
        check(recorder == null) { "Recorder already open" }
        val resolver = context.contentResolver
        val name = "VID_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val row = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert returned null")
        uri = row

        try {
            val pfd = resolver.openFileDescriptor(row, "rw")
                ?: throw IOException("Could not open MediaStore output")
            descriptor = pfd

            val r = MediaRecorder(context)
            if (withAudio) r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            @Suppress("DEPRECATION")
            r.setOutputFile(pfd.fileDescriptor)
            r.setVideoEncodingBitRate(config.bitRate)
            r.setVideoFrameRate(config.fps)
            r.setVideoSize(config.size.width, config.size.height)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (withAudio) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(128_000)
                r.setAudioSamplingRate(48_000)
                r.setAudioChannels(1)
            }
            r.setOrientationHint(orientationHint)
            r.prepare()
            recorder = r
            Log.i(
                "RawCam/Video",
                "prepared $name ${config.size} ${config.fps}fps " +
                    "${config.bitRate / 1_000_000.0}Mbps audio=$withAudio orientation=$orientationHint"
            )
            return r.surface
        } catch (t: Throwable) {
            Log.e("RawCam/Video", "prepare failed", t)
            release(deleteRow = true)
            if (t is IOException) throw t
            throw IOException(t.message ?: "Could not prepare the video recorder", t)
        }
    }

    /** Starts consuming camera frames. Call once the recording session is repeating. */
    fun start() {
        val r = recorder ?: throw IllegalStateException("Recorder is not prepared")
        r.start()
        started = true
        startedAtMs = SystemClock.elapsedRealtime()
        Log.i("RawCam/Video", "recording started")
    }

    /** Stops and publishes the pending entry. Throws if the MP4 could not be finalized. */
    fun stop(): Uri {
        val r = recorder ?: throw IllegalStateException("Recorder is not open")
        val row = uri ?: throw IllegalStateException("No MediaStore entry")
        val wasStarted = started
        started = false
        try {
            if (!wasStarted) throw IllegalStateException("Recording never started")
            r.stop()
        } catch (t: Throwable) {
            Log.e("RawCam/Video", "stop failed", t)
            releaseInternal()
            uri = null
            runCatching { context.contentResolver.delete(row, null, null) }
            throw if (t is IOException) t else IOException(t.message ?: "Could not finish the video", t)
        }
        releaseInternal()
        uri = null
        val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        runCatching { context.contentResolver.update(row, done, null, null) }
            .onFailure { Log.w("RawCam/Video", "could not publish $row", it) }
        Log.i("RawCam/Video", "saved $row")
        return row
    }

    /** Drops the recording and its pending MediaStore entry. Safe to call at any time. */
    fun cancel() = release(deleteRow = true)

    /** Releases encoder and file descriptor. The pending row is left alone. */
    fun release(deleteRow: Boolean = false) {
        val row = uri
        uri = null
        started = false
        releaseInternal()
        if (deleteRow && row != null) {
            runCatching { context.contentResolver.delete(row, null, null) }
        }
    }

    private fun releaseInternal() {
        recorder?.let { r ->
            runCatching { r.reset() }
            runCatching { r.release() }
        }
        recorder = null
        runCatching { descriptor?.close() }
        descriptor = null
    }
}
