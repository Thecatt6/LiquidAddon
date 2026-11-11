package com.liquidaddon.aiming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StallGuardTest {

    /** A healthy tick clock: fifty milliseconds, the rate the game runs at. */
    private val tick = 50L

    private fun clock(start: Long = 0L) = object {
        var now = start
        fun step(ms: Long = tick): Long {
            now += ms
            return now
        }
    }

    @Test
    fun `an even tick clock stays normal and fully shared`() {
        val guard = StallGuard()
        val c = clock()
        repeat(40) {
            assertEquals(StallGuard.Phase.NORMAL, guard.observe(c.step()))
            assertEquals(1f, guard.assistShare())
            assertEquals(2.5f, guard.trustedOwn(2.5f))
        }
        assertEquals(0, guard.stalls)
        assertEquals(0L, guard.lastStallMs)
    }

    @Test
    fun `a gap of seconds opens the window on the tick that resumes`() {
        val guard = StallGuard()
        val c = clock()
        repeat(5) { guard.observe(c.step()) }
        assertEquals(StallGuard.Phase.RESUME, guard.observe(c.step(2500)))
        assertEquals(1, guard.stalls)
        assertEquals(2500L, guard.lastStallMs)
    }

    @Test
    fun `the resume tick glides and does not certify the queued jump`() {
        val guard = StallGuard()
        val c = clock()
        repeat(5) { guard.observe(c.step()) }
        guard.observe(c.step(3000))
        assertEquals(StallGuard.RESUME_SHARE, guard.assistShare())
        assertEquals(0f, guard.trustedOwn(31.4f))
        assertEquals(0f, guard.trustedCounts(209f))
    }

    @Test
    fun `the strength ramps back to one over the recovery ticks`() {
        val guard = StallGuard()
        val c = clock()
        repeat(3) { guard.observe(c.step()) }
        guard.observe(c.step(2000))
        val shares = mutableListOf(guard.assistShare())
        val phases = mutableListOf(StallGuard.Phase.RESUME)
        repeat(StallGuard.DEFAULT_RECOVERY_TICKS) {
            phases += guard.observe(c.step())
            shares += guard.assistShare()
        }
        assertEquals(
            listOf(
                StallGuard.Phase.RESUME, StallGuard.Phase.RECOVERY,
                StallGuard.Phase.RECOVERY, StallGuard.Phase.RECOVERY,
            ),
            phases,
        )
        // Climbing, never above one, and back to one the moment the window closes.
        for (i in 1 until shares.size) assertTrue(shares[i] > shares[i - 1], "share must climb: $shares")
        assertTrue(shares.all { it <= 1f }, "share must never exceed one: $shares")
        assertEquals(1f, shares.last())
        assertEquals(StallGuard.Phase.NORMAL, guard.observe(c.step()))
        assertEquals(1f, guard.assistShare())
    }

    @Test
    fun `the own-turn is trusted again once the sanitize window closes`() {
        val guard = StallGuard()
        val c = clock()
        repeat(3) { guard.observe(c.step()) }
        guard.observe(c.step(1800))
        assertEquals(0f, guard.trustedOwn(30f), "resume tick")
        guard.observe(c.step())
        assertEquals(0f, guard.trustedOwn(30f), "the frame boundary can put the jump one tick later")
        guard.observe(c.step())
        assertEquals(30f, guard.trustedOwn(30f), "third tick of the window is a real hand again")
    }

    @Test
    fun `a bad tick is not a freeze and the threshold itself is one`() {
        val soft = StallGuard()
        val c = clock()
        repeat(3) { soft.observe(c.step()) }
        assertEquals(StallGuard.Phase.NORMAL, soft.observe(c.step(120)))
        assertEquals(StallGuard.Phase.NORMAL, soft.observe(c.step(StallGuard.DEFAULT_STALL_MS - 1)))
        assertEquals(0, soft.stalls)
        assertEquals(StallGuard.Phase.RESUME, soft.observe(c.step(StallGuard.DEFAULT_STALL_MS)))
        assertEquals(1, soft.stalls)
    }

    @Test
    fun `two freezes count twice and reset forgets them`() {
        val guard = StallGuard()
        val c = clock()
        repeat(3) { guard.observe(c.step()) }
        guard.observe(c.step(2100))
        repeat(StallGuard.DEFAULT_RECOVERY_TICKS + 1) { guard.observe(c.step()) }
        guard.observe(c.step(4200))
        assertEquals(2, guard.stalls)
        assertEquals(4200L, guard.lastStallMs)
        guard.reset()
        assertEquals(0, guard.stalls)
        assertEquals(0L, guard.lastStallMs)
        assertEquals(StallGuard.Phase.NORMAL, guard.observe(c.step()))
        assertEquals(1f, guard.assistShare())
    }
}
