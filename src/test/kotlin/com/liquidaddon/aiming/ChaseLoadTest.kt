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

    @Test
    fun `the peak remembers the hardest demand of the session`() {
        val peak = LoadPeak()
        peak.see(0.25f, fromStall = false)
        peak.see(0.75f, fromStall = false)
        peak.see(0.5f, fromStall = false)

        assertEquals(75, peak.pct)
    }

    @Test
    fun `the tick that resumes a freeze never becomes the peak`() {
        val peak = LoadPeak()
        // The queued jump of a long freeze: thousands of percent of a demand that is not the
        // target's, and exactly the tick the guard names RESUME.
        peak.see(22.27f, fromStall = true)
        assertEquals(0, peak.pct)

        peak.see(0.5f, fromStall = false)
        assertEquals(50, peak.pct)

        peak.see(22.27f, fromStall = true)
        assertEquals(50, peak.pct, "a later freeze must not rewrite the fight's peak either")
    }

    @Test
    fun `a demand that is not a number or not a demand is not counted`() {
        val peak = LoadPeak()
        peak.see(Float.NaN, fromStall = false)
        peak.see(-3f, fromStall = false)
        peak.see(0f, fromStall = false)
        assertEquals(0, peak.pct)
    }

    @Test
    fun `reset forgets the peak`() {
        val peak = LoadPeak()
        peak.see(0.75f, fromStall = false)
        peak.reset()
        assertEquals(0, peak.pct)
        assertEquals(0f, peak.peak, 1.0e-6f)
    }
}
