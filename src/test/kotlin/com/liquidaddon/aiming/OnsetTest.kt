package com.liquidaddon.aiming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnsetTest {

    @Test
    fun `no fade means full help from the first tick`() {
        val onset = Onset()
        onset.begin(now = 1_000L, baseMs = 0, draw = 0.5f)
        assertEquals(1f, onset.progress(1_000L))
        assertEquals(0, onset.lastDurationMs)
    }

    @Test
    fun `the draw scales the base into the span`() {
        val low = Onset()
        low.begin(0L, 400, 0f)
        assertEquals((400 * Onset.SPAN_MIN).toInt(), low.lastDurationMs)
        val high = Onset()
        high.begin(0L, 400, 1f)
        assertEquals((400 * Onset.SPAN_MAX).toInt(), high.lastDurationMs)
        val clamped = Onset()
        clamped.begin(0L, 400, 9f)
        assertEquals(high.lastDurationMs, clamped.lastDurationMs, "a draw outside 0..1 clamps")
    }

    @Test
    fun `the fade leaves from zero and arrives at one over the drawn duration`() {
        val onset = Onset()
        onset.begin(1_000L, 400, 0f) // 300 ms
        assertEquals(0f, onset.progress(1_000L))
        assertEquals(0.5f, onset.progress(1_150L), 1.0e-6f, "the smoothstep middle")
        assertEquals(1f, onset.progress(1_300L))
        assertEquals(1f, onset.progress(9_999L))
    }

    @Test
    fun `two engagements with different draws disagree on when the help arrives`() {
        val quick = Onset()
        quick.begin(0L, 400, 0f)
        val slow = Onset()
        slow.begin(0L, 400, 1f)
        val quickAt = quick.progress(400L)
        val slowAt = slow.progress(400L)
        assertTrue(quickAt > slowAt, "the same instant sits later in the quick fade: $quickAt vs $slowAt")
        assertEquals(1f, quickAt)
    }

    @Test
    fun `the walk is monotone`() {
        val onset = Onset()
        onset.begin(0L, 300, 0.5f)
        var previous = 0f
        for (t in 0L..600L step 25) {
            val p = onset.progress(t)
            assertTrue(p >= previous - 1.0e-6f, "the fade never walks back: $t -> $p")
            previous = p
        }
    }
}
