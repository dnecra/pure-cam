package com.rawcam.core

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualWhiteBalanceTest {

    private val neutralProfile = TestProfiles.lens(
        referenceIlluminant1 = 1,
        referenceIlluminant2 = 20
    )

    @Test
    fun clampKelvin_roundsToStepAndBounds() {
        assertEquals(2000, ManualWhiteBalance.clampKelvin(0))
        assertEquals(2000, ManualWhiteBalance.clampKelvin(2024))
        assertEquals(2050, ManualWhiteBalance.clampKelvin(2025))
        assertEquals(2050, ManualWhiteBalance.clampKelvin(2049))
        assertEquals(10000, ManualWhiteBalance.clampKelvin(10001))
        assertEquals(10000, ManualWhiteBalance.clampKelvin(20000))
    }

    @Test
    fun kelvinToXy_matchesKnownDaylightPoint() {
        val (x, y) = ManualWhiteBalance.kelvinToXy(6500)

        assertEquals(0.3135, x, 0.003)
        assertEquals(0.3237, y, 0.003)
    }

    @Test
    fun kelvinToXyz_isNormalizedToYOne() {
        val xyz = ManualWhiteBalance.kelvinToXyz(6500)
        val (x, y) = ManualWhiteBalance.kelvinToXy(6500)

        assertEquals(3, xyz.size)
        assertEquals(1f, xyz[1], 0f)
        assertEquals(x / y, xyz[0].toDouble(), 1e-6)
        assertEquals((1.0 - x - y) / y, xyz[2].toDouble(), 1e-6)
    }

    @Test
    fun multiply_appliesRowMajorMatrix() {
        val identity = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )
        val result = ManualWhiteBalance.multiply(identity, floatArrayOf(0.2f, 0.4f, 0.6f))

        assertEquals(0.2f, result[0], 1e-5f)
        assertEquals(0.4f, result[1], 1e-5f)
        assertEquals(0.6f, result[2], 1e-5f)
    }

    @Test
    fun interpolateMatrices_usesMiredInterpolation() {
        val m1 = FloatArray(9) { 0.1f }
        val m2 = FloatArray(9) { 0.2f }

        assertArrayEquals(m1, ManualWhiteBalance.interpolateMatrices(m1, m2, 6500, 3800, 6500), 1e-6f)
        assertArrayEquals(m2, ManualWhiteBalance.interpolateMatrices(m1, m2, 6500, 3800, 3800), 1e-6f)
        assertArrayEquals(m1, ManualWhiteBalance.interpolateMatrices(m1, m2, 6500, 3800, 20000), 1e-6f)
        assertArrayEquals(m2, ManualWhiteBalance.interpolateMatrices(m1, m2, 6500, 3800, 1000), 1e-6f)

        val middle = ManualWhiteBalance.interpolateMatrices(m1, m2, 6500, 3800, 4796)
        assertEquals(0.15f, middle[0], 0.001f)
    }

    @Test
    fun interpolateMatrices_fallsBackForWrongSize() {
        val fallback = ManualWhiteBalance.interpolateMatrices(floatArrayOf(1f), floatArrayOf(1f), 6500, 3800, 5000)

        assertArrayEquals(ManualWhiteBalance.defaultTransform(), fallback, 0f)
    }

    @Test
    fun interpolatedTransform_returnsNullWithoutCalibration() {
        assertNull(ManualWhiteBalance.interpolatedTransform(TestProfiles.lens(colorTransform1 = null), 5000))
    }

    @Test
    fun gainsForKelvin_normalizeGreenAndNeutralizeRequestedWhite() {
        val gains = ManualWhiteBalance.gainsForKelvin(neutralProfile, 6500)
        assertNotNull(gains)
        gains!!

        val xyz = ManualWhiteBalance.kelvinToXyz(6500)
        assertEquals(4, gains.size)
        assertEquals(1f, gains[1], 0f)
        assertEquals(1f, gains[2], 0f)
        assertTrue(gains[0] > 0f && gains[3] > 0f)
        assertEquals(1f, gains[0] * xyz[0], 2e-3f)
        assertEquals(1f, gains[3] * xyz[2], 2e-3f)
    }

    @Test
    fun gainsForKelvin_returnsNullWithoutMatrices() {
        assertNull(ManualWhiteBalance.gainsForKelvin(TestProfiles.lens(colorTransform1 = null), 5000))
    }

    @Test
    fun computeKelvin_usesDeviceFallbackTransform() {
        val fallback = FloatArray(9) { 0.5f }

        val result = ManualWhiteBalance.computeKelvin(neutralProfile, 5000, fallback)

        assertTrue(result.calibrated)
        assertEquals(4, result.gains.size)
        assertArrayEquals(fallback, result.transform, 0f)
    }

    @Test
    fun computeKelvin_uncalibratedUsesNeutralGainsAndDefaultTransform() {
        val profile = TestProfiles.lens(colorTransform1 = null, colorTransform2 = null)

        val result = ManualWhiteBalance.computeKelvin(profile, 5000, null)

        assertFalse(result.calibrated)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f, 1f), result.gains, 0f)
        assertArrayEquals(ManualWhiteBalance.defaultTransform(), result.transform, 0f)
    }

    @Test
    fun estimateKelvinFromGains_recoversSynthesizedTemperature() {
        for (kelvin in listOf(4000, 5000, 6500, 7500)) {
            val gains = ManualWhiteBalance.gainsForKelvin(neutralProfile, kelvin)!!
            val estimated = ManualWhiteBalance.estimateKelvinFromGains(neutralProfile, gains)
            assertTrue("estimated $estimated for $kelvin", abs(estimated - kelvin) <= 50)
        }
    }

    @Test
    fun estimateKelvinFromGains_defaultsWhenUnavailable() {
        assertEquals(5200, ManualWhiteBalance.estimateKelvinFromGains(neutralProfile, null))
        assertEquals(5200, ManualWhiteBalance.estimateKelvinFromGains(neutralProfile, floatArrayOf(1f, 1f)))
        assertEquals(
            5200,
            ManualWhiteBalance.estimateKelvinFromGains(TestProfiles.lens(colorTransform1 = null), floatArrayOf(1f, 1f, 1f, 1f))
        )
    }

    @Test
    fun normalizeGains_setsGreenToOneAndBoundsRedBlue() {
        assertArrayEquals(
            floatArrayOf(1f, 1f, 1f, 2f),
            ManualWhiteBalance.normalizeGains(floatArrayOf(2f, 2f, 2f, 4f)),
            1e-6f
        )
        assertArrayEquals(
            floatArrayOf(0.2f, 1f, 1f, 5f),
            ManualWhiteBalance.normalizeGains(floatArrayOf(0.1f, 1f, 1f, 6f)),
            1e-6f
        )
        assertArrayEquals(
            floatArrayOf(1f, 1f, 1f, 1f),
            ManualWhiteBalance.normalizeGains(floatArrayOf(1f, 0f, 0f, 2f)),
            0f
        )
        assertArrayEquals(
            floatArrayOf(1f, 1f, 1f, 1f),
            ManualWhiteBalance.normalizeGains(floatArrayOf(1f, 1f)),
            0f
        )
    }

    @Test
    fun describe_namesEveryMode() {
        assertEquals("Auto", ManualWhiteBalance.describe(WhiteBalanceMode.Auto))
        assertEquals(
            "Daylight 5500K",
            ManualWhiteBalance.describe(WhiteBalanceMode.Preset(WbPreset.DAYLIGHT))
        )
        assertEquals("4800 K", ManualWhiteBalance.describe(WhiteBalanceMode.Kelvin(4800)))
    }

    @Test
    fun presetKelvin_exposesNominalCct() {
        assertEquals(5500, ManualWhiteBalance.presetKelvin(WbPreset.DAYLIGHT))
        assertEquals(WbPreset.SHADE.cct, ManualWhiteBalance.presetKelvin(WbPreset.SHADE))
    }
}
