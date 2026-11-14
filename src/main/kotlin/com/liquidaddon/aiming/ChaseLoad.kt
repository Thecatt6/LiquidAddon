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
/**
 * The hardest demand of a session, as a percent of the cap of one tick — the `load` field of the
 * telemetry line.
 *
 * One kind of tick must never become that peak: the tick that resumes a freeze. Its "demand" is the
 * queued jump of the mouse, seconds of movement applied at once, so its error against the cap reads
 * in the thousands of percent and says nothing about the fight. [StallGuard] already names that tick
 * ([StallGuard.Phase.RESUME]), so the peak simply does not listen to it: a session that flew through
 * one 97 second freeze and a session that never left the fight then report the same load for the
 * same fight, which is what the field is for.
 */
internal class LoadPeak {
    /** The hardest counted demand since [reset], as a fraction of the cap of one tick. */
    var peak = 0f
        private set

    /** [peak] in the percent form the telemetry line prints. */
    val pct: Int get() = (peak * 100f).toInt()

    /**
     * One tick's demand. Ignored when [fromStall] (the freeze is speaking, not the target), and when
     * the demand is not a number or not a demand at all.
     */
    fun see(load: Float, fromStall: Boolean) {
        if (fromStall) return
        if (load.isNaN() || load <= 0f) return
        if (load > peak) peak = load
    }

    /** Forgets the peak; a new session has demanded nothing yet. */
    fun reset() {
        peak = 0f
    }
}

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
