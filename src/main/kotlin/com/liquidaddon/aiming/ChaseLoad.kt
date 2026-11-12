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
