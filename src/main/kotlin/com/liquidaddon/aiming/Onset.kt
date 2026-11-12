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
 * So each [begin] draws two things of its own: a duration — the configured base scaled into
 * [SPAN_MIN]..[SPAN_MAX] of itself by a uniform [draw] in 0..1 — and a *shape* for the walk, picked
 * by [shape] out of three families an unplanned warm-up actually takes: an ease-in (the hand finds
 * the target slowly), a smoothstep (no kick at either end), an ease-out (a confident first pull
 * that settles). Two engagements then disagree on when the help arrives *and* on the curve it
 * arrives along, so there is no single onset a check can average out of many fights. The engine
 * hands in both draws (it already owns the random source); the class only does the clock, which is
 * what makes it testable without a game.
 *
 * The span is a design choice, not a measurement: wide enough that two engagements disagree
 * visibly on when the help arrives, narrow enough that the configured [baseMs] still means what
 * its setting says. The effect on any anticheat is **not measured**.
 */
internal class Onset {

    private var start = 0L
    private var durationMs = 0
    private var curve = 1

    /** The last drawn duration, in milliseconds: what the telemetry line reports. */
    val lastDurationMs: Int get() = durationMs

    /**
     * Starts a new engagement at [now]: the fade will take `baseMs * (SPAN_MIN + draw * (SPAN_MAX -
     * SPAN_MIN))` milliseconds. A [baseMs] of zero (or a drawn duration of zero) means no fade at
     * all: [progress] answers 1 from the first tick, which is what the off setting asks for.
     */
    fun begin(now: Long, baseMs: Int, draw: Float, shape: Float = 0.5f) {
        start = now
        val span = SPAN_MIN + (SPAN_MAX - SPAN_MIN) * draw.coerceIn(0f, 1f)
        durationMs = (baseMs * span).toInt()
        curve = when {
            shape < 1f / 3f -> 0
            shape < 2f / 3f -> 1
            else -> 2
        }
    }

    /**
     * 0 at [start], 1 one drawn duration later, the drawn curve in between; 1 when there is no
     * fade. The three curves: `t^2` (ease-in), the smoothstep, `1 - (1 - t)^2` (ease-out).
     */
    fun progress(now: Long): Float {
        if (durationMs <= 0) return 1f
        val t = ((now - start).toFloat() / durationMs).coerceIn(0f, 1f)
        return when (curve) {
            0 -> t * t
            2 -> 1f - (1f - t) * (1f - t)
            else -> t * t * (3f - 2f * t)
        }
    }

    companion object {
        const val SPAN_MIN = 0.75f
        const val SPAN_MAX = 1.35f
    }
}
