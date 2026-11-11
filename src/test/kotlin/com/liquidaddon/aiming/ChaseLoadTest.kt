package com.liquidaddon.aiming

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChaseLoadTest {

    @Test
    fun `below the knee the assist is unhindered`() {
        assertEquals(1f, ChaseLoad.loadShare(0f))
        assertEquals(1f, ChaseLoad.loadShare(ChaseLoad.KNEE))
        assertEquals(1f, ChaseLoad.loadShare(-2f), "a negative load is no load")
        assertEquals(1f, ChaseLoad.loadShare(Float.NaN), "a delta out of nowhere is no load")
    }

    @Test
    fun `at the cap and past it the share sits on the floor`() {
        assertEquals(ChaseLoad.FLOOR, ChaseLoad.loadShare(1f), 1.0e-6f)
        assertEquals(ChaseLoad.FLOOR, ChaseLoad.loadShare(4f), 1.0e-6f)
    }

    @Test
    fun `the share falls monotonically between knee and cap`() {
        var previous = 1f
        var t = ChaseLoad.KNEE
        while (t <= 1f) {
            val share = ChaseLoad.loadShare(t)
            assertTrue(share <= previous + 1.0e-6f, "share must not climb back: $t -> $share")
            previous = share
            t += 0.05f
        }
    }

    @Test
    fun `the easing leaves the knee without an edge`() {
        val at = ChaseLoad.loadShare(ChaseLoad.KNEE)
        val just = ChaseLoad.loadShare(ChaseLoad.KNEE + 1.0e-4f)
        assertTrue(abs(at - just) < 1.0e-6f, "smoothstep is flat where the easing begins: $at vs $just")
    }
}
