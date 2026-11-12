package com.liquidaddon.aiming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IrregularClockTest {

    private val beat = 150..400
    private val hold = 800..2000
    private val outlier = 40..120

    @Test
    fun `each draw lands inside its own band`() {
        assertTrue(IrregularClock.nextMs(0.0f, beat, hold, outlier) in beat)
        assertTrue(IrregularClock.nextMs(0.54f, beat, hold, outlier) in beat)
        assertTrue(IrregularClock.nextMs(0.56f, beat, hold, outlier) in hold)
        assertTrue(IrregularClock.nextMs(0.84f, beat, hold, outlier) in hold)
        assertTrue(IrregularClock.nextMs(0.86f, beat, hold, outlier) in outlier)
        assertTrue(IrregularClock.nextMs(1.0f, beat, hold, outlier) in outlier)
    }

    @Test
    fun `the mixture is mostly beats, sometimes holds, rarely outliers`() {
        var beats = 0; var holds = 0; var outs = 0
        for (i in 0 until 1000) {
            when (IrregularClock.nextMs(i / 1000f, beat, hold, outlier)) {
                in beat -> beats++
                in hold -> holds++
                else -> outs++
            }
        }
        assertEquals(550, beats)
        assertEquals(300, holds)
        assertEquals(150, outs)
    }

    @Test
    fun `consecutive draws disagree in kind, not only in length`() {
        val a = IrregularClock.nextMs(0.3f, beat, hold, outlier)
        val b = IrregularClock.nextMs(0.7f, beat, hold, outlier)
        assertTrue(b > 3 * a, "a beat next to a hold: $a vs $b")
    }
}
