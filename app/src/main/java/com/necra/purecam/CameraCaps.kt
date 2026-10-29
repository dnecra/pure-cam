package com.necra.purecam

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.util.Log
import android.util.Size
import com.necra.purecam.core.Facing
import com.necra.purecam.core.LensMode
import com.necra.purecam.core.LensProfile
import com.necra.purecam.core.RangeF
import com.necra.purecam.core.RangeI
import com.necra.purecam.core.RangeL
import com.necra.purecam.core.RectI
import com.necra.purecam.core.SizeI
import com.necra.purecam.core.WbPreset
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class LensCatalog(
    val byFacing: Map<Facing, List<LensProfile>>
) {
    fun lenses(facing: Facing): List<LensProfile> = byFacing[facing].orEmpty()

    fun main(facing: Facing): LensProfile? {
        val list = lenses(facing)
        if (list.isEmpty()) return null
        val target = 26f
        return list.minWithOrNull(
            compareBy<LensProfile> { if (it.focalEquivalentMm <= 0f) 999f else abs(it.focalEquivalentMm - target) }
                .thenBy { modePriority(it.mode) }
        )
    }

    fun nextFacing(from: Facing): Facing {
        val back = byFacing[Facing.BACK].orEmpty()
        val front = byFacing[Facing.FRONT].orEmpty()
        return when {
            from == Facing.BACK && front.isNotEmpty() -> Facing.FRONT
            from == Facing.FRONT && back.isNotEmpty() -> Facing.BACK
            back.isNotEmpty() -> Facing.BACK
            front.isNotEmpty() -> Facing.FRONT
            else -> from
        }
    }

    private fun modePriority(mode: LensMode): Int = when (mode) {
        LensMode.DIRECT -> 0
        LensMode.PHYSICAL -> 1
        LensMode.LOGICAL -> 2
    }
}

/**
 * Reads Camera2 characteristics and turns them into per-lens profiles and a probe order.
 * Everything device-specific is logged so the M0 capability table can be built from logcat.
 */
object CameraCaps {

    private const val TAG = "PureCam/Caps"

    private data class Group(
        val cameraId: String,
        val characteristics: CameraCharacteristics,
        val physicalIds: List<String>
    )

    fun discover(manager: CameraManager): LensCatalog {
        val ids = try {
            manager.cameraIdList
        } catch (t: Throwable) {
            Log.e(TAG, "cameraIdList failed", t)
            emptyArray()
        }

        val groups = ArrayList<Group>()
        val directIds = ArrayList<String>()
        val physicalIds = HashSet<String>()
        val groupIds = HashSet<String>()
        val nonLogicalIds = ArrayList<String>()

        for (id in ids) {
            val ch = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            if (facing == null || facing == CameraMetadata.LENS_FACING_EXTERNAL) continue
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val logical = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            if (!logical) {
                nonLogicalIds.add(id)
                continue
            }
            val pids: List<String> = try {
                ch.getPhysicalCameraIds().toList()
            } catch (t: Throwable) {
                Log.w(TAG, "getPhysicalCameraIds($id) failed: ${t.message}")
                emptyList()
            }
            groups.add(Group(id, ch, pids))
            groupIds.add(id)
            physicalIds.addAll(pids)
        }

        // Physical camera IDs may appear in cameraIdList as well. They are handled inside the
        // logical group with strategies B/C, so only genuinely standalone cameras are direct.
        for (id in nonLogicalIds) {
            if (!physicalIds.contains(id)) directIds.add(id)
        }

        val byFacing = HashMap<Facing, MutableList<LensProfile>>()

        for (group in groups) {
            val logicalCh = group.characteristics
            val facing = facingOf(logicalCh) ?: continue
            val map = logicalCh.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)?.map { it.toSizeI() }.orEmpty()
            val previewSizes = map?.getOutputSizes(SurfaceTexture::class.java)?.map { it.toSizeI() }.orEmpty()

            val profiles = ArrayList<LensProfile>()
            if (group.physicalIds.isNotEmpty()) {
                for (pid in group.physicalIds) {
                    val pch = runCatching { manager.getCameraCharacteristics(pid) }.getOrNull()
                    if (pch == null) {
                        Log.w(TAG, "no characteristics for physical camera $pid")
                        continue
                    }
                    val profile = buildProfile(
                        cameraId = group.cameraId,
                        physicalId = pid,
                        mode = LensMode.PHYSICAL,
                        characteristics = pch,
                        logicalCharacteristics = logicalCh,
                        jpegSizes = jpegSizes,
                        previewSizes = previewSizes,
                        facing = facing
                    )
                    if (profile.focalEquivalentMm > 0f) profiles.add(profile)
                }
            }

            val selected = ArrayList<LensProfile>()
            if (profiles.isEmpty()) {
                selected.add(
                    buildProfile(
                        cameraId = group.cameraId,
                        physicalId = null,
                        mode = LensMode.LOGICAL,
                        characteristics = logicalCh,
                        logicalCharacteristics = logicalCh,
                        jpegSizes = jpegSizes,
                        previewSizes = previewSizes,
                        facing = facing
                    )
                )
            } else {
                selected.addAll(profiles)
                if (profiles.none { abs(it.focalEquivalentMm - 26f) < 8f }) {
                    // No physical profile looks like the main lens; keep the logical camera as a fallback.
                    selected.add(
                        buildProfile(
                            cameraId = group.cameraId,
                            physicalId = null,
                            mode = LensMode.LOGICAL,
                            characteristics = logicalCh,
                            logicalCharacteristics = logicalCh,
                            jpegSizes = jpegSizes,
                            previewSizes = previewSizes,
                            facing = facing
                        )
                    )
                }
            }

            finishGroup(selected, logicalCh, isLogicalCamera = true).forEach { p ->
                byFacing.getOrPut(p.facing) { ArrayList() }.add(p)
            }
        }

        // Cameras that are not part of a logical group: strategy A, open directly.
        for (id in directIds) {
            val ch = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val facing = facingOf(ch) ?: continue
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)?.map { it.toSizeI() }.orEmpty()
            val previewSizes = map?.getOutputSizes(SurfaceTexture::class.java)?.map { it.toSizeI() }.orEmpty()
            val profile = buildProfile(
                cameraId = id,
                physicalId = null,
                mode = LensMode.DIRECT,
                characteristics = ch,
                logicalCharacteristics = ch,
                jpegSizes = jpegSizes,
                previewSizes = previewSizes,
                facing = facing
            )
            finishGroup(listOf(profile), ch, isLogicalCamera = false).forEach { p ->
                byFacing.getOrPut(p.facing) { ArrayList() }.add(p)
            }
        }

        val result = byFacing.mapValues { (facing, list) ->
            val deduped = list.distinctBy { it.id }
                .sortedWith(compareBy({ it.baseZoom }, { it.label }))
            Log.i(TAG, "facing=$facing lenses=${deduped.size}")
            deduped.forEach { p -> Log.i(TAG, "  $p") }
            deduped
        }
        return LensCatalog(result)
    }

    /** Assigns labels, relative zoom and the per-lens zoom cap. */
    private fun finishGroup(
        profiles: List<LensProfile>,
        logicalCh: CameraCharacteristics,
        isLogicalCamera: Boolean
    ): List<LensProfile> {
        if (profiles.isEmpty()) return emptyList()
        val withFocal = profiles.filter { it.focalEquivalentMm > 0f }
        val mainEq = if (withFocal.isEmpty()) {
            26f
        } else {
            withFocal.minByOrNull { abs(it.focalEquivalentMm - 26f) }!!.focalEquivalentMm
        }
        val logicalMaxZoom = logicalCh.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val idCounts = HashMap<String, Int>()

        return profiles.map { p ->
            val ratio = if (p.focalEquivalentMm > 0f) p.focalEquivalentMm / mainEq else 1f
            var label = formatRatio(ratio)
            val count = (idCounts[p.id] ?: 0) + 1
            idCounts[p.id] = count
            if (count > 1) label = "$label ($count)"
            val baseZoom = if (isLogicalCamera && p.mode != LensMode.DIRECT) ratio else 1f
            val maxZoom = if (baseZoom > 0f) min(5f, (logicalMaxZoom / baseZoom).coerceAtLeast(1f)) else 5f
            p.copy(
                label = label,
                baseZoom = if (baseZoom > 0f) baseZoom else 1f,
                maxZoom = maxZoom.coerceIn(1f, 5f),
                zoomRatioRange = p.zoomRatioRange
            )
        }
    }

    private fun formatRatio(ratio: Float): String {
        if (ratio <= 0f) return "1\u00d7"
        if (ratio in 0.95f..1.05f) return "1\u00d7"
        val rounded = ratio.roundToInt()
        return if (abs(ratio - rounded) < 0.08f) {
            "$rounded\u00d7"
        } else {
            String.format("%.1f\u00d7", ratio)
        }
    }

    private fun buildProfile(
        cameraId: String,
        physicalId: String?,
        mode: LensMode,
        characteristics: CameraCharacteristics,
        logicalCharacteristics: CameraCharacteristics,
        jpegSizes: List<SizeI>,
        previewSizes: List<SizeI>,
        facing: Facing
    ): LensProfile {
        val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
        val manualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val manualPost = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING)

        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.toRectI()
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.toRectI()
            ?: jpegSizes.maxByOrNull { it.area }?.toRectI()
            ?: RectI(0, 0, 4000, 3000)
        val logicalActive = logicalCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.toRectI()
            ?: logicalCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.toRectI()
            ?: active

        val pixelArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.toRectI()
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.toRangeI()
        val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.toRangeL()
        val maxFrame = characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
            ?: 1_000_000_000L
        val postRaw = characteristics.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)?.toRangeI()

        val minFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val focusCalibration = characteristics.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
        val focusCalibrated =
            focusCalibration == CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED

        val awbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
        val awbPresets = awbModes.toList().mapNotNull { awbPreset(it) }.toSet()

        val aeModes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: intArrayOf()
        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()

        val nrModes = characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf()
        val edgeModes = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: intArrayOf()
        val hotPixelModes = characteristics.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES) ?: intArrayOf()
        val aberrationModes =
            characteristics.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES) ?: intArrayOf()
        val distortionModes = characteristics.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES) ?: intArrayOf()
        val videoStabModes =
            characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
        val tonemapModes = characteristics.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: intArrayOf()

        val logicalMaxZoom = logicalCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val zoomRatioRange = logicalCharacteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            ?.let { RangeF(it.lower, it.upper) }

        // Video buffers come from the same stream map, but not every lens advertises a recorder
        // output (logical-only or stills-only cameras). Fall back to its preview stream, which the
        // encoder can still consume; VideoMath keeps only wide, recordable sizes.
        val ownMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val videoSizes = ownMap?.getOutputSizes(MediaRecorder::class.java)?.map { it.toSizeI() }.orEmpty()
            .ifEmpty { ownMap?.getOutputSizes(SurfaceTexture::class.java)?.map { it.toSizeI() }.orEmpty() }
            .filter { it.width > it.height }
            .sortedByDescending { it.area }
        val videoFpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { it.lower..it.upper }
            .orEmpty()

        val focalEq = equivalentFocal(characteristics)
        val id = if (physicalId != null) "logical:$cameraId:physical:$physicalId" else "camera:$cameraId"

        val profile = LensProfile(
            id = id,
            cameraId = cameraId,
            physicalId = physicalId,
            mode = mode,
            facing = facing,
            label = "1\u00d7",
            focalEquivalentMm = focalEq,
            baseZoom = 1f,
            sensorOrientation = sensorOrientation,
            activeArray = active,
            logicalActiveArray = logicalActive,
            pixelArray = pixelArray,
            jpegSizes = jpegSizes.sortedByDescending { it.area },
            previewSizes = previewSizes.sortedByDescending { it.area },
            videoSizes = videoSizes,
            videoFpsRanges = videoFpsRanges,
            isoRange = isoRange,
            maxAnalogIso = characteristics.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY),
            exposureRange = exposureRange,
            maxFrameDurationNs = maxFrame,
            postRawBoostRange = postRaw,
            minFocusDiopters = minFocus,
            focusCalibrated = focusCalibrated,
            awbPresets = awbPresets,
            alwaysFlashSupported = aeModes.contains(CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH),
            afContinuousSupported = afModes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE),
            afAutoSupported = afModes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO),
            manualSensor = manualSensor,
            manualPostProcessing = manualPost,
            noiseReductionOff = nrModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_OFF),
            edgeOff = edgeModes.contains(CameraMetadata.EDGE_MODE_OFF),
            hotPixelOff = hotPixelModes.contains(CameraMetadata.HOT_PIXEL_MODE_OFF),
            aberrationOff = aberrationModes.contains(CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF),
            distortionOff = distortionModes.contains(CameraMetadata.DISTORTION_CORRECTION_MODE_OFF),
            videoStabilizationOff = videoStabModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF),
            tonemapPresetCurve = tonemapModes.contains(CameraMetadata.TONEMAP_MODE_PRESET_CURVE),
            flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false,
            maxZoom = min(5f, logicalMaxZoom.coerceAtLeast(1f)),
            zoomRatioRange = zoomRatioRange,
            colorTransform1 = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.toFloatArray(),
            colorTransform2 = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.toFloatArray(),
            referenceIlluminant1 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: 1,
            referenceIlluminant2 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt() ?: 20
        )

        Log.i(
            TAG,
            "lens id=$id mode=$mode facing=$facing focalEq=${"%.1f".format(focalEq)}mm " +
                "iso=$isoRange shutter=$exposureRange focus=$minFocus manual=$manualSensor/$manualPost " +
                "sizes=${jpegSizes.size} video=${videoSizes.firstOrNull()} fps=$videoFpsRanges " +
                "zoom=${zoomRatioRange} awb=${awbPresets.map { it.name }}"
        )
        return profile
    }

    private fun equivalentFocal(ch: CameraCharacteristics): Float {
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 0f
        val physical = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 0f
        val diagonal = sqrt(physical.width * physical.width + physical.height * physical.height)
        if (diagonal <= 0f) return 0f
        return focal * 43.27f / diagonal
    }

    private fun facingOf(ch: CameraCharacteristics): Facing? = when (ch.get(CameraCharacteristics.LENS_FACING)) {
        CameraMetadata.LENS_FACING_BACK -> Facing.BACK
        CameraMetadata.LENS_FACING_FRONT -> Facing.FRONT
        else -> null
    }

    private fun awbPreset(mode: Int): WbPreset? = when (mode) {
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> WbPreset.INCANDESCENT
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> WbPreset.FLUORESCENT
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> WbPreset.WARM_FLUORESCENT
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> WbPreset.DAYLIGHT
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> WbPreset.CLOUDY_DAYLIGHT
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> WbPreset.TWILIGHT
        CameraMetadata.CONTROL_AWB_MODE_SHADE -> WbPreset.SHADE
        else -> null
    }

    fun report(catalog: LensCatalog): String = buildString {
        appendLine("PureCam capability report")
        appendLine("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        appendLine("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        for ((facing, lenses) in catalog.byFacing) {
            appendLine()
            appendLine("$facing (${lenses.size})")
            lenses.forEach { p ->
                appendLine("  ${p.label} ${"%.1f".format(p.focalEquivalentMm)}mm  ${p.mode}")
                appendLine("    id=${p.id}")
                appendLine("    JPEG ${p.jpegSizes.firstOrNull()}  preview ${p.previewSizes.firstOrNull()}")
                appendLine("    video ${p.videoSizes.firstOrNull() ?: "-"}  fps ${p.videoFpsRanges}")
                appendLine("    manual exposure=${p.supportsManualExposure} focus=${p.supportsManualFocus} kelvin=${p.supportsKelvin}")
                appendLine("    ISO=${p.isoRange} shutter=${p.exposureRange}")
                appendLine("    focus=${p.minFocusDiopters}${if (p.focusCalibrated) " calibrated" else ""} flash=${p.flashAvailable}")
                appendLine("    zoom range=${p.zoomRatioRange} max=${p.maxZoom}")
                appendLine("    off: NR=${p.noiseReductionOff} edge=${p.edgeOff} hot=${p.hotPixelOff} CA=${p.aberrationOff} dist=${p.distortionOff} stab=${p.videoStabilizationOff} tonemap=${p.tonemapPresetCurve}")
                appendLine("    presets=${p.awbPresets.joinToString { it.name }}")
            }
        }
    }

    private fun Size.toSizeI(): SizeI = SizeI(width, height)
    private fun Rect.toRectI(): RectI = RectI(left, top, right, bottom)
    private fun Size.toRectI(): RectI = RectI(0, 0, width, height)
    private fun SizeI.toRectI(): RectI = RectI(0, 0, width, height)
    private fun android.util.Range<Int>.toRangeI(): RangeI = RangeI(lower, upper)
    private fun android.util.Range<Long>.toRangeL(): RangeL = RangeL(lower, upper)

    private fun ColorSpaceTransform.toFloatArray(): FloatArray {
        val elements = IntArray(18)
        copyElements(elements, 0)
        return FloatArray(9) { i ->
            val denominator = elements[i * 2 + 1]
            if (denominator == 0) 0f else elements[i * 2].toFloat() / denominator
        }
    }
}
