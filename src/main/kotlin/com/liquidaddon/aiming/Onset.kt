package com.liquidaddon.aiming

/**
 * The fade-in of the help after a reaction pause or a target switch, with a duration of its own
 * per engagement.
 *
 * The fade was already a smoothstep over the configured [baseMs], which removes the kick; what
 * stayed was the *signature*: the same 300 ms shape after the same 200 ± 100 ms reaction, every
 * single engagement, so the tracking of a new target improves on the same clock every time — a
 * curve a check can average out of many engagements. A person's warm-up is not a constant: it is
 * drawn again every time.
 *
 * So each [begin] draws a duration of its own — the configured base scaled into
 * [SPAN_MIN]..[SPAN_MAX] of itself by a uniform [draw] in 0..1 — and [progress] walks the same
 * smoothstep over that drawn duration. The engine hands in the draw (it already owns the random
 * source); the class only does the clock, which is what makes it testable without a game.
 *
 * The span is a design choice, not a measurement: wide enough that two engagements disagree
 * visibly on when the help arrives, narrow enough that the configured [baseMs] still means what
 * its setting says. The effect on any anticheat is **not measured**.
 */
internal class Onset {

    private var start = 0L
    private var durationMs = 0

    /** The last drawn duration, in milliseconds: what the telemetry line reports. */
    val lastDurationMs: Int get() = durationMs

    /**
     * Starts a new engagement at [now]: the fade will take `baseMs * (SPAN_MIN + draw * (SPAN_MAX -
     * SPAN_MIN))` milliseconds. A [baseMs] of zero (or a drawn duration of zero) means no fade at
     * all: [progress] answers 1 from the first tick, which is what the off setting asks for.
     */
    fun begin(now: Long, baseMs: Int, draw: Float) {
        start = now
        val span = SPAN_MIN + (SPAN_MAX - SPAN_MIN) * draw.coerceIn(0f, 1f)
        durationMs = (baseMs * span).toInt()
    }

    /** 0 at [start], 1 one drawn duration later, a smoothstep in between; 1 when there is no fade. */
    fun progress(now: Long): Float {
        if (durationMs <= 0) return 1f
        val t = ((now - start).toFloat() / durationMs).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    companion object {
        const val SPAN_MIN = 0.75f
        const val SPAN_MAX = 1.35f
    }
}
