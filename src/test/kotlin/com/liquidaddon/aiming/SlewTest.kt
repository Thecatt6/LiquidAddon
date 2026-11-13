package com.liquidaddon.aiming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlewTest {

    @Test
    fun `a fall passes through untouched, in the very tick it happens`() {
        val slew = Slew()
        assertEquals(1f, slew.apply(1f, 0.25f, tick = 1), 1.0e-6f)
        assertEquals(0f, slew.apply(0f, 0.25f, tick = 2), 1.0e-6f, "a hand stops at once; the help stops with it")
        assertEquals(0.1f, slew.apply(0.1f, 0.25f, tick = 3), 1.0e-6f)
        assertEquals(0, slew.bites, "no rise was limited on the way down")
    }

    @Test
    fun `a rise comes back over as many ticks as the rate says`() {
        val slew = Slew()
        slew.apply(0f, 0.25f, tick = 1)
        val walk = (2..6).map { slew.apply(1f, 0.25f, tick = it.toLong()) }
        assertEquals(0.25f, walk[0], 1.0e-6f)
        assertEquals(0.5f, walk[1], 1.0e-6f)
        assertEquals(0.75f, walk[2], 1.0e-6f)
        assertEquals(1f, walk[3], 1.0e-6f, "full help on the fourth tick")
        assertEquals(1f, walk[4], 1.0e-6f, "and it stays there")
        // Three of those ticks are steeper than the rate; the fourth rises exactly at it.
        assertEquals(3, slew.bites)
    }

    @Test
    fun `steady state is left alone`() {
        val slew = Slew()
        val held = (1..20).map { slew.apply(0.8f, 0.25f, tick = it.toLong()) }
        assertTrue(held.all { kotlin.math.abs(it - 0.8f) < 1.0e-6f }, held.toString())
        assertEquals(0, slew.bites)
        assertEquals(0, slew.dips)
    }

    @Test
    fun `the counters report the dip and the worst jump the gates asked for`() {
        val slew = Slew()
        slew.apply(0.9f, 0.25f, tick = 1)
        slew.apply(0.1f, 0.25f, tick = 2)
        assertEquals(1, slew.dips, "half or more down to under half counts once")
        slew.apply(0.95f, 0.25f, tick = 3)
        assertEquals(0.85f, slew.worstAsk, 1.0e-6f, "0.1 -> 0.95 is the biggest rise asked for")
        assertTrue(slew.bites > 0)
    }

    @Test
    fun `a tick the engine never reached counts as a tick with no help`() {
        val slew = Slew()
        slew.apply(0.9f, 0.25f, tick = 10)
        // Ticks 11 and 12 answered with an early return - a rest beat, a freeze, the dead zone -
        // so they left no value behind. Without the gap the walk would resume from 0.9 and the
        // snap this class exists to remove would survive every gate that returns early.
        assertEquals(0.25f, slew.apply(0.9f, 0.25f, tick = 13), 1.0e-6f)
        assertEquals(0.5f, slew.apply(0.9f, 0.25f, tick = 14), 1.0e-6f)
        assertEquals(0.75f, slew.apply(0.9f, 0.25f, tick = 15), 1.0e-6f)
        assertEquals(0.9f, slew.apply(0.9f, 0.25f, tick = 16), 1.0e-6f)
    }

    @Test
    fun `a rate of zero or less never limits anything`() {
        val slew = Slew()
        slew.apply(0f, 0f, tick = 1)
        assertEquals(1f, slew.apply(1f, 0f, tick = 2), 1.0e-6f, "the limiter is off, so the jump is allowed")
        assertEquals(0, slew.bites)
    }

    @Test
    fun `reset forgets the walk and the counters`() {
        val slew = Slew()
        slew.apply(0f, 0.25f, tick = 1)
        slew.apply(1f, 0.25f, tick = 2)
        assertTrue(slew.bites > 0 && slew.dips > 0 && slew.worstAsk > 0f)
        slew.reset()
        assertEquals(0, slew.bites)
        assertEquals(0, slew.dips)
        assertEquals(0f, slew.worstAsk, 1.0e-6f)
        assertEquals(0.7f, slew.apply(0.7f, 0.25f, tick = 3), 1.0e-6f, "and the walk starts over")
    }
}
