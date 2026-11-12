package com.liquidaddon.aiming

/**
 * A clock that beats like a hand, not like a metronome.
 *
 * Two of the engine's humanizers scheduled themselves on near-uniform intervals: the wobble of
 * the goal point redrawn every 150–400 ms, and the rest beats of the hand every 1.4–2.6 s.
 * Uniform intervals are a clock a check can average out of a fight: the tracking quality dips or
 * the aim micro-corrects at a readable cadence, and periodicity is precisely what "machine-like
 * rotation" families look for.
 *
 * A hand is bursty instead: mostly short beats, now and then a long hold where nothing is
 * reconsidered, and once in a while an outlier beat — a cluster of quick repicks for the wobble,
 * a long absence for the rest beats, whichever the caller puts in the third band. [nextMs] maps
 * one uniform draw onto that mixture — 55% of draws in the [beat] band, 30% in the [hold] one,
 * 15% in the [outlier] one, each band walked linearly inside itself — so consecutive intervals
 * disagree in kind, not only in length.
 *
 * The weights and the bands the engine passes are design choices, not measurements; what is
 * tested is the shape (see `IrregularClockTest`). The effect on any anticheat is **not measured**.
 */
internal object IrregularClock {

    /** The next interval in milliseconds for a uniform [draw] in 0..1 across the three bands. */
    fun nextMs(draw: Float, beat: IntRange, hold: IntRange, outlier: IntRange): Int {
        val u = draw.coerceIn(0f, 1f)
        return when {
            u < 0.55f -> within(beat, u / 0.55f)
            u < 0.85f -> within(hold, (u - 0.55f) / 0.30f)
            else -> within(outlier, (u - 0.85f) / 0.15f)
        }
    }

    private fun within(range: IntRange, t: Float): Int =
        (range.first + (range.last - range.first) * t.coerceIn(0f, 1f)).toInt()
}
