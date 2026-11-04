package com.necra.purecam.core

import org.junit.Assert.assertEquals
import org.junit.Test

class OrientationMathTest {

    @Test
    fun normalizeDegrees_wrapsIntoZeroTo359() {
        assertEquals(0, OrientationMath.normalizeDegrees(0))
        assertEquals(90, OrientationMath.normalizeDegrees(450))
        assertEquals(270, OrientationMath.normalizeDegrees(-90))
        assertEquals(1, OrientationMath.normalizeDegrees(721))
        assertEquals(0, OrientationMath.normalizeDegrees(360))
    }

    @Test
    fun quantizeDeviceOrientation_returnsNearestQuarterTurn() {
        assertEquals(-1, OrientationMath.quantizeDeviceOrientation(-1))
        assertEquals(-1, OrientationMath.quantizeDeviceOrientation(-10))
        assertEquals(0, OrientationMath.quantizeDeviceOrientation(0))
        assertEquals(0, OrientationMath.quantizeDeviceOrientation(44))
        assertEquals(90, OrientationMath.quantizeDeviceOrientation(45))
        assertEquals(90, OrientationMath.quantizeDeviceOrientation(134))
        assertEquals(180, OrientationMath.quantizeDeviceOrientation(135))
        assertEquals(270, OrientationMath.quantizeDeviceOrientation(225))
        assertEquals(0, OrientationMath.quantizeDeviceOrientation(359))
    }

    @Test
    fun displayRotation_isOppositeOfPhysicalDeviceRotation() {
        assertEquals(0, OrientationMath.displayRotation(0))
        assertEquals(270, OrientationMath.displayRotation(90))
        assertEquals(180, OrientationMath.displayRotation(180))
        assertEquals(90, OrientationMath.displayRotation(270))
        assertEquals(90, OrientationMath.displayRotation(-90))
    }

    @Test
    fun jpegOrientation_backCameraAddsPhysicalRotation() {
        assertEquals(90, OrientationMath.jpegOrientation(90, 0, Facing.BACK))
        assertEquals(180, OrientationMath.jpegOrientation(90, 90, Facing.BACK))
        assertEquals(270, OrientationMath.jpegOrientation(90, 180, Facing.BACK))
        assertEquals(0, OrientationMath.jpegOrientation(90, 270, Facing.BACK))
    }

    @Test
    fun jpegOrientation_frontReversesPhysicalDeviceRotation() {
        // Camera2 requires reversing OrientationEventListener rotation for front-facing lenses.
        assertEquals(270, OrientationMath.jpegOrientation(270, 0, Facing.FRONT))
        assertEquals(180, OrientationMath.jpegOrientation(270, 90, Facing.FRONT))
        assertEquals(90, OrientationMath.jpegOrientation(270, 180, Facing.FRONT))
        assertEquals(0, OrientationMath.jpegOrientation(270, 270, Facing.FRONT))
    }

    @Test
    fun jpegOrientation_normalizesOutOfRangeInputs() {
        assertEquals(90, OrientationMath.jpegOrientation(-270, 0, Facing.BACK))
        assertEquals(90, OrientationMath.jpegOrientation(450, 0, Facing.BACK))
        assertEquals(0, OrientationMath.jpegOrientation(90, -90, Facing.BACK))
    }

    @Test
    fun iconRotation_isOppositeDeviceRotation() {
        assertEquals(0f, OrientationMath.iconRotation(0), 0f)
        assertEquals(-90f, OrientationMath.iconRotation(90), 0f)
        assertEquals(-270f, OrientationMath.iconRotation(270), 0f)
    }
}
