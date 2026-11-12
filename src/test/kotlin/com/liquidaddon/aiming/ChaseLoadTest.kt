package com.liquidaddon.aiming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChaseLoadTest {

    @Test
    fun `no demand gets all of the help`() {
        assertEquals(1f, ChaseLoad.loadShare(0f))
        assertEquals(1f, ChaseLoad.loadShare(-2f), "a negative load is no load")
        assertEquals(1f, ChaseLoad.loadShare(Float.NaN), "a delta out of nowhere is no load")
    }

    @Test
    fun `at the cap and past it the share sits on the floor`() {
        assertEquals(ChaseLoad.FLOOR, ChaseLoad.loadShare(1f), 1.0e-6f)
        assertEquals(ChaseLoad.FLOOR, ChaseLoad.loadShare(4f), 1.0e-6f)
    }

    @Test
    fun `the walk is straight and has no flat region`() {
        assertEquals(0.775f, ChaseLoad.loadShare(0.5f), 1.0e-6f)
        var previous = 1f
        var t = 0f
        while (t <= 1f) {
            val share = ChaseLoad.loadShare(t)
            assertTrue(share <= previous, "share must not climb back: $t -> $share")
            if (t > 0f) assertTrue(share < previous, "every step of load costs share: $t -> $share")
            previous = share
            t += 0.05f
        }
    }
}
