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
    fun `the shape draw picks one of three curves, all from zero to one`() {
        val easeIn = Onset()
        easeIn.begin(0L, 400, 0.5f, 0.1f)
        val smooth = Onset()
        smooth.begin(0L, 400, 0.5f, 0.5f)
        val easeOut = Onset()
        easeOut.begin(0L, 400, 0.5f, 0.9f)
        // Halfway through each one's own drawn duration the three families sit apart; reading the
        // half from the class keeps the assert out of float rounding on the draw.
        assertEquals(0.25f, easeIn.progress(easeIn.lastDurationMs.toLong() / 2), 5.0e-3f)
        assertEquals(0.50f, smooth.progress(smooth.lastDurationMs.toLong() / 2), 5.0e-3f)
        assertEquals(0.75f, easeOut.progress(easeOut.lastDurationMs.toLong() / 2), 5.0e-3f)
        for (o in listOf(easeIn, smooth, easeOut)) {
            assertEquals(0f, o.progress(0L), 1.0e-6f)
            assertEquals(1f, o.progress(o.lastDurationMs.toLong()), 1.0e-6f)
        }
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
