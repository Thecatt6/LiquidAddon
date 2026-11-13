package com.liquidaddon.aiming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BeatTest {

    @Test
    fun `a beat eases out and eases back in, symmetrically`() {
        val beat = (0 until 6).map { Beat.share(it, length = 6, depth = 0f, ramp = 2) }
        assertEquals(beat[0], beat[5], 1.0e-6f, "the two ends of the beat agree")
        assertEquals(beat[1], beat[4], 1.0e-6f)
        assertTrue(beat[0] > beat[1], "easing down: ${beat}")
        assertTrue(beat[1] > beat[2], "easing down: ${beat}")
        assertEquals(0f, beat[2], 1.0e-6f, "the bottom of a beat long enough for its ramps")
        assertEquals(0f, beat[3], 1.0e-6f)
        assertTrue(beat[4] < beat[5], "easing back up: ${beat}")
    }

    @Test
    fun `the first and last tick are never the bottom`() {
        // The whole point: the help does not leave in one tick and does not come back in one.
        assertTrue(Beat.share(0, 4, 0f, 1) > 0.4f, "one tick of ease is still half the help")
        assertTrue(Beat.share(3, 4, 0f, 1) > 0.4f)
    }

    @Test
    fun `a beat too short for its ramps is a triangle that never bottoms out`() {
        val beat = (0 until 3).map { Beat.share(it, length = 3, depth = 0f, ramp = 4) }
        assertTrue(beat.all { it > 0f }, "no tick reaches zero: $beat")
        assertTrue(beat[1] < beat[0] && beat[1] < beat[2], "and it still dips in the middle: $beat")
    }

    @Test
    fun `a ramp of zero is the old rectangle and a depth of one is no dip at all`() {
        val rectangle = (0 until 4).map { Beat.share(it, length = 4, depth = 0.15f, ramp = 0) }
        assertTrue(rectangle.all { kotlin.math.abs(it - 0.15f) < 1.0e-6f }, rectangle.toString())
        val flat = (0 until 4).map { Beat.share(it, length = 4, depth = 1f, ramp = 2) }
        assertTrue(flat.all { kotlin.math.abs(it - 1f) < 1.0e-6f }, flat.toString())
    }

    @Test
    fun `an empty beat and an out-of-range index are safe`() {
        assertEquals(1f, Beat.share(0, length = 0, depth = 0f, ramp = 2), 1.0e-6f)
        assertEquals(Beat.share(0, 4, 0f, 1), Beat.share(-3, 4, 0f, 1), 1.0e-6f)
        assertEquals(Beat.share(3, 4, 0f, 1), Beat.share(99, 4, 0f, 1), 1.0e-6f)
    }
}
