package com.liquidaddon.aiming

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MousePatternGuardTest {

    @Test
    fun `a two value alternation is broken`() {
        val guard = MousePatternGuard(random = Random(11))
        val out = ArrayList<Pair<Int, Int>>()
        repeat(40) { tick ->
            val step = if (tick % 2 == 0) 1 else -1
            out += guard.guard(step, 0)
        }

        assertFalse(closesACycle(out), "the guard left a period cycle in $out")
        assertTrue(guard.repairs > 0, "the guard never had to do anything")
        // The path is what was planned: a repair is paid back, it is not a detour.
        assertTrue(kotlin.math.abs(out.sumOf { it.first }) <= 3, "the aim drifted off the path: $out")
    }

    @Test
    fun `a constant step is broken`() {
        val guard = MousePatternGuard(random = Random(12))
        val out = ArrayList<Pair<Int, Int>>()
        repeat(60) { out += guard.guard(3, 1) }

        assertFalse(closesACycle(out), "the guard left a period cycle in $out")
        assertTrue(guard.repairs > 0)
        assertEquals(180, out.sumOf { it.first }, "sixty three-count steps still have to be 180 counts")
        assertEquals(60, out.sumOf { it.second })
    }

    @Test
    fun `a hand at rest is left alone`() {
        val guard = MousePatternGuard(random = Random(13))
        repeat(40) { assertEquals(0 to 0, guard.guard(0, 0)) }
        assertEquals(0, guard.repairs, "resting is not a pattern: nothing may be invented out of it")
    }

    @Test
    fun `a varied sequence is not touched`() {
        val guard = MousePatternGuard(random = Random(14))
        val planned = listOf(
            5 to 2, 4 to 1, 6 to -2, 3 to 1, 2 to 0, 5 to -1, 1 to 2, 7 to 1,
            4 to -3, 2 to 1, 3 to 2, 6 to -1, 5 to 1, 4 to 0, 2 to -2, 3 to 1,
        )
        val out = planned.map { (y, p) -> guard.guard(y, p) }
        assertEquals(planned, out, "a sequence that never repeats was repaired anyway")
        assertEquals(0, guard.repairs)
    }

    @Test
    fun `a step is never turned around`() {
        val guard = MousePatternGuard(random = Random(15))
        val out = ArrayList<Pair<Int, Int>>()
        // The assist pulling one count at a time, for four seconds: the worst case for a cycle.
        repeat(80) { out += guard.guard(1, 0) }

        assertTrue(out.all { it.first >= 0 }, "a repair walked the aim backwards: $out")
        assertFalse(closesACycle(out))
        assertEquals(80, out.sumOf { it.first }, "the total travel must not change")
    }

    @Test
    fun `the assist control loop stops closing cycles`() {
        // The loop the module really runs: an error, a pull that is a share of it, the lattice, the
        // step taken off the error. The target keeps sliding away, so the loop never settles — and
        // without the guard it locks into a two or three tick cycle within a few seconds.
        val guard = MousePatternGuard(random = Random(16))
        var error = 2.0
        val out = ArrayList<Pair<Int, Int>>()
        repeat(300) {
            error += 0.4
            val wanted = kotlin.math.round(error * 0.35).toInt().coerceAtMost(8)
            val step = guard.guard(wanted, 0)
            out += step
            error -= step.first
        }

        assertFalse(closesACycle(out), "the loop still closes a cycle: $out")
        assertTrue(out.map { it.first }.toSet().size >= 4, "too few distinct steps left: $out")
        assertTrue(kotlin.math.abs(error) < 4.0, "the aim lost the target: error=$error")
    }

    @Test
    fun `reset forgets the history`() {
        val guard = MousePatternGuard(random = Random(17))
        repeat(20) { guard.guard(2, 0) }
        assertTrue(guard.repairs > 0)

        guard.reset()

        assertEquals(0, guard.repairs)
        // A fresh guard sees no history, so the same step it just refused is planned again.
        val fresh = MousePatternGuard(random = Random(17))
        assertEquals(fresh.guard(2, 0), guard.guard(2, 0))
    }

    /**
     * True when [steps] contains a window that repeats [cycles] times with a period of
     * [maxPeriod] or less — the shape the anticheat calls a pattern.
     */
    private fun closesACycle(
        steps: List<Pair<Int, Int>>,
        maxPeriod: Int = 6,
        cycles: Int = 3,
    ): Boolean {
        for (period in 1..maxPeriod) {
            val need = period * cycles
            if (steps.size < need) continue
            for (end in need..steps.size) {
                val window = steps.subList(end - need, end)
                val repeating = (0 until need - period).all { window[it] == window[it + period] }
                if (repeating && window.any { it != 0 to 0 }) return true
            }
        }
        return false
    }
}
