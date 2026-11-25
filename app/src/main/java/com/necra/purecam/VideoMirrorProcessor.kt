package com.necra.purecam

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.necra.purecam.core.SizeI
import com.necra.purecam.core.VideoMath
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Rewrites a finished MP4 with one axis mirrored, then remuxes the original audio track
 * untouched. Only the front camera needs this: the camera's recorder surface always carries the
 * same unmirrored stream as the live preview, so the "Mirrored" save option has to be applied
 * after the clip is finalised.
 *
 * The stored frames are in sensor orientation and the container carries a rotation hint. To make
 * the *displayed* image mirror horizontally, the stored frames must be flipped horizontally for a
 * 0/180 hint and vertically for a 90/270 hint (see [VideoMath.mirrorFlipsX]).
 */
object VideoMirrorProcessor {

    private const val TAG = "PureCam/Mirror"
    private const val DEFAULT_BUFFER_SIZE = 4 * 1024 * 1024

    /** EGL_ANDROID_recordable: guarantees the surface can be consumed by a codec. */
    private const val EGL_RECORDABLE_ANDROID = 0x3142

    /**
     * @return true when [destination] was written successfully. The caller keeps the original
     * clip when this fails.
     */
    fun mirror(source: File, destination: File, orientationHint: Int): Boolean {
        val videoOnly = File(destination.parentFile ?: source.parentFile, "${destination.name}.video")
        return try {
            if (!transcodeVideo(source, videoOnly, orientationHint)) return false
            remux(videoOnly, source, destination, orientationHint)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "could not mirror $source", t)
            false
        } finally {
            videoOnly.delete()
        }
    }

    // ---------------------------------------------------------------- video transcode

    private fun transcodeVideo(source: File, output: File, orientationHint: Int): Boolean {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var egl: EglState? = null
        var surfaceTexture: SurfaceTexture? = null
        var decoderSurface: Surface? = null
        var inputSurface: Surface? = null
        var renderer: FrameRenderer? = null
        // The frame callback must not share the thread that waits for it below.
        var frameThread: HandlerThread? = null

        try {
            extractor.setDataSource(source.absolutePath)
            val track = findTrack(extractor, "video/")
            if (track < 0) return false
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val fps = format.intOr(MediaFormat.KEY_FRAME_RATE, 30).coerceIn(1, 240)
            val bitRate = format.intOr(
                MediaFormat.KEY_BIT_RATE,
                VideoMath.bitRate(SizeI(width, height), fps)
            )

            val encoderFormat = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType("video/avc").apply {
                configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }

            egl = EglState(inputSurface!!).apply { makeCurrent() }
            val textureId = egl.createExternalTexture()
            surfaceTexture = SurfaceTexture(textureId).apply { setDefaultBufferSize(width, height) }
            val frameAvailable = Semaphore(0)
            frameThread = HandlerThread("PureCam/MirrorFrames").apply { start() }
            surfaceTexture.setOnFrameAvailableListener(
                { frameAvailable.release() },
                Handler(frameThread.looper)
            )
            decoderSurface = Surface(surfaceTexture)
            renderer = FrameRenderer(
                textureId = textureId,
                viewportWidth = width,
                viewportHeight = height,
                flipX = VideoMath.mirrorFlipsX(orientationHint),
                flipY = VideoMath.mirrorFlipsY(orientationHint)
            )

            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(format, decoderSurface, null, 0)
                start()
            }

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                setOrientationHint(orientationHint)
            }

            var muxerTrack = -1
            var muxerStarted = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var encoderDone = false

            while (!encoderDone) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)
                        val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (buffer == null || size < 0) {
                            decoder.queueInputBuffer(
                                index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                if (!decoderDone) {
                    val index = decoder.dequeueOutputBuffer(info, 10_000)
                    if (index >= 0) {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (info.size > 0) {
                            decoder.releaseOutputBuffer(index, true)
                            if (frameAvailable.tryAcquire(1, TimeUnit.SECONDS)) {
                                surfaceTexture.updateTexImage()
                                renderer.draw(surfaceTexture)
                                egl.swapBuffers(surfaceTexture.timestamp)
                            } else {
                                Log.w(TAG, "decoder frame was not delivered")
                            }
                        } else {
                            decoder.releaseOutputBuffer(index, false)
                        }
                        if (eos) {
                            decoderDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }

                while (true) {
                    val index = encoder.dequeueOutputBuffer(info, 0)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                muxerTrack = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                        }

                        index >= 0 -> {
                            val buffer = encoder.getOutputBuffer(index)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                info.size = 0
                            }
                            if (buffer != null && info.size > 0 && muxerStarted) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(muxerTrack, buffer, info)
                            }
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.releaseOutputBuffer(index, false)
                            if (eos) {
                                encoderDone = true
                                break
                            }
                        }

                        else -> break
                    }
                }
            }
            return muxerStarted
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching {
                if (muxer != null) {
                    // A muxer that never started is simply released.
                    runCatching { muxer.stop() }
                    muxer.release()
                }
            }
            runCatching { decoderSurface?.release() }
            runCatching { surfaceTexture?.release() }
            runCatching { inputSurface?.release() }
            runCatching { renderer?.release() }
            runCatching { egl?.release() }
            runCatching { frameThread?.quitSafely() }
            runCatching { extractor.release() }
        }
    }

    // ---------------------------------------------------------------- audio remux

    /** Copies the mirrored video track plus the original audio track into the final file. */
    private fun remux(videoFile: File, audioSource: File, destination: File, hint: Int) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            val videoTrack = findTrack(videoExtractor, "video/")
            require(videoTrack >= 0) { "no video track in mirrored clip" }
            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            videoExtractor.selectTrack(videoTrack)

            audioExtractor.setDataSource(audioSource.absolutePath)
            val audioTrack = findTrack(audioExtractor, "audio/")
            val audioFormat = if (audioTrack >= 0) audioExtractor.getTrackFormat(audioTrack) else null
            if (audioTrack >= 0) audioExtractor.selectTrack(audioTrack)

            muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(hint)
            val outVideo = muxer.addTrack(videoFormat)
            val outAudio = audioFormat?.let { muxer.addTrack(it) } ?: -1
            muxer.start()

            val videoBuffer = ByteBuffer.allocateDirect(bufferSize(videoFormat))
                .order(ByteOrder.nativeOrder())
            val audioBuffer = audioFormat?.let {
                ByteBuffer.allocateDirect(bufferSize(it)).order(ByteOrder.nativeOrder())
            }
            val videoInfo = MediaCodec.BufferInfo()
            val audioInfo = MediaCodec.BufferInfo()
            var videoPending = false
            var audioPending = false
            var videoDone = false
            var audioDone = audioFormat == null
            var videoTime = 0L
            var audioTime = 0L

            if (videoBuffer.capacity() <= 0 || audioBuffer?.capacity() == 0) return

            while (!videoDone || !audioDone) {
                if (!videoPending && !videoDone) {
                    val size = videoExtractor.readSampleData(videoBuffer, 0)
                    if (size < 0) {
                        videoDone = true
                    } else {
                        videoTime = videoExtractor.sampleTime
                        videoInfo.set(0, size, videoTime, videoExtractor.sampleFlags)
                        videoBuffer.position(0)
                        videoBuffer.limit(size)
                        videoPending = true
                    }
                }
                if (!audioPending && !audioDone && audioBuffer != null) {
                    val size = audioExtractor.readSampleData(audioBuffer, 0)
                    if (size < 0) {
                        audioDone = true
                    } else {
                        audioTime = audioExtractor.sampleTime
                        audioInfo.set(0, size, audioTime, audioExtractor.sampleFlags)
                        audioBuffer.position(0)
                        audioBuffer.limit(size)
                        audioPending = true
                    }
                }

                when {
                    videoPending && (!audioPending || videoTime <= audioTime) -> {
                        muxer.writeSampleData(outVideo, videoBuffer, videoInfo)
                        videoExtractor.advance()
                        videoPending = false
                    }

                    audioPending && outAudio >= 0 && audioBuffer != null -> {
                        muxer.writeSampleData(outAudio, audioBuffer, audioInfo)
                        audioExtractor.advance()
                        audioPending = false
                    }

                    else -> break
                }
            }
            runCatching { muxer.stop() }
        } finally {
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith(prefix) == true) return index
        }
        return -1
    }

    private fun bufferSize(format: MediaFormat): Int =
        format.intOr(MediaFormat.KEY_MAX_INPUT_SIZE, DEFAULT_BUFFER_SIZE)
            .coerceAtLeast(DEFAULT_BUFFER_SIZE)

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

    // ---------------------------------------------------------------- EGL / GL

    private class EglState(window: Surface) {
        private val display: EGLDisplay
        private val context: EGLContext
        private val surface: EGLSurface

        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

            val configAttrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(
                EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, count, 0) &&
                    count[0] > 0 && configs[0] != null
            ) { "no EGL config" }
            val config = configs[0]!!

            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

            surface = EGL14.eglCreateWindowSurface(
                display,
                config,
                window,
                intArrayOf(EGL14.EGL_NONE),
                0
            )
            check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        }

        fun makeCurrent() {
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
                "eglMakeCurrent failed"
            }
        }

        fun createExternalTexture(): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            check(ids[0] != 0) { "could not create texture" }
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
            )
            return ids[0]
        }

        fun swapBuffers(timestampNs: Long) {
            EGLExt.eglPresentationTimeANDROID(display, surface, timestampNs)
            check(EGL14.eglSwapBuffers(display, surface)) { "eglSwapBuffers failed" }
        }

        fun release() {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
    }

    private class FrameRenderer(
        private val textureId: Int,
        private val viewportWidth: Int,
        private val viewportHeight: Int,
        flipX: Boolean,
        flipY: Boolean
    ) {
        private val program: Int
        private val positionSlot: Int
        private val texCoordSlot: Int
        private val texMatrixSlot: Int
        private val samplerSlot: Int
        private val vertices: FloatBuffer
        private val texMatrix = FloatArray(16)

        init {
            program = link(
                """
                attribute vec4 aPosition;
                attribute vec2 aTexCoord;
                uniform mat4 uTexMatrix;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
                }
                """.trimIndent(),
                """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTexCoord;
                uniform samplerExternalOES sTexture;
                void main() {
                    gl_FragColor = texture2D(sTexture, vTexCoord);
                }
                """.trimIndent()
            )
            positionSlot = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordSlot = GLES20.glGetAttribLocation(program, "aTexCoord")
            texMatrixSlot = GLES20.glGetUniformLocation(program, "uTexMatrix")
            samplerSlot = GLES20.glGetUniformLocation(program, "sTexture")

            // Flipping the texture coordinates (inside the SurfaceTexture transform) mirrors the
            // rendered frame without touching the quad, so the transform stays valid as-is.
            val u0 = if (flipX) 1f else 0f
            val u1 = if (flipX) 0f else 1f
            val v0 = if (flipY) 1f else 0f
            val v1 = if (flipY) 0f else 1f
            val data = floatArrayOf(
                -1f, -1f, u0, v0,
                1f, -1f, u1, v0,
                -1f, 1f, u0, v1,
                1f, 1f, u1, v1
            )
            vertices = ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(data)
                    position(0)
                }
        }

        fun draw(surfaceTexture: SurfaceTexture) {
            surfaceTexture.getTransformMatrix(texMatrix)

            GLES20.glUseProgram(program)
            GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(samplerSlot, 0)
            GLES20.glUniformMatrix4fv(texMatrixSlot, 1, false, texMatrix, 0)

            vertices.position(0)
            GLES20.glVertexAttribPointer(
                positionSlot, 2, GLES20.GL_FLOAT, false, 4 * 4, vertices
            )
            vertices.position(2)
            GLES20.glVertexAttribPointer(
                texCoordSlot, 2, GLES20.GL_FLOAT, false, 4 * 4, vertices
            )
            GLES20.glEnableVertexAttribArray(positionSlot)
            GLES20.glEnableVertexAttribArray(texCoordSlot)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionSlot)
            GLES20.glDisableVertexAttribArray(texCoordSlot)
        }

        fun release() {
            GLES20.glDeleteProgram(program)
        }

        private fun link(vertexSource: String, fragmentSource: String): Int {
            val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            val id = GLES20.glCreateProgram()
            GLES20.glAttachShader(id, vertex)
            GLES20.glAttachShader(id, fragment)
            GLES20.glLinkProgram(id)
            val status = IntArray(1)
            GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            check(status[0] == GLES20.GL_TRUE) { "could not link mirror program" }
            return id
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) {
                "mirror shader failed: ${GLES20.glGetShaderInfoLog(shader)}"
            }
            return shader
        }
    }
}
