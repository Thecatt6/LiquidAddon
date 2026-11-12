package com.liquidaddon.aiming

/**
 * The crosshair gate's goodbye, without the cliff.
 *
 * `OnlyOnCrosshair` keeps the assist alive while the ray crosses the hitbox and for a grace after
 * it leaves. The grace was a constant 140 ms of *full* assist followed by a hard stop: a check that
 * crosses the ray against the hitbox per tick sees the quality of the tracking drop on the same
 * latency every single time the crosshair slips off the edge — the constancy is the signature, not
 * the edge.
 *
 * So leaving the hitbox starts a fade of its own: the share of the help walks from 1 to 0 over a
 * duration drawn per exit (the configured grace scaled into [SPAN_MIN]..[SPAN_MAX] of itself), and
 * the gate closes exactly when the share reaches zero — the cut lands on nothing, so there is no
 * step for the camera or the packets to show. Coming back onto the box cancels the fade at 1.
 *
 * The draw is handed in; the class only keeps the clock (see `EdgeGraceTest`).
 */
internal class EdgeGrace {

    private var off = false
    private var leftAt = 0L
    private var drawnMs = 0L

    /** The drawn duration of the current exit fade, in milliseconds; 0 while on the box. */
    val lastGraceMs: Long get() = if (off) drawnMs else 0L

    /** The crosshair is on the hitbox: no fade running, full help. */
    fun enter() {
        off = false
    }

    /** The crosshair left the hitbox at [now]: draw this exit's fade. Repeated calls do not redraw. */
    fun leave(now: Long, baseMs: Long, draw: Float) {
        if (off) return
        off = true
        leftAt = now
        drawnMs = (baseMs * (SPAN_MIN + (SPAN_MAX - SPAN_MIN) * draw.coerceIn(0f, 1f))).toLong()
    }

    /** The share of the help at [now]: 1 on the box, a smoothstep down to 0 during the drawn fade. */
    fun share(now: Long): Float {
        if (!off || drawnMs <= 0L) return if (off) 0f else 1f
        val t = ((now - leftAt).toFloat() / drawnMs).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)
    }

    /** The fade is over: the gate may close. Never true while the crosshair is on the box. */
    fun expired(now: Long): Boolean = off && (drawnMs <= 0L || now - leftAt >= drawnMs)

    /** Forgets the gate: on the box, nothing running. */
    fun reset() {
        off = false
        leftAt = 0L
        drawnMs = 0L
    }

    companion object {
        const val SPAN_MIN = 0.6f
        const val SPAN_MAX = 1.5f
    }
}
