package com.liquidaddon.aiming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiFeaturesTest {

    private fun assertClose(expected: Float, actual: Float, what: String) {
        assertTrue(
            kotlin.math.abs(expected - actual) < 1e-4f,
            "$what: expected $expected, got $actual"
        )
    }

    @Test
    fun `the six slots are the documented ones, in the documented order`() {
        // current looks at yaw 10 / pitch 0, one tick ago it looked at yaw 5 / pitch -2, the goal
        // is yaw 20 / pitch 3; the player moved (0.3, 0.4) horizontally, the target did not move,
        // the squared distance is 9.
        val f = AiFeatures.features(
            currentYaw = 10f, currentPitch = 0f,
            previousYaw = 5f, previousPitch = -2f,
            targetYaw = 20f, targetPitch = 3f,
            playerDiffX = 0.3f, playerDiffZ = 0.4f,
            targetDiffX = 0f, targetDiffZ = 0f,
            squaredDistance = 9f,
        )!!
        assertEquals(AiFeatures.INPUT_SIZE, f.size)
        assertClose(10f, f[0], "deltaYaw current->target")
        assertClose(3f, f[1], "deltaPitch current->target")
        assertClose(5f, f[2], "deltaYaw previous->current")
        assertClose(2f, f[3], "deltaPitch previous->current")
        assertClose(0.5f, f[4], "speed: horizontal player diff plus horizontal target diff")
        assertClose(9f, f[5], "squared distance, already squared")
    }

    @Test
    fun `deltas cross the meridian the way wrapDegrees does`() {
        val f = AiFeatures.features(
            currentYaw = 179f, currentPitch = 0f,
            previousYaw = -179f, previousPitch = 0f,
            targetYaw = -179f, targetPitch = 0f,
            playerDiffX = 0f, playerDiffZ = 0f,
            targetDiffX = 0f, targetDiffZ = 0f,
            squaredDistance = 4f,
        )!!
        assertClose(2f, f[0], "-179 is two degrees east of 179, not 358 west")
        assertClose(-2f, f[2], "and the previous step wraps the same way")
        assertEquals(2f, AiFeatures.wrap(-358f))
        assertEquals(-180f, AiFeatures.wrap(180f))
    }

    @Test
    fun `the speed is horizontal only, and adds both movers`() {
        val f = AiFeatures.features(
            currentYaw = 0f, currentPitch = 0f,
            previousYaw = 0f, previousPitch = 0f,
            targetYaw = 0f, targetPitch = 0f,
            playerDiffX = 0f, playerDiffZ = -0.2f,
            targetDiffX = 0.07f, targetDiffZ = 0.24f,
            squaredDistance = 1f,
        )!!
        // |playerDiff| xz = 0.2, |targetDiff| xz = 0.25 (the 7/24/25 triple): vertical never counts.
        assertClose(0.45f, f[4], "speed")
    }

    @Test
    fun `a corrupt situation has no features at all`() {
        assertNull(
            AiFeatures.features(
                currentYaw = Float.NaN, currentPitch = 0f,
                previousYaw = 0f, previousPitch = 0f,
                targetYaw = 0f, targetPitch = 0f,
                playerDiffX = 0f, playerDiffZ = 0f,
                targetDiffX = 0f, targetDiffZ = 0f,
                squaredDistance = 1f,
            ),
            "a NaN rotation must not become an input"
        )
        assertNull(
            AiFeatures.features(
                currentYaw = 0f, currentPitch = 0f,
                previousYaw = 0f, previousPitch = 0f,
                targetYaw = 0f, targetPitch = 0f,
                playerDiffX = 0f, playerDiffZ = 0f,
                targetDiffX = 0f, targetDiffZ = 0f,
                squaredDistance = Float.POSITIVE_INFINITY,
            ),
            "an infinite distance must not become an input"
        )
    }

    @Test
    fun `usable is the guard the engine puts in front of predict`() {
        assertTrue(AiFeatures.usable(FloatArray(6)))
        assertFalse(AiFeatures.usable(FloatArray(5)), "a short vector is not six features")
        assertFalse(
            AiFeatures.usable(floatArrayOf(0f, 0f, 0f, 0f, 0f, Float.NaN)),
            "one NaN poisons the whole vector"
        )
    }
}
