package com.liquidaddon.aiming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiStatsTest {

    private fun empty() = AiStats.Snapshot(
        ticks = 0, usedPct = 0, fbInit = 0, fbModel = 0, fbNan = 0, fbOther = 0,
        reachPct = 0, meanAiStep = 0f, meanCurveStep = 0f, corr = Float.NaN
    )

    @Test
    fun `a session without AI ticks says so instead of printing zeroes`() {
        // Zeroes would read as "the model answered nothing", which is a different fact from
        // "the engine never asked": Engine = Math, or AI never engaged this session.
        assertEquals("nessun tick AI in questa sessione", AiStats.format(empty(), 100f))
    }

    @Test
    fun `the line is the example line, number by number`() {
        val stats = AiStats()
        repeat(4) { stats.noteRequested() }
        stats.noteFaded()
        repeat(3) {
            stats.noteUsed(aiMag = 1.2f, curveMag = 0.85f, step = AiStats.Step(it + 1f, it + 1f))
        }
        val line = AiStats.format(stats.snapshot(), 100f)
        assertEquals(
            "ai 75% used | fb: init 0, model 0, nan 0, reach 25% | " +
                "step 1.20° vs curve 0.85° | corr 1.00 | aiStr 100",
            line
        )
    }

    @Test
    fun `a fourth fallback family is printed only when it happens`() {
        val stats = AiStats()
        stats.noteRequested()
        stats.noteFallback(AiStats.fallbackKind("model returned 1 values"))
        val line = AiStats.format(stats.snapshot(), 100f)
        assertTrue(line.contains("nan 0, other 1, reach 0%"), line)
    }

    @Test
    fun `percentages round, they do not truncate`() {
        val stats = AiStats()
        repeat(3) { stats.noteRequested() }
        stats.noteUsed(1f, 1f, AiStats.Step(1f, 1f))
        stats.noteUsed(1f, 1f, AiStats.Step(2f, 2f))
        assertEquals(67, stats.snapshot().usedPct)
    }

    @Test
    fun `the reasons of a fallback land in the family a player reads`() {
        assertEquals(AiStats.Fallback.INIT, AiStats.fallbackKind("deep learning not initialised"))
        assertEquals(AiStats.Fallback.MODEL, AiStats.fallbackKind("no model '21KC11KP' loaded"))
        assertEquals(AiStats.Fallback.NAN, AiStats.fallbackKind("model returned NaN"))
        assertEquals(AiStats.Fallback.OTHER, AiStats.fallbackKind("model returned 1 values"))
        assertEquals(AiStats.Fallback.OTHER, AiStats.fallbackKind("ClosedStateException"))
    }

    @Test
    fun `correlation is one on a trace and minus one against it`() {
        assertEquals(1f, AiStats.pearson(listOf(1f, 2f, 3f), listOf(2f, 4f, 6f)))
        assertEquals(-1f, AiStats.pearson(listOf(1f, 2f, 3f), listOf(6f, 4f, 2f)))
    }

    @Test
    fun `a trace that never moves has no correlation to report`() {
        // A dead model output is not "perfectly in phase" with the curve: it is not a signal.
        assertTrue(AiStats.pearson(listOf(2f, 2f, 2f), listOf(1f, 2f, 3f)).isNaN())
        assertTrue(AiStats.pearson(listOf(1f), listOf(1f)).isNaN())
    }

    @Test
    fun `the correlation window forgets what left it`() {
        val stats = AiStats()
        // Five ticks of a model fighting the curve, then twenty of agreement: the window is 20,
        // so the fight must be gone from the number by the time the agreement ends.
        repeat(5) { stats.noteUsed(1f, 1f, AiStats.Step(-(it + 1f), it + 1f)) }
        repeat(20) { stats.noteUsed(1f, 1f, AiStats.Step(it + 1f, it + 1f)) }
        assertEquals(1f, stats.snapshot().corr)
    }

    @Test
    fun `a reset is a new session, not a paused one`() {
        val stats = AiStats()
        repeat(3) { stats.noteRequested() }
        stats.noteUsed(1f, 1f, AiStats.Step(1f, 1f))
        stats.reset()
        assertEquals("nessun tick AI in questa sessione", AiStats.format(stats.snapshot(), 100f))
    }
}
