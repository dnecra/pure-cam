package com.necra.purecam

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
import com.necra.purecam.core.VideoConfig
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the camera's recorder surface straight into MediaStore. MediaRecorder writes the MP4
 * itself from the frames Camera2 delivers, and the pending MediaStore row is published only after
 * the file has been finalized.
 *
 * Front-camera clips arrive already mirrored on this device, so "As previewed" has to flip them
 * back. MediaRecorder has no mirror option, so in that case the clip is written
 * to a cache file first, flipped by [VideoMirrorProcessor] and copied into MediaStore once the
 * encoder has finished. If the flip fails the unmirrored recording is kept.
 */
class VideoRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var config: VideoConfig? = null
    private var dateTakenMs = 0L
    private var startedAtMs = 0L
    private var started = false
    private var orientationHint = 0
    private var mirror = false
    private var tempFile: File? = null

    val isStarted: Boolean get() = started

    /** True when [stop] has to re-encode the clip before publishing it. */
    val isMirroring: Boolean get() = mirror

    fun elapsedMs(): Long =
        if (started) (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L) else 0L

    /**
     * Creates the pending MediaStore entry and prepares the encoder. Throws when the device
     * cannot record with the requested audio configuration; the caller may retry silently.
     */
    fun open(
        config: VideoConfig,
        orientationHint: Int,
        withAudio: Boolean,
        mirror: Boolean = false
    ): Surface {
        check(recorder == null) { "Recorder already open" }
        val resolver = context.contentResolver
        val captureTime = System.currentTimeMillis()
        dateTakenMs = captureTime
        this.config = config
        this.orientationHint = orientationHint
        this.mirror = mirror
        val name = "VID_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(captureTime))}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
            put(MediaStore.Video.Media.DATE_TAKEN, captureTime)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val row = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert returned null")
        uri = row

        try {
            val r = MediaRecorder(context)
            if (withAudio) r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (mirror) {
                // Post-processing needs a seekable file; MediaStore is filled after the flip.
                val file = File(context.cacheDir, "rec-$captureTime.mp4")
                tempFile = file
                r.setOutputFile(file.absolutePath)
            } else {
                val pfd = resolver.openFileDescriptor(row, "rw")
                    ?: throw IOException("Could not open MediaStore output")
                descriptor = pfd
                @Suppress("DEPRECATION")
                r.setOutputFile(pfd.fileDescriptor)
            }
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
                "PureCam/Video",
                "prepared $name ${config.size} ${config.fps}fps " +
                    "${config.bitRate / 1_000_000.0}Mbps audio=$withAudio orientation=$orientationHint " +
                    "mirror=$mirror"
            )
            return r.surface
        } catch (t: Throwable) {
            Log.e("PureCam/Video", "prepare failed", t)
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
        Log.i("PureCam/Video", "recording started")
    }

    /** Stops and publishes the pending entry. Throws if the MP4 could not be finalized. */
    fun stop(): Uri {
        val r = recorder ?: throw IllegalStateException("Recorder is not open")
        val row = uri ?: throw IllegalStateException("No MediaStore entry")
        val wasStarted = started
        val durationMs = elapsedMs()
        val savedConfig = config
        val captureTime = dateTakenMs
        val hint = orientationHint
        val mirroring = mirror
        val recorded = tempFile
        started = false
        try {
            if (!wasStarted) throw IllegalStateException("Recording never started")
            r.stop()
        } catch (t: Throwable) {
            Log.e("PureCam/Video", "stop failed", t)
            releaseInternal()
            uri = null
            runCatching { context.contentResolver.delete(row, null, null) }
            throw if (t is IOException) t else IOException(t.message ?: "Could not finish the video", t)
        }
        // The cache file is needed below when the clip must be mirrored.
        releaseInternal(keepTemp = mirroring)
        uri = null
        if (mirroring && recorded != null) {
            try {
                publishMirrored(row, recorded, hint)
            } catch (t: Throwable) {
                Log.e("PureCam/Video", "could not publish mirrored $row", t)
                runCatching { context.contentResolver.delete(row, null, null) }
                throw if (t is IOException) t else IOException(t.message ?: "Could not save the video", t)
            } finally {
                recorded.delete()
            }
        }
        try {
            context.contentResolver.update(
                row,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null
            )
        } catch (t: Throwable) {
            Log.w("PureCam/Video", "could not publish $row", t)
        }
        val metadata = ContentValues().apply {
            put(MediaStore.Video.Media.DATE_TAKEN, captureTime)
            put(MediaStore.Video.Media.DURATION, durationMs)
            savedConfig?.let {
                put(MediaStore.Video.Media.WIDTH, it.size.width)
                put(MediaStore.Video.Media.HEIGHT, it.size.height)
            }
        }
        runCatching { context.contentResolver.update(row, metadata, null, null) }
            .onFailure { Log.w("PureCam/Video", "could not update video metadata for $row", it) }
        Log.i("PureCam/Video", "saved $row")
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

    /**
     * Mirrors the finished clip and copies it into the pending MediaStore entry. Falls back to
     * the unmirrored file when the device cannot transcode it, so the user never loses a clip.
     */
    private fun publishMirrored(row: Uri, recorded: File, hint: Int) {
        val mirrored = File(context.cacheDir, "mirror-${System.nanoTime()}.mp4")
        try {
            val flipped = VideoMirrorProcessor.mirror(recorded, mirrored, hint)
            if (!flipped) Log.w("PureCam/Video", "mirroring failed; saving the unmirrored clip")
            val source = if (flipped) mirrored else recorded
            val pfd = context.contentResolver.openFileDescriptor(row, "rw")
                ?: throw IOException("Could not open MediaStore output")
            // The pending row was never written to, so a plain copy starts at offset 0.
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { output ->
                FileInputStream(source).use { input ->
                    input.copyTo(output, DEFAULT_COPY_BUFFER)
                }
            }
            // The clip is safely in MediaStore; free the cache copy.
            if (flipped) recorded.delete()
        } finally {
            mirrored.delete()
        }
    }

    private fun releaseInternal(keepTemp: Boolean = false) {
        recorder?.let { r ->
            runCatching { r.reset() }
            runCatching { r.release() }
        }
        recorder = null
        config = null
        dateTakenMs = 0L
        mirror = false
        orientationHint = 0
        runCatching { descriptor?.close() }
        descriptor = null
        // A stopped or cancelled recording must not leave cache files behind.
        val file = tempFile
        tempFile = null
        if (!keepTemp) file?.delete()
    }

    private companion object {
        const val DEFAULT_COPY_BUFFER = 256 * 1024
    }
}
