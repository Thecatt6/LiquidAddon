package com.liquidaddon.aiming

/**
 * How much of the assist a demand gets, as a function of how hard that demand pushes against the
 * cap of a single tick — with **no unhindered region anywhere**.
 *
 * The first version of this curve kept a knee: below sixty percent of the cap the assist ran at
 * full, and the flat region was the tell. A closed loop at full share nulls the error it sees, so
 * while the demand stayed under the knee the tracking was perfect in a way a hand never is: a hand
 * leaves a small standing offset even on a slow target, and its error grows from the first degree
 * of speed, not from the sixty-first percent of it.
 *
 * So the share is now a straight walk from 1 at no demand to [FLOOR] at a full load: every degree
 * of error the assist closes, it closes a little less completely than the one before, and the
 * steady state of the loop is a small offset that breathes with the fight instead of zero. Past a
 * full load the cap itself still rules and the share stays at the floor.
 *
 * The load the engine feeds in is the current angular error against the cap (`hypot(dyaw, dpitch)
 * / maxSpeed`), which is the demand the tick is about to answer. [FLOOR] is a design choice, not a
 * measurement: low enough that a saturated chase still visibly lags, high enough that the assist
 * still helps a strafing target. The effect on any anticheat is **not measured** — the shape is,
 * in `ChaseLoadTest`.
 */
internal object ChaseLoad {

    /** Fraction of the help still granted at (and past) a full load. */
    const val FLOOR = 0.55f

    /**
     * The share of the help for a load of [load] = demanded turn per tick / cap per tick, 0..1 and
     * beyond. Negative or NaN loads (a delta that came out of nowhere) read as no load at all.
     */
    fun loadShare(load: Float): Float {
        if (load.isNaN() || load <= 0f) return 1f
        return 1f - (1f - FLOOR) * load.coerceIn(0f, 1f)
    }
}

/**
 * The hardest demand of a session, as a percent of the cap of one tick — the `load` field of the
 * telemetry line.
 *
 * One kind of tick must never become that peak: the ticks of a freeze's window. The resume tick
 * applies the queued jump of the mouse - seconds of movement at once - and the recovery ticks
 * behind it chase a target that moved while nothing ran, so their error against the cap reads in
 * the hundreds or thousands of percent and says nothing about the fight. [StallGuard] already
 * names that window ([StallGuard.Phase.RESUME] and [StallGuard.Phase.RECOVERY]), so [LoadPeak]
 * counts it apart: `load` is the fight, `freeze` is what the freeze asked for, and a session that
 * flew through one 97 second freeze reports the same `load` as a session that never left the
 * fight.
 */
internal class LoadPeak {
    /** The hardest counted demand since [reset], as a fraction of the cap of one tick. */
    var peak = 0f
        private set

    /** The hardest demand a freeze's window asked for, kept apart from [peak]; 0 without freezes. */
    var stallPeak = 0f
        private set

    /** [peak] in the percent form the telemetry line prints. */
    val pct: Int get() = (peak * 100f).toInt()

    /** [stallPeak] in percent, for the `freeze` field of the telemetry line. */
    val stallPct: Int get() = (stallPeak * 100f).toInt()

    /**
     * One tick's demand. A tick inside a freeze's window ([fromStall]) is counted, but into
     * [stallPeak]: its demand is the freeze's tail - the target moved while nothing ran, or the
     * queued jump moved the view - and mixing it into [peak] would report the freeze as the fight.
     * Demands that are not a number, or not a demand at all, are ignored on both sides.
     */
    fun see(load: Float, fromStall: Boolean) {
        if (load.isNaN() || load <= 0f) return
        if (fromStall) {
            if (load > stallPeak) stallPeak = load
        } else if (load > peak) {
            peak = load
        }
    }

    /** Forgets both peaks; a new session has demanded nothing yet. */
    fun reset() {
        peak = 0f
        stallPeak = 0f
    }
}
