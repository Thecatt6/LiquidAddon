package com.liquidaddon.aiming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EdgeGraceTest {

    @Test
    fun `on the box the help is full and the gate never expires`() {
        val grace = EdgeGrace()
        grace.enter()
        assertEquals(1f, grace.share(1_000L))
        assertFalse(grace.expired(9_999_999L))
        assertEquals(0L, grace.lastGraceMs)
    }

    @Test
    fun `leaving draws a fade and the cut lands on zero`() {
        val grace = EdgeGrace()
        grace.enter()
        grace.leave(1_000L, 140L, 0.5f) // 140 * 1.05 = 147 ms
        assertEquals(147L, grace.lastGraceMs)
        assertEquals(1f, grace.share(1_000L), 1.0e-6f)
        assertEquals(0.5f, grace.share(1_000L + 73), 1.0e-2f)
        assertEquals(0f, grace.share(1_147L), 1.0e-6f)
        assertTrue(grace.expired(1_148L))
        assertFalse(grace.expired(1_100L))
    }

    @Test
    fun `two exits draw two latencies`() {
        val quick = EdgeGrace()
        quick.enter(); quick.leave(0L, 140L, 0f)
        val slow = EdgeGrace()
        slow.enter(); slow.leave(0L, 140L, 1f)
        assertEquals(84L, quick.lastGraceMs)
        assertEquals(210L, slow.lastGraceMs)
        assertTrue(!quick.expired(83L) && slow.expired(210L))
    }

    @Test
    fun `coming back onto the box cancels the fade at full`() {
        val grace = EdgeGrace()
        grace.enter()
        grace.leave(1_000L, 140L, 0.5f)
        grace.enter()
        assertEquals(1f, grace.share(1_050L))
        assertFalse(grace.expired(1_050L))
    }

    @Test
    fun `a repeated leave does not redraw the fade`() {
        val grace = EdgeGrace()
        grace.enter()
        grace.leave(1_000L, 140L, 0f)
        grace.leave(1_050L, 140L, 1f)
        assertEquals(84L, grace.lastGraceMs, "the first draw stands")
    }
}
