package com.liquidaddon.modules.doubleclicks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DoubleClickSchedulerTest {
    @Test
    fun `one to three extras follow the original on later ticks`() {
        for (count in 1..3) {
            val scheduler = DoubleClickScheduler()
            scheduler.recordOriginal(count, ExtraClick.ATTACK)

            // The original's input tick can never contain one of our extras.
            assertNull(scheduler.takeNext(1))
            repeat(count) {
                assertEquals(ExtraClick.ATTACK, scheduler.takeNext(1))
            }
            assertNull(scheduler.takeNext(1))
            assertEquals(0, scheduler.pendingCount)
        }
    }

    @Test
    fun `delay spaces the first extra and subsequent extras`() {
        val scheduler = DoubleClickScheduler()
        scheduler.recordOriginal(2, ExtraClick.ATTACK)

        assertNull(scheduler.takeNext(2)) // original
        assertNull(scheduler.takeNext(2))
        assertEquals(ExtraClick.ATTACK, scheduler.takeNext(2))
        assertNull(scheduler.takeNext(2))
        assertEquals(ExtraClick.ATTACK, scheduler.takeNext(2))
        assertNull(scheduler.takeNext(2))
    }

    @Test
    fun `a new original click takes priority over pending extras`() {
        val scheduler = DoubleClickScheduler()
        scheduler.recordOriginal(1, ExtraClick.ATTACK)
        assertNull(scheduler.takeNext(1))

        scheduler.recordOriginal(1, ExtraClick.ATTACK)
        assertNull(scheduler.takeNext(1))
        assertEquals(ExtraClick.ATTACK, scheduler.takeNext(1))
        assertEquals(ExtraClick.ATTACK, scheduler.takeNext(1))
        assertNull(scheduler.takeNext(1))
    }

    @Test
    fun `rapid originals never leave an unbounded backlog`() {
        val scheduler = DoubleClickScheduler()
        repeat(100) {
            scheduler.recordOriginal(3, ExtraClick.ATTACK)
            assertNull(scheduler.takeNext(1))
            assertEquals(3, scheduler.pendingCount)
        }

        repeat(3) { assertEquals(ExtraClick.ATTACK, scheduler.takeNext(1)) }
        assertNull(scheduler.takeNext(1))
    }

    @Test
    fun `original block intent is preserved when queued with another click`() {
        val scheduler = DoubleClickScheduler()
        scheduler.recordOriginal(1, ExtraClick.SWING_ONLY)
        scheduler.recordOriginal(1, ExtraClick.ATTACK)

        assertNull(scheduler.takeNext(1))
        assertEquals(ExtraClick.SWING_ONLY, scheduler.takeNext(1))
        assertEquals(ExtraClick.ATTACK, scheduler.takeNext(1))
    }

    @Test
    fun `block mining and miss cooldown never turn into extra attacks`() {
        assertEquals(ExtraClick.SWING_ONLY, extraClickAction(ExtraClick.SWING_ONLY, true, false, false))
        assertEquals(ExtraClick.SWING_ONLY, extraClickAction(ExtraClick.ATTACK, false, false, false))
        assertEquals(ExtraClick.SWING_ONLY, extraClickAction(ExtraClick.ATTACK, true, true, false))
        assertEquals(ExtraClick.SWING_ONLY, extraClickAction(ExtraClick.ATTACK, true, false, true))
        assertEquals(ExtraClick.ATTACK, extraClickAction(ExtraClick.ATTACK, true, false, false))
    }

    @Test
    fun `right and left follow-ups keep their own kind`() {
        val left = DoubleClickScheduler()
        val right = DoubleClickScheduler()
        left.recordOriginal(1, ExtraClick.ATTACK)
        right.recordOriginal(3, ExtraClick.USE)

        assertNull(left.takeNext(1))
        assertNull(right.takeNext(1))
        assertEquals(ExtraClick.ATTACK, left.takeNext(1))
        repeat(3) { assertEquals(ExtraClick.USE, right.takeNext(1)) }
        assertNull(left.takeNext(1))
        assertNull(right.takeNext(1))
    }

    @Test
    fun `a use that vanilla would refuse is dropped, never turned into a swing`() {
        assertTrue(useExtraAllowed(ExtraClick.USE, false, false, false))
        assertFalse(useExtraAllowed(ExtraClick.USE, true, false, false))
        assertFalse(useExtraAllowed(ExtraClick.USE, false, true, false))
        assertFalse(useExtraAllowed(ExtraClick.USE, false, false, true))
        assertFalse(useExtraAllowed(ExtraClick.ATTACK, false, false, false))
        assertFalse(useExtraAllowed(ExtraClick.SWING_ONLY, false, false, false))
    }

    @Test
    fun `clearing discards pending actions and all timing state`() {
        val scheduler = DoubleClickScheduler()
        scheduler.recordOriginal(3, ExtraClick.SWING_ONLY)
        assertNull(scheduler.takeNext(3))
        scheduler.clear()

        assertEquals(0, scheduler.pendingCount)
        assertNull(scheduler.takeNext(1))
        scheduler.recordOriginal(1, ExtraClick.ATTACK)
        assertNull(scheduler.takeNext(1))
        assertEquals(ExtraClick.ATTACK, scheduler.takeNext(1))
        assertNull(scheduler.takeNext(1))
    }

    @Test
    fun `invalid values cannot create a burst or an oversized queue`() {
        val scheduler = DoubleClickScheduler()
        scheduler.recordOriginal(100, ExtraClick.ATTACK)
        assertEquals(3, scheduler.pendingCount)
        assertNull(scheduler.takeNext(0))
        repeat(3) { assertEquals(ExtraClick.ATTACK, scheduler.takeNext(0)) }
        assertNull(scheduler.takeNext(0))

        scheduler.recordOriginal(-1, ExtraClick.ATTACK)
        assertNull(scheduler.takeNext(1))
        assertNull(scheduler.takeNext(1))
    }

    @Test
    fun `only one follow-up leaves in a tick, left before right`() {
        val attack = DoubleClickScheduler()
        val use = DoubleClickScheduler()
        attack.recordOriginal(2, ExtraClick.ATTACK)
        use.recordOriginal(2, ExtraClick.USE)

        val ticks = (0..5).map { DoubleClickScheduler.takeNextFrom(attack, use, 1) }

        // Tick 0 is the original's own input tick: nothing extra may ride along with it.
        assertEquals(listOf(null, ExtraClick.ATTACK, ExtraClick.ATTACK, ExtraClick.USE, ExtraClick.USE, null), ticks)
        assertEquals(0, attack.pendingCount)
        assertEquals(0, use.pendingCount)
    }

    @Test
    fun `the right button goes on a tick the left has nothing to give`() {
        val attack = DoubleClickScheduler()
        val use = DoubleClickScheduler()
        use.recordOriginal(1, ExtraClick.USE)

        assertNull(DoubleClickScheduler.takeNextFrom(attack, use, 1))
        assertEquals(ExtraClick.USE, DoubleClickScheduler.takeNextFrom(attack, use, 1))
        assertNull(DoubleClickScheduler.takeNextFrom(attack, use, 1))
    }

    @Test
    fun `the right queue keeps its own spacing while the left button fires`() {
        val attack = DoubleClickScheduler()
        val use = DoubleClickScheduler()
        attack.recordOriginal(1, ExtraClick.ATTACK)
        use.recordOriginal(1, ExtraClick.USE)

        val ticks = (0..7).map { DoubleClickScheduler.takeNextFrom(attack, use, 3) }

        // Both queues count down from the same original click, so the right follow-up lands on the
        // tick after the left one. What it never does is land on the *same* tick: the tick the left
        // button fires is a tick the right queue keeps, which is also why two right follow-ups stay
        // `delay` ticks apart from each other instead of coming out in a burst.
        assertEquals(
            listOf(null, null, null, ExtraClick.ATTACK, ExtraClick.USE, null, null, null),
            ticks,
        )
    }
}
