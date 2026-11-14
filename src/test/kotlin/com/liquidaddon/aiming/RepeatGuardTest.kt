package com.liquidaddon.aiming

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepeatGuardTest {
    private val unit = 0.5f

    @Test
    fun `the third identical delta in a row leaves moved by one count`() {
        val guard = RepeatGuard()

        assertEquals(2.0f to 1.0f, guard.pass(2.0f, 1.0f, unit))
        assertEquals(2.0f to 1.0f, guard.pass(2.0f, 1.0f, unit))

        val third = guard.pass(2.0f, 1.0f, unit)
        assertEquals(2.0f + unit to 1.0f, third, "the break moves the busier axis by one count")
        assertEquals(1, guard.breaks)
    }

    @Test
    fun `a broken run starts over, so three in a row never leaves`() {
        val guard = RepeatGuard()
        val out = (1..9).map { guard.pass(2.0f, 1.0f, unit) }

        for (i in 2 until out.size) {
            val tripleIdentical = out[i] == out[i - 1] && out[i - 1] == out[i - 2]
            assertTrue(!tripleIdentical, "ticks ${i - 2}..$i left identical: $out")
        }
        assertEquals(3, guard.breaks, "one break per completed run of three")
    }

    @Test
    fun `the break alternates sign, so a long chase does not walk off`() {
        val guard = RepeatGuard()
        val out = (1..6).map { guard.pass(2.0f, 1.0f, unit) }

        val firstBreak = out[2]
        val secondBreak = out[5]
        assertEquals(2.0f + unit, firstBreak.first)
        assertEquals(2.0f - unit, secondBreak.first, "the second break gives the count back")
    }

    @Test
    fun `different deltas pass untouched`() {
        val guard = RepeatGuard()
        assertEquals(1.0f to 0.0f, guard.pass(1.0f, 0.0f, unit))
        assertEquals(1.5f to 0.5f, guard.pass(1.5f, 0.5f, unit))
        assertEquals(1.0f to 0.0f, guard.pass(1.0f, 0.0f, unit))
        assertEquals(0, guard.breaks)
    }

    @Test
    fun `a rest is broken too, and breathes out and back on both axes`() {
        val guard = RepeatGuard()
        val out = (1..9).map { guard.pass(0.0f, 0.0f, unit) }

        assertEquals(0.0f to 0.0f, out[0])
        assertEquals(0.0f to 0.0f, out[1])
        assertEquals(unit to 0.0f, out[2], "the third rest drifts one count of yaw")
        assertEquals(0.0f to 0.0f, out[3])
        assertEquals(0.0f to 0.0f, out[4])
        assertEquals(0.0f to -unit, out[5], "the next rest breathes one count of pitch, back")
        for (i in 2 until out.size) {
            assertTrue(out[i] != out[i - 1] || out[i - 1] != out[i - 2], "rest triple at $i")
        }
    }

    @Test
    fun `the break is exactly one count on exactly one axis`() {
        val guard = RepeatGuard()
        guard.pass(3.0f, -2.0f, unit)
        guard.pass(3.0f, -2.0f, unit)
        val out = guard.pass(3.0f, -2.0f, unit)

        val movedYaw = abs(out.first - 3.0f) > 1.0e-6f
        val movedPitch = abs(out.second - -2.0f) > 1.0e-6f
        assertTrue(movedYaw xor movedPitch, "exactly one axis moves: $out")
        val moved = if (movedYaw) abs(out.first - 3.0f) else abs(out.second - -2.0f)
        assertEquals(unit, moved, 1.0e-6f, "and it moves by one mouse count, not by an invented angle")
    }

    @Test
    fun `without a lattice there is nothing to stay on, so nothing is broken`() {
        val guard = RepeatGuard()
        repeat(5) { assertEquals(2.0f to 1.0f, guard.pass(2.0f, 1.0f, 0f)) }
        repeat(5) { assertEquals(2.0f to 1.0f, guard.pass(2.0f, 1.0f, -1f)) }
        assertEquals(0, guard.breaks)
    }

    @Test
    fun `reset forgets the run and the counter`() {
        val guard = RepeatGuard()
        guard.pass(2.0f, 1.0f, unit)
        guard.pass(2.0f, 1.0f, unit)
        guard.pass(2.0f, 1.0f, unit)
        assertEquals(1, guard.breaks)

        guard.reset()
        assertEquals(0, guard.breaks)
        assertEquals(2.0f to 1.0f, guard.pass(2.0f, 1.0f, unit), "a fresh session may repeat twice")
        assertEquals(2.0f to 1.0f, guard.pass(2.0f, 1.0f, unit))
        assertEquals(0, guard.breaks)
    }
}
