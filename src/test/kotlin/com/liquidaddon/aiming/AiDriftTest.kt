package com.liquidaddon.aiming

import kotlin.test.Test
import kotlin.test.assertEquals

class AiDriftTest {

    @Test
    fun `an agreeing window leaves the model alone`() {
        assertEquals(1f, AiDrift.modelShare(corr = 0.71f, windowFull = true))
        assertEquals(1f, AiDrift.modelShare(corr = AiDrift.MIN_CORR, windowFull = true),
            "the threshold itself is still agreement")
        assertEquals(1f, AiDrift.modelShare(corr = 1f, windowFull = true))
    }

    @Test
    fun `a drifted window takes half the step back, never all of it`() {
        assertEquals(AiDrift.DRIFTED_MODEL_SHARE, AiDrift.modelShare(corr = 0.29f, windowFull = true))
        assertEquals(AiDrift.DRIFTED_MODEL_SHARE, AiDrift.modelShare(corr = 0f, windowFull = true))
        assertEquals(AiDrift.DRIFTED_MODEL_SHARE, AiDrift.modelShare(corr = -0.9f, windowFull = true),
            "anti-correlation is drift, not a different species")
        assertEquals(0.5f, AiDrift.DRIFTED_MODEL_SHARE, "halfway is the documented policy")
    }

    @Test
    fun `a young or dead window is not evidence`() {
        assertEquals(1f, AiDrift.modelShare(corr = Float.NaN, windowFull = true),
            "a trace that never moves has no correlation to act on")
        assertEquals(1f, AiDrift.modelShare(corr = 0.1f, windowFull = false),
            "partial windows do not bend the step, whatever they seem to say")
        assertEquals(1f, AiDrift.modelShare(corr = Float.NaN, windowFull = false))
    }

    @Test
    fun `the guard reads what the telemetry shows`() {
        // The pairs of a healthy session: model and curve move together, magnitudes apart.
        val stats = AiStats()
        repeat(AiStats.WINDOW) { i ->
            stats.noteUsed(
                aiMag = 1.2f + i * 0.01f,
                curveMag = 0.8f,
                step = AiStats.Step(aiYaw = 1f + i * 0.1f, curveYaw = 2f + i * 0.2f),
            )
        }
        val corr = stats.snapshot().corr
        assertEquals(1f, AiDrift.modelShare(corr, windowFull = true), "corr 1.00 -> the model drives")

        // The same window, but the model now answers against the geometry of the situation.
        stats.reset()
        repeat(AiStats.WINDOW) { i ->
            stats.noteUsed(
                aiMag = 1f,
                curveMag = 1f,
                step = AiStats.Step(aiYaw = 1f + i * 0.1f, curveYaw = 2f - i * 0.2f),
            )
        }
        val drifted = stats.snapshot().corr
        assertEquals(
            AiDrift.DRIFTED_MODEL_SHARE,
            AiDrift.modelShare(drifted, windowFull = true),
            "corr -1.00 -> half curve until the window forgets"
        )
    }
}
