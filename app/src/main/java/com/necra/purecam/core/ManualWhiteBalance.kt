package com.necra.purecam.core

import kotlin.math.abs
import kotlin.math.ln

data class KelvinResult(
    /** RGGB channel gains, green normalized to 1. */
    val gains: FloatArray,
    /** 3x3 XYZ -> output RGB transform, row major. */
    val transform: FloatArray,
    val calibrated: Boolean
)

object ManualWhiteBalance {

    const val MIN_KELVIN = 2000
    const val MAX_KELVIN = 10000
    const val KELVIN_STEP = 50

    /** Standard sRGB (D65) XYZ -> linear RGB matrix, row major. */
    private val XYZ_TO_SRGB = floatArrayOf(
        3.2404542f, -1.5371385f, -0.4985314f,
        -0.9692660f, 1.8760108f, 0.0415560f,
        0.0556434f, -0.2040259f, 1.0572252f
    )

    fun clampKelvin(kelvin: Int): Int =
        ((kelvin.coerceIn(MIN_KELVIN, MAX_KELVIN) + KELVIN_STEP / 2) / KELVIN_STEP) * KELVIN_STEP

    /** Planckian locus approximation (Kim et al.). */
    fun kelvinToXy(kelvin: Int): Pair<Double, Double> {
        val t = kelvin.toDouble().coerceIn(1667.0, 25000.0)
        val x = if (t <= 4000.0) {
            -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910
        } else {
            -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390
        }
        val y = when {
            t <= 2222.0 -> -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            t <= 4000.0 -> -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
        }
        return x to y
    }

    fun kelvinToXyz(kelvin: Int): FloatArray {
        val (x, y) = kelvinToXy(kelvin)
        val yy = if (y <= 1e-6) 1.0 else y
        return floatArrayOf((x / yy).toFloat(), 1f, ((1.0 - x - y) / yy).toFloat())
    }

    /**
     * Camera RGB response to a white of the requested temperature, using the sensor's
     * two reference-illuminant transforms interpolated by mired.
     */
    fun interpolatedTransform(profile: LensProfile, kelvin: Int): FloatArray? {
        val m1 = profile.colorTransform1 ?: return null
        val m2 = profile.colorTransform2 ?: m1
        val c1 = cctForReference(profile.referenceIlluminant1)
        val c2 = cctForReference(profile.referenceIlluminant2)
        return if (c1 == c2) m1.copyOf() else interpolateMatrices(m1, m2, c1, c2, kelvin)
    }

    fun interpolateMatrices(m1: FloatArray, m2: FloatArray, cct1: Int, cct2: Int, kelvin: Int): FloatArray {
        if (m1.size != 9 || m2.size != 9) return XYZ_TO_SRGB.copyOf()
        val m1m = 1_000_000.0 / cct1.coerceAtLeast(1)
        val m2m = 1_000_000.0 / cct2.coerceAtLeast(1)
        val km = 1_000_000.0 / kelvin.coerceAtLeast(1)
        val t = if (abs(m2m - m1m) < 1e-6) 0.0 else ((km - m1m) / (m2m - m1m)).coerceIn(0.0, 1.0)
        val out = FloatArray(9)
        for (i in 0 until 9) out[i] = (m1[i] * (1.0 - t) + m2[i] * t).toFloat()
        return out
    }

    /** Green-normalized RGGB gains that make the requested white neutral. */
    fun gainsForKelvin(profile: LensProfile, kelvin: Int): FloatArray? {
        val matrix = interpolatedTransform(profile, kelvin) ?: return null
        val white = kelvinToXyz(kelvin)
        val neutral = multiply(matrix, white)
        if (abs(neutral[1]) < 1e-6f || neutral[0] <= 1e-6f || neutral[2] <= 1e-6f) return null
        val g = neutral[1]
        val r = (g / neutral[0]).coerceIn(0.2f, 5f)
        val b = (g / neutral[2]).coerceIn(0.2f, 5f)
        return floatArrayOf(r, 1f, 1f, b)
    }

    /**
     * @param fallbackTransform COLOR_CORRECTION_TRANSFORM from the last auto-AWB result.
     * Using the device's own transform avoids guessing the camera-to-sRGB matrix.
     */
    fun computeKelvin(profile: LensProfile, kelvin: Int, fallbackTransform: FloatArray?): KelvinResult {
        val gains = gainsForKelvin(profile, kelvin)
        val transform = fallbackTransform?.takeIf { it.size == 9 }?.copyOf()
            ?: XYZ_TO_SRGB.copyOf()
        return KelvinResult(
            gains = gains ?: floatArrayOf(1f, 1f, 1f, 1f),
            transform = transform,
            calibrated = gains != null
        )
    }

    fun estimateKelvinFromGains(profile: LensProfile, gains: FloatArray?): Int {
        if (gains == null || gains.size != 4 || !profile.supportsKelvin) return 5200
        val measuredR = gains[0] / gains[1].coerceAtLeast(1e-6f)
        val measuredB = gains[3] / gains[1].coerceAtLeast(1e-6f)
        var best = 5200
        var bestScore = Double.MAX_VALUE
        var k = MIN_KELVIN
        while (k <= MAX_KELVIN) {
            val candidate = gainsForKelvin(profile, k)
            if (candidate != null) {
                val score = abs(ln((candidate[0] / candidate[1]).toDouble()) - ln(measuredR.toDouble())) +
                    abs(ln((candidate[3] / candidate[1]).toDouble()) - ln(measuredB.toDouble()))
                if (score < bestScore) {
                    bestScore = score
                    best = k
                }
            }
            k += 50
        }
        return best
    }

    fun describe(mode: WhiteBalanceMode): String = when (mode) {
        is WhiteBalanceMode.Auto -> "Auto"
        is WhiteBalanceMode.Preset -> "${mode.preset.label} ${mode.preset.cct}K"
        is WhiteBalanceMode.Kelvin -> "${mode.kelvin} K"
    }

    fun multiply(m: FloatArray, v: FloatArray): FloatArray = floatArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2]
    )

    /** Approximate CCT for the two reference illuminants the sensor was calibrated against. */
    fun cctForReference(reference: Int): Int = when (reference) {
        1 -> 6500      // DAYLIGHT
        2 -> 3800      // FLUORESCENT
        3 -> 2856      // TUNGSTEN
        4 -> 5500      // FLASH
        9 -> 5500      // FINE_WEATHER
        10 -> 6500     // CLOUDY_WEATHER
        11 -> 7500     // SHADE
        12 -> 6500     // DAYLIGHT_FLUORESCENT
        13 -> 5000     // DAY_WHITE_FLUORESCENT
        14 -> 4200     // COOL_WHITE_FLUORESCENT
        15 -> 3500     // WHITE_FLUORESCENT
        16 -> 2856     // STANDARD_A
        17 -> 4874     // STANDARD_B
        18 -> 6774     // STANDARD_C
        19 -> 5500     // D55
        20 -> 6500     // D65
        21 -> 7500     // D75
        22 -> 5000     // D50
        23 -> 3200     // ISO_STUDIO_TUNGSTEN
        else -> 5500
    }

    fun defaultTransform(): FloatArray = XYZ_TO_SRGB.copyOf()

    /** Re-normalizes arbitrary gains so green = 1 and values stay usable. */
    fun normalizeGains(gains: FloatArray): FloatArray {
        if (gains.size != 4) return floatArrayOf(1f, 1f, 1f, 1f)
        val g = (gains[1] + gains[2]) / 2f
        if (abs(g) < 1e-6f) return floatArrayOf(1f, 1f, 1f, 1f)
        return floatArrayOf(
            (gains[0] / g).coerceIn(0.2f, 5f),
            1f,
            1f,
            (gains[3] / g).coerceIn(0.2f, 5f)
        )
    }

    /** Used by the UI to show a Kelvin marker for a preset. */
    fun presetKelvin(preset: WbPreset): Int = preset.cct
}
