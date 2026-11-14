package com.liquidaddon.modules.jumpreset

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ResetRollTest {
    @Test
    fun `accuracy 85 splits a thousand bands into 850 perfect and 75 of each miss`() {
        val bands = ResetRoll.bands(85)

        assertEquals(850, bands[0])
        assertEquals(75, bands[1])
        assertEquals(75, bands[2])
    }

    @Test
    fun `every accuracy fills the thousand bands exactly`() {
        for (accuracy in 0..100) {
            val bands = ResetRoll.bands(accuracy)
            val total = bands[0] + bands[1] + bands[2]

            assertEquals(ResetRoll.BANDS, total, "accuracy $accuracy does not add up")
            assertEquals(accuracy * 10, bands[0], "accuracy $accuracy is not $accuracy% perfect")
            assertTrue(bands[1] >= 0 && bands[2] >= 0, "accuracy $accuracy has a negative band")
        }
    }

    @Test
    fun `accuracy 100 never misses and accuracy 0 never lands`() {
        val all = ResetRoll.bands(100)
        assertEquals(ResetRoll.BANDS, all[0])
        assertEquals(0, all[1])
        assertEquals(0, all[2])

        val none = ResetRoll.bands(0)
        assertEquals(0, none[0])
        // A miss is late or nothing, half and half - never a hidden perfect.
        assertEquals(ResetRoll.LATE_OF_MISS, none[1])
        assertEquals(ResetRoll.BANDS - ResetRoll.LATE_OF_MISS, none[2])
    }

    @Test
    fun `the bands are read in order - perfect then late then nothing`() {
        fun at(roll: Int) = ResetRoll.outcome(roll, 85, 0, 1, 4)

        assertIs<ResetOutcome.Perfect>(at(0))
        assertIs<ResetOutcome.Perfect>(at(849))
        assertIs<ResetOutcome.Late>(at(850))
        assertIs<ResetOutcome.Late>(at(924))
        assertIs<ResetOutcome.NoJump>(at(925))
        assertIs<ResetOutcome.NoJump>(at(999))
    }

    @Test
    fun `a late attempt covers the whole late window and nothing outside it`() {
        val seen = mutableSetOf<Int>()

        for (roll in 0 until 400) {
            val outcome = ResetRoll.outcome(900, 85, roll, 1, 4)
            val late = assertIs<ResetOutcome.Late>(outcome)

            assertTrue(late.ticks in 1..4, "late by ${late.ticks} ticks is outside the window")
            seen += late.ticks
        }

        assertEquals(setOf(1, 2, 3, 4), seen)
    }

    @Test
    fun `a late attempt is never on the perfect window`() {
        for (lateMin in 0..3) {
            for (lateMax in lateMin..6) {
                val ticks = ResetRoll.lateTicks(0, lateMin, lateMax)
                assertTrue(ticks >= 1, "lateMin $lateMin lateMax $lateMax landed on the window")
            }
        }
    }

    @Test
    fun `an upside down or empty late window collapses to one legal value`() {
        // Empty: min == max.
        assertEquals(3, ResetRoll.lateTicks(0, 3, 3))
        assertEquals(3, ResetRoll.lateTicks(7, 3, 3))
        // Upside down: max below min.
        assertEquals(4, ResetRoll.lateTicks(0, 4, 1))
        assertEquals(4, ResetRoll.lateTicks(99, 4, 1))
        // Below one tick of lateness.
        assertEquals(1, ResetRoll.lateTicks(0, 0, 0))
        assertEquals(1, ResetRoll.lateTicks(5, -3, 0))
    }

    @Test
    fun `a roll outside the bands cannot invent an outcome`() {
        assertIs<ResetOutcome.Perfect>(ResetRoll.outcome(-500, 85, 0, 1, 4))
        assertIs<ResetOutcome.NoJump>(ResetRoll.outcome(Int.MAX_VALUE, 85, 0, 1, 4))
        assertIs<ResetOutcome.Perfect>(ResetRoll.outcome(Int.MIN_VALUE, 100, 0, 1, 4))

        // A negative late roll still lands inside the window.
        val late = assertIs<ResetOutcome.Late>(ResetRoll.outcome(900, 85, -7, 1, 4))
        assertTrue(late.ticks in 1..4)
    }

    @Test
    fun `an accuracy outside the slider range is clamped, not trusted`() {
        assertEquals(ResetRoll.bands(0)[0], ResetRoll.bands(-40)[0])
        assertEquals(ResetRoll.bands(100)[0], ResetRoll.bands(900)[0])
    }
}
