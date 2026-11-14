package com.liquidaddon.modules.hitselect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DropRuleTest {
    /** The switches as the module ships them. */
    private val defaults = HitSelectSettings(
        pauseMode = true,
        alwaysWhenHurt = true,
        onlyWhenHurt = false,
        onlyWhenTargetVulnerable = true,
        maxTargetHurtTime = 0,
        preferKBReduction = true,
    )

    private fun stance(myHurt: Int = 0, targetHurt: Int = 0, falling: Boolean = false, moving: Boolean = false) =
        HitSelectStance(
            myHurtTime = myHurt,
            targetHurtTime = targetHurt,
            fallingForCrit = falling,
            moving = moving,
        )

    @Test
    fun `with the defaults an attack goes through and the ones inside the pause do not`() {
        assertNull(DropRule.reason(defaults, pauseRemaining = 0, stance()))

        for (pause in 1..20) {
            val reason = DropRule.reason(defaults, pauseRemaining = pause, stance())
            assertEquals("paused ($pause ticks)", reason)
        }
    }

    @Test
    fun `being hurt keeps the attack, inside a pause and outside it`() {
        assertNull(DropRule.reason(defaults, pauseRemaining = 0, stance(myHurt = 4)))
        assertNull(DropRule.reason(defaults, pauseRemaining = 9, stance(myHurt = 1)))

        val cold = defaults.copy(alwaysWhenHurt = false)
        assertEquals("paused (9 ticks)", DropRule.reason(cold, pauseRemaining = 9, stance(myHurt = 1)))
    }

    @Test
    fun `OnlyWhenHurt drops exactly the attacks that are not hurt`() {
        val only = defaults.copy(alwaysWhenHurt = false, onlyWhenHurt = true)

        assertEquals("not hurt", DropRule.reason(only, pauseRemaining = 0, stance(myHurt = 0)))
        assertNull(DropRule.reason(only, pauseRemaining = 0, stance(myHurt = 1)))
        // Inside a pause the pause answers first, and it says the same thing.
        assertEquals(
            "paused (3 ticks) and not hurt",
            DropRule.reason(only, pauseRemaining = 3, stance(myHurt = 0)),
        )
    }

    @Test
    fun `an invulnerable target is dropped only above MaxTargetHurtTime`() {
        assertNull(DropRule.reason(defaults, 0, stance(targetHurt = 0)))
        assertEquals(
            "target invulnerable (hurtTime 1 > 0)",
            DropRule.reason(defaults, 0, stance(targetHurt = 1)),
        )

        val lenient = defaults.copy(maxTargetHurtTime = 3)
        assertNull(DropRule.reason(lenient, 0, stance(targetHurt = 3)))
        assertEquals(
            "target invulnerable (hurtTime 4 > 3)",
            DropRule.reason(lenient, 0, stance(targetHurt = 4)),
        )

        val off = defaults.copy(onlyWhenTargetVulnerable = false)
        assertNull(DropRule.reason(off, 0, stance(targetHurt = 10)))
    }

    @Test
    fun `Active mode with the KB preference drops a moving attack and keeps a still one`() {
        val active = defaults.copy(pauseMode = false)

        assertEquals("moving with no knockback on us", DropRule.reason(active, 0, stance(moving = true)))
        assertNull(DropRule.reason(active, 0, stance(moving = false)))
        // Moving but hurt: the knockback of that attack is worth more than the one it would deal.
        assertNull(DropRule.reason(active, 0, stance(moving = true, myHurt = 2)))
    }

    @Test
    fun `Active mode with the crit preference keeps only the falling attack`() {
        val crits = defaults.copy(pauseMode = false, preferKBReduction = false)

        assertNull(DropRule.reason(crits, 0, stance(falling = true)))
        assertEquals("no crit fall", DropRule.reason(crits, 0, stance(falling = false)))
    }

    @Test
    fun `a pause is answered before anything else is looked at`() {
        // Not hurt, target vulnerable, standing still: every later rule would keep it, the pause
        // still drops it.
        assertEquals("paused (7 ticks)", DropRule.reason(defaults, 7, stance()))
        // And a pause of zero is no pause at all.
        assertNull(DropRule.reason(defaults, 0, stance()))
        assertNull(DropRule.reason(defaults, -3, stance()))
    }

    @Test
    fun `the pause is spread around the slider and never below one tick`() {
        for (base in 1..20) {
            var lowest = Int.MAX_VALUE
            var highest = Int.MIN_VALUE
            var total = 0L

            for (roll in 0 until DropRule.pauseRolls(base)) {
                val length = DropRule.pauseLength(base, roll)
                assertTrue(length >= 1, "base $base roll $roll dropped below one tick")
                lowest = minOf(lowest, length)
                highest = maxOf(highest, length)
                total += length
            }

            val rolls = DropRule.pauseRolls(base)
            val spread = base / DropRule.PAUSE_SPREAD_DIVISOR
            assertEquals(base - spread, lowest, "base $base does not reach its lower edge")
            assertEquals(base + spread, highest, "base $base does not reach its upper edge")
            // The average is exactly the slider: the spread moves the shape, not the rate.
            assertEquals(base.toLong(), total / rolls, "base $base changed the average pause")
        }
    }

    @Test
    fun `every length inside the spread comes out equally often`() {
        val base = 10
        val rolls = DropRule.pauseRolls(base)
        assertEquals(5, rolls)

        val seen = (0 until rolls).map { DropRule.pauseLength(base, it) }.sorted()
        assertEquals(listOf(8, 9, 10, 11, 12), seen)
    }

    @Test
    fun `a pause too short to spread stays exactly where the slider says`() {
        for (base in 1..3) {
            assertEquals(1, DropRule.pauseRolls(base))
            for (roll in -5..5) {
                assertEquals(base, DropRule.pauseLength(base, roll))
            }
        }
    }

    @Test
    fun `a base or a roll outside the range cannot produce a nonsense pause`() {
        assertEquals(1, DropRule.pauseLength(0, 0))
        assertEquals(1, DropRule.pauseLength(-9, 3))
        // A negative roll still lands inside the spread.
        assertTrue(DropRule.pauseLength(10, -3) in 8..12)
        assertTrue(DropRule.pauseLength(10, Int.MIN_VALUE) in 8..12)
    }
}
